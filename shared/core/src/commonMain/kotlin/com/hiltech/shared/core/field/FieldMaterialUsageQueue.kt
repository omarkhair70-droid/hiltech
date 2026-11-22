package com.hiltech.shared.core.field

import androidx.room3.withWriteTransaction
import com.hiltech.shared.core.local.HiltechLocalDatabase
import com.hiltech.shared.core.local.OfflineSchemaContract
import com.hiltech.shared.core.local.PendingCommandEntity
import com.hiltech.shared.core.local.PendingCommandStates
import com.hiltech.shared.core.work.FieldPersonalIssuedStockLineDto
import com.hiltech.shared.core.work.FieldPersonalIssuedStockSnapshotDto
import com.hiltech.shared.core.work.TechnicianJobBundleDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class FieldMaterialUsageIntent(
    val operationId: String,
    val workOrderId: String,
    val requirementId: String,
    val stockItemId: String,
    val stockItemCode: String,
    val stockItemName: String,
    val quantity: String,
    val unitCode: String,
    val issuedVersion: Long,
    val state: String,
    val lastResultCode: String?,
)

/**
 * Durable local *usage report intent*. It never decrements the authoritative
 * issued balance. The server revalidates Work/assignment/issued versions when
 * the same operationId is replayed after connectivity returns.
 */
class FieldMaterialUsageQueue(
    private val database: HiltechLocalDatabase,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun list(
        identityId: String,
        installationId: String,
        workOrderId: String,
    ): List<FieldMaterialUsageIntent> =
        database.pendingCommandDao()
            .materialUsageForWorkOrder(identityId, installationId, workOrderId)
            .mapNotNull { it.asMaterialIntentOrNull() }

    suspend fun queue(
        identityId: String,
        installationId: String,
        bundle: TechnicianJobBundleDto,
        issued: FieldPersonalIssuedStockSnapshotDto,
        item: FieldPersonalIssuedStockLineDto,
        quantity: String,
        operationId: String,
        nowEpochMs: Long,
        verifiedOnlineThisSession: Boolean,
    ): FieldMaterialUsageIntent {
        require(verifiedOnlineThisSession) {
            "A stale cached WorkOrder is not authority to capture a new material-usage intent."
        }
        require(identityId.isNotBlank() && installationId.isNotBlank())
        require(operationId.matches(UUID_REGEX))
        require(bundle.workOrderId.matches(UUID_REGEX))
        require(bundle.assignment.assignmentId.matches(UUID_REGEX))
        require(item.requirementId.matches(UUID_REGEX) && item.stockItemId.matches(UUID_REGEX))
        val binding = requireNotNull(bundle.binding)
        require(bundle.workOrderVersion >= 1 && bundle.assignment.version >= 1 && item.sourceVersion >= 1)
        require(issued.status == "SOURCE_CURRENT")
        require(issued.workOrderId == bundle.workOrderId)
        require(issued.workOrderVersion == bundle.workOrderVersion)
        require(
            issued.items.count {
                it.requirementId == item.requirementId &&
                    it.stockItemId == item.stockItemId &&
                    it.sourceVersion == item.sourceVersion &&
                    it.unitCode == item.unitCode
            } == 1,
        ) {
            "The issued-material source line is no longer unique/current."
        }

        val normalizedQuantity = ExactMaterialDecimal.parse(quantity)
        val outstanding = ExactMaterialDecimal.parse(item.outstandingQuantity)
        require(normalizedQuantity <= outstanding) {
            "Reported usage cannot exceed the verified outstanding issued quantity."
        }

        return database.withWriteTransaction {
            require(
                database.pendingCommandDao()
                    .outstandingCompletion(identityId, bundle.workOrderId)
                    .isEmpty(),
            ) {
                "A completion submission is already pending for this WorkOrder."
            }
            require(
                database.pendingCommandDao()
                    .outstandingFieldCommand(identityId, bundle.workOrderId)
                    .isEmpty(),
            ) {
                "Resolve or sync pending field transitions before material usage."
            }
            require(database.pendingCommandDao().get(operationId) == null) {
                "A local operationId cannot be reused."
            }
            val prior = database.pendingCommandDao()
                .materialUsageForWorkOrder(identityId, installationId, bundle.workOrderId)
                .filter {
                    it.state in setOf(
                        PendingCommandStates.PENDING,
                        PendingCommandStates.RETRYABLE,
                        PendingCommandStates.SYNCING,
                    )
                }
                .mapNotNull { it.asMaterialIntentOrNull() }
                .filter {
                    it.requirementId == item.requirementId &&
                        it.stockItemId == item.stockItemId &&
                        it.issuedVersion == item.sourceVersion &&
                        it.unitCode == item.unitCode
                }

            val locallyPending =
                prior.fold(ExactMaterialDecimal.ZERO) { total, row ->
                    total + ExactMaterialDecimal.parse(row.quantity)
                }
            require(locallyPending + normalizedQuantity <= outstanding) {
                "Pending local usage plus this report exceeds the verified outstanding issued quantity."
            }

            val payload = buildJsonObject {
                put("assignmentId", bundle.assignment.assignmentId)
                put("assignmentVersion", bundle.assignment.version)
                put("workVersion", bundle.workOrderVersion)
                put("requirementId", item.requirementId)
                put("stockItemId", item.stockItemId)
                put("issuedVersion", item.sourceVersion)
                put("quantity", normalizedQuantity.canonical)
                put("unitCode", item.unitCode)
                put("_localStockItemCode", item.stockItemCode)
                put("_localStockItemName", item.stockItemName)
            }.toString()

            val entity = PendingCommandEntity(
                operationId = operationId,
                actorUserIdentityId = identityId,
                deviceId = installationId,
                commandType = COMMAND_TYPE,
                targetType = "WorkOrder",
                targetId = bundle.workOrderId,
                baseVersion = bundle.workOrderVersion,
                payloadVersion = OfflineSchemaContract.CURRENT_PENDING_PAYLOAD_VERSION,
                payloadJson = payload,
                clientOccurredAtEpochMs = nowEpochMs,
                enqueuedAtEpochMs = nowEpochMs,
                localSequence = database.pendingCommandDao().maxLocalSequence() + 1,
                state = PendingCommandStates.PENDING,
                retryCount = 0,
                nextRetryAtEpochMs = null,
                lastAttemptAtEpochMs = null,
                lastResultCode = null,
                lastServerVersion = null,
                lastCorrelationId = null,
                contractVersion = OfflineSchemaContract.CURRENT_COMMAND_CONTRACT_VERSION,
                policyBindingRef = "binding:" + binding.bindingRevision,
                updatedAtEpochMs = nowEpochMs,
            )
            database.pendingCommandDao().insert(entity)
            requireNotNull(entity.asMaterialIntentOrNull())
        }
    }

    private fun PendingCommandEntity.asMaterialIntentOrNull(): FieldMaterialUsageIntent? {
        if (commandType != COMMAND_TYPE || targetType != "WorkOrder") return null
        val payload =
            runCatching { json.parseToJsonElement(payloadJson).jsonObject }
                .getOrNull()
                ?: return null
        fun value(key: String): String? = payload[key]?.jsonPrimitive?.content
        val requirementId = value("requirementId") ?: return null
        val stockItemId = value("stockItemId") ?: return null
        val issuedVersion = value("issuedVersion")?.toLongOrNull() ?: return null
        val quantity = value("quantity") ?: return null
        val unitCode = value("unitCode") ?: return null
        return FieldMaterialUsageIntent(
            operationId = operationId,
            workOrderId = targetId,
            requirementId = requirementId,
            stockItemId = stockItemId,
            stockItemCode = value("_localStockItemCode").orEmpty(),
            stockItemName = value("_localStockItemName").orEmpty(),
            quantity = quantity,
            unitCode = unitCode,
            issuedVersion = issuedVersion,
            state = state,
            lastResultCode = lastResultCode,
        )
    }

    private companion object {
        const val COMMAND_TYPE = "ReportMaterialUsage"
        val UUID_REGEX =
            Regex(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-" +
                    "[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}",
            )
    }
}

private class ExactMaterialDecimal private constructor(
    /** Non-negative fixed-scale (6dp) digits, no sign or decimal separator. */
    private val scaledDigits: String,
    val canonical: String,
) : Comparable<ExactMaterialDecimal> {
    override fun compareTo(other: ExactMaterialDecimal): Int {
        val left = scaledDigits.trimStart('0').ifEmpty { "0" }
        val right = other.scaledDigits.trimStart('0').ifEmpty { "0" }
        return when {
            left.length != right.length -> left.length.compareTo(right.length)
            else -> left.compareTo(right)
        }
    }

    operator fun plus(other: ExactMaterialDecimal): ExactMaterialDecimal {
        val width = maxOf(scaledDigits.length, other.scaledDigits.length)
        val left = scaledDigits.padStart(width, '0')
        val right = other.scaledDigits.padStart(width, '0')
        val out = CharArray(width)
        var carry = 0
        for (index in width - 1 downTo 0) {
            val sum = (left[index] - '0') + (right[index] - '0') + carry
            out[index] = ('0'.code + (sum % 10)).toChar()
            carry = sum / 10
        }
        val digits =
            (if (carry > 0) carry.toString() else "") +
                out.concatToString()
        return fromScaledDigits(digits)
    }

    companion object {
        val ZERO = fromScaledDigits("0")

        fun parse(raw: String): ExactMaterialDecimal {
            val text = raw.trim()
            require(PATTERN.matches(text)) {
                "Material quantity must be a positive exact decimal with up to 6 decimals."
            }
            val parts = text.split('.', limit = 2)
            val integer = parts[0].trimStart('0').ifEmpty { "0" }
            val fraction = parts.getOrNull(1).orEmpty().padEnd(SCALE, '0')
            val scaled = (integer + fraction).trimStart('0').ifEmpty { "0" }
            require(scaled != "0") { "Material quantity must be positive." }
            return fromScaledDigits(scaled)
        }

        private fun fromScaledDigits(raw: String): ExactMaterialDecimal {
            val normalized = raw.trimStart('0').ifEmpty { "0" }
            val padded = normalized.padStart(SCALE + 1, '0')
            val integerPart =
                padded.dropLast(SCALE)
                    .trimStart('0')
                    .ifEmpty { "0" }
            val fractionPart =
                padded.takeLast(SCALE)
                    .trimEnd('0')
            val canonical =
                if (fractionPart.isEmpty()) integerPart
                else integerPart + "." + fractionPart
            return ExactMaterialDecimal(normalized, canonical)
        }

        private const val SCALE = 6
        private val PATTERN = Regex("[0-9]{1,14}(?:\\.[0-9]{1,6})?")
    }
}

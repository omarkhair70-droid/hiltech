package com.hiltech.server.companysettings

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID

/** Bounded company operating configuration; grants no approval or signing authority. */
interface CompanySettingsAuthorityPort {
    fun canManageCompanySettings(actor: UUID, organizationId: UUID): Boolean
}

enum class PurchaseOrderNumberingSource { COMPANY, DEPLOYMENT, NOT_CONFIGURED }
data class PurchaseOrderNumberingSnapshot(
    val organizationId: UUID, val source: PurchaseOrderNumberingSource, val prefix: String?,
    val version: Long, val revisionId: UUID? = null, val changedAt: Instant? = null,
) {
    val ready: Boolean get() = prefix != null && validExistingPurchaseOrderPrefix(prefix)
    fun code(serial: Long): String {
        require(ready && serial >= 1)
        return "$prefix-${serial.toString().padStart(6, '0')}"
    }
}
data class PurchaseOrderNumberingRevision(
    val id: UUID, val operationId: UUID, val organizationId: UUID, val version: Long, val prefix: String,
    val previousSource: PurchaseOrderNumberingSource, val previousPrefix: String?, val reason: String,
    val changedBy: UUID, val changedAt: Instant, val changedByDisplayName: String = "",
)
interface PurchaseOrderNumberingSourcePort {
    fun current(organizationId: UUID): PurchaseOrderNumberingSnapshot
}
interface PurchaseOrderNumberingPersistencePort : PurchaseOrderNumberingSourcePort {
    fun lockOrganization(organizationId: UUID)
    fun revision(id: UUID): PurchaseOrderNumberingRevision?
    fun history(organizationId: UUID, limit: Int): List<PurchaseOrderNumberingRevision>
    fun append(revision: PurchaseOrderNumberingRevision)
}

fun validExistingPurchaseOrderPrefix(value: String): Boolean = Regex("^[A-Z][A-Z0-9-]{1,23}$").matches(value)
fun validNewPurchaseOrderPrefix(value: String): Boolean = value.length in 2..24 && Regex("^[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)*$").matches(value)

data class PurchaseOrderNumberingHistoryResponse(
    val version: Long, val prefix: String, val changedAt: String, val changedByDisplayName: String, val reason: String,
)
data class PurchaseOrderNumberingResponse(
    val organizationId: String, val source: String, val prefix: String?, val version: Long, val ready: Boolean,
    val exampleCode: String?, val changedAt: String?, val history: List<PurchaseOrderNumberingHistoryResponse> = emptyList(),
)
fun PurchaseOrderNumberingSnapshot.response(history: List<PurchaseOrderNumberingRevision> = emptyList()) = PurchaseOrderNumberingResponse(
    organizationId.toString(), source.name, prefix, version, ready, if (ready) code(1) else null, changedAt?.toString(),
    history.map { PurchaseOrderNumberingHistoryResponse(it.version, it.prefix, it.changedAt.toString(), it.changedByDisplayName, it.reason) },
)
data class ConfigurePurchaseOrderNumberingCommand(
    val operationId: UUID, val organizationId: UUID, val expectedVersion: Long,
    val expectedSource: PurchaseOrderNumberingSource, val expectedPrefix: String?,
    val prefix: String, val reason: String, val actor: UUID, val correlationId: String,
)
data class ConfigurePurchaseOrderNumberingResponse(
    val operationId: String, val organizationId: String, val version: Long, val prefix: String,
    val current: Boolean, val replayed: Boolean, val resultCode: String = "PURCHASE_ORDER_NUMBERING_CONFIGURED",
)

@Service
class PurchaseOrderNumberingService(
    private val authority: CompanySettingsAuthorityPort,
    private val persistence: PurchaseOrderNumberingPersistencePort,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
) {
    fun current(actor: UUID, organizationId: UUID): PurchaseOrderNumberingResponse {
        requireAuthority(actor, organizationId)
        return persistence.current(organizationId).response(persistence.history(organizationId, 50))
    }

    fun configure(input: ConfigurePurchaseOrderNumberingCommand): ConfigurePurchaseOrderNumberingResponse {
        requireAuthority(input.actor, input.organizationId)
        val prefix = input.prefix.trim().uppercase(Locale.ROOT)
        val reason = input.reason.trim()
        if (input.expectedVersion < 0 || !validNewPurchaseOrderPrefix(prefix) || reason.length !in 3..1000 ||
            reason.any { it.code < 32 && it !in "\n\r\t" } || reason.any { it.code == 127 } ||
            (input.expectedSource == PurchaseOrderNumberingSource.COMPANY) != (input.expectedVersion > 0) ||
            (input.expectedSource == PurchaseOrderNumberingSource.NOT_CONFIGURED) != (input.expectedPrefix == null)) {
            throw problem("COMPANY_NUMBERING_INVALID", "Enter a valid purchase order prefix and a reason for the change.", HttpStatus.BAD_REQUEST)
        }
        val id = UUID.nameUUIDFromBytes("company-po-numbering:${input.operationId}".toByteArray(Charsets.UTF_8))
        val fingerprint = IdempotencyKeyContract.fingerprint(buildJsonObject {
            put("organizationId", input.organizationId.toString()); put("expectedVersion", input.expectedVersion)
            put("expectedSource", input.expectedSource.name); put("expectedPrefix", input.expectedPrefix)
            put("prefix", prefix); put("reason", reason)
        }.toString())
        val execution = commands.execute(IdempotentCommandSpec(input.operationId, input.actor,
            "COMPANY_PO_NUMBERING_CONFIGURE", "PURCHASE_ORDER_NUMBERING", id, fingerprint, input.correlationId)) {
            persistence.lockOrganization(input.organizationId)
            requireAuthority(input.actor, input.organizationId)
            val before = persistence.current(input.organizationId)
            if (before.version != input.expectedVersion || before.source != input.expectedSource || before.prefix != input.expectedPrefix)
                throw problem("COMPANY_NUMBERING_CHANGED", "The numbering configuration changed. Refresh it before saving.", HttpStatus.CONFLICT)
            if (before.source == PurchaseOrderNumberingSource.COMPANY && before.prefix == prefix)
                throw problem("COMPANY_NUMBERING_UNCHANGED", "This prefix is already the current company configuration.", HttpStatus.CONFLICT)
            val now = clock.instant()
            persistence.append(PurchaseOrderNumberingRevision(id, input.operationId, input.organizationId, before.version + 1,
                prefix, before.source, before.prefix, reason, input.actor, now))
            audit.append(AuditEventRecord(actorUserId = input.actor, action = "PURCHASE_ORDER_NUMBERING_CONFIGURED",
                targetType = "PURCHASE_ORDER_NUMBERING", targetId = id, previousStateRef = before.source.name,
                newStateRef = "COMPANY", safeDiffJson = """{"fields":["prefix","version","source"]}""",
                occurredAt = now, correlationId = input.correlationId, reason = reason))
            IdempotentCommandOutcome("PURCHASE_ORDER_NUMBERING_CONFIGURED", """{"revisionId":"$id"}""")
        }
        val saved = persistence.revision(id)?.takeIf { it.organizationId == input.organizationId && it.operationId == input.operationId }
            ?: throw problem("COMPANY_NUMBERING_RECEIPT_UNAVAILABLE", "The saved numbering receipt is unavailable.", HttpStatus.CONFLICT)
        return ConfigurePurchaseOrderNumberingResponse(input.operationId.toString(), input.organizationId.toString(),
            saved.version, saved.prefix, persistence.current(input.organizationId).revisionId == saved.id, execution.replayed)
    }

    private fun requireAuthority(actor: UUID, organizationId: UUID) {
        if (!authority.canManageCompanySettings(actor, organizationId))
            throw problem("COMPANY_SETTINGS_DENIED", "Current company settings administration is required.", HttpStatus.FORBIDDEN)
    }
    private fun problem(code: String, message: String, status: HttpStatus) = ProductApiException(code, message, status)
}

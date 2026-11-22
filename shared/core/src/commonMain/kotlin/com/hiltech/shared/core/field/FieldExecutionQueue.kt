package com.hiltech.shared.core.field

import androidx.room3.withWriteTransaction
import com.hiltech.shared.core.local.HiltechLocalDatabase
import com.hiltech.shared.core.local.OfflineSchemaContract
import com.hiltech.shared.core.local.PendingCommandEntity
import com.hiltech.shared.core.local.PendingCommandStates
import com.hiltech.shared.core.work.TechnicianJobBundleDto
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class FieldExecutionIntent(val commandType: String) {
    START("StartWork"),
    BLOCK("BlockWork"),
    RESUME("ResumeWork"),
}

data class FieldQueuedIntent(
    val operationId: String,
    val commandType: String,
    val state: String,
    val targetId: String,
)

/** Durable *intent*, never a server-acknowledged field execution result. */
class FieldExecutionQueue(private val database: HiltechLocalDatabase) {
    suspend fun outstanding(identityId: String, workOrderId: String): FieldQueuedIntent? {
        val dao = database.pendingCommandDao()
        return (
            dao.outstandingFieldCommand(identityId, workOrderId).firstOrNull()
                ?: dao.outstandingCompletion(identityId, workOrderId).firstOrNull()
            )?.asFieldIntent()
    }

    suspend fun queue(
        identityId: String,
        installationId: String,
        bundle: TechnicianJobBundleDto,
        action: FieldExecutionIntent,
        operationId: String,
        nowEpochMs: Long,
        verifiedOnlineThisSession: Boolean,
        explanation: String? = null,
    ): FieldQueuedIntent {
        require(verifiedOnlineThisSession) {
            "A stale read-only bundle is not authority to capture a new field command."
        }
        require(identityId.isNotBlank() && installationId.isNotBlank())
        require(bundle.workOrderVersion >= 1 && bundle.assignment.version >= 1)
        require(bundle.binding != null)
        require(operationId.matches(UUID_REGEX))
        require(bundle.workOrderId.matches(UUID_REGEX))
        require(bundle.assignment.assignmentId.matches(UUID_REGEX))
        require(bundle.project.lifecycleState == "ACTIVE" || action == FieldExecutionIntent.BLOCK)
        val permitted = when (action) {
            FieldExecutionIntent.START ->
                bundle.lifecycleState == "ASSIGNED" &&
                    bundle.readinessState == "READY" &&
                    bundle.executionCapabilities.startOffline
            FieldExecutionIntent.BLOCK ->
                bundle.lifecycleState == "IN_PROGRESS" &&
                    bundle.executionCapabilities.blockOffline
            FieldExecutionIntent.RESUME ->
                bundle.lifecycleState == "BLOCKED" &&
                    bundle.readinessState == "READY" &&
                    bundle.executionCapabilities.resumeOffline
        }
        require(permitted) { "This field action is not available for the verified source state." }
        val reason = when (action) {
            FieldExecutionIntent.START -> {
                require(explanation == null)
                null
            }
            else -> explanation?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2000 }
                ?: throw IllegalArgumentException("A field blocker/resolution reason is required.")
        }
        val blockerId = if (action == FieldExecutionIntent.RESUME) {
            bundle.blockers.singleOrNull {
                it.blockerTypeCode == "FIELD_EXECUTION" && it.state == "OPEN"
            }?.blockerId ?: throw IllegalArgumentException("The authoritative open execution blocker is unavailable.")
        } else null

        val json = buildJsonObject {
            put("assignmentId", bundle.assignment.assignmentId)
            put("assignmentVersion", bundle.assignment.version)
            if (reason != null) put("reason", reason)
            if (blockerId != null) put("blockerId", blockerId)
        }.toString()

        return database.withWriteTransaction {
            require(
                database.engineeringTestIntentDao().outstandingForWorkOrder(
                    identityId,
                    installationId,
                    bundle.workOrderId,
                ) == 0,
            ) {
                "Sync or review the pending engineering test before changing field state."
            }
            require(database.pendingCommandDao().outstandingCompletion(identityId, bundle.workOrderId).isEmpty()) {
                "Resolve the pending completion submission before changing field state."
            }
            require(database.pendingCommandDao().outstandingFieldCommand(identityId, bundle.workOrderId).isEmpty()) {
                "Resolve or sync the prior field intent before creating a new one."
            }
            require(database.pendingCommandDao().get(operationId) == null) {
                "A local operationId cannot be reused."
            }
            val intent = PendingCommandEntity(
                operationId = operationId,
                actorUserIdentityId = identityId,
                deviceId = installationId,
                commandType = action.commandType,
                targetType = "WorkOrder",
                targetId = bundle.workOrderId,
                baseVersion = bundle.workOrderVersion,
                payloadVersion = OfflineSchemaContract.CURRENT_PENDING_PAYLOAD_VERSION,
                payloadJson = json,
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
                policyBindingRef = "binding:" + bundle.binding.bindingRevision,
                updatedAtEpochMs = nowEpochMs,
            )
            database.pendingCommandDao().insert(intent)
            intent.asFieldIntent()
        }
    }

    private fun PendingCommandEntity.asFieldIntent() =
        FieldQueuedIntent(operationId, commandType, state, targetId)

    private companion object {
        val UUID_REGEX =
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
    }
}

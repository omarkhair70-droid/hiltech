package com.hiltech.shared.core.field

import androidx.room3.withWriteTransaction
import com.hiltech.shared.core.local.HiltechLocalDatabase
import com.hiltech.shared.core.local.LocalEvidenceStates
import com.hiltech.shared.core.local.OfflineSchemaContract
import com.hiltech.shared.core.local.PendingCommandEntity
import com.hiltech.shared.core.local.PendingCommandStates
import com.hiltech.shared.core.work.TechnicianJobBundleDto
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class FieldQueuedCompletion(
    val operationId: String,
    val workOrderId: String,
    val state: String,
)

/**
 * Submission is a durable non-authoritative intent. It is never sent by the
 * normal Start/Block/Resume replay phase. The late completion phase runs only
 * after Evidence and engineering replay finish, and the server rechecks all
 * current business dependencies under a WorkOrder lock.
 *
 * We allow capture only for an online-verified fully satisfied server gate.
 * An offline Evidence/test draft cannot be counted as accepted Work evidence.
 */
class FieldCompletionQueue(
    private val database: HiltechLocalDatabase,
) {
    suspend fun queue(
        identityId: String,
        installationId: String,
        bundle: TechnicianJobBundleDto,
        operationId: String,
        nowEpochMs: Long,
        verifiedOnlineThisSession: Boolean,
    ): FieldQueuedCompletion {
        require(verifiedOnlineThisSession) {
            "A cached JobBundle cannot authorize completion submission."
        }
        require(identityId.isNotBlank() && installationId.isNotBlank())
        require(UUID.matches(operationId) && UUID.matches(bundle.workOrderId))
        require(UUID.matches(bundle.assignment.assignmentId))
        require(bundle.workOrderVersion >= 1 && bundle.assignment.version >= 1)
        val binding = requireNotNull(bundle.binding) {
            "The authoritative policy binding is unavailable."
        }
        require(binding.bindingRevision >= 1)
        require(bundle.project.lifecycleState == "ACTIVE")
        require(bundle.lifecycleState in setOf("IN_PROGRESS", "REWORK_REQUIRED"))
        require(bundle.executionCapabilities.submitOffline) {
            "This WorkOrder does not allow local completion intent capture."
        }
        require(bundle.completionGate.available && bundle.completionGate.satisfied) {
            "Complete the authoritative server prerequisites before submitting."
        }
        val payload = buildJsonObject {
            put("assignmentId", bundle.assignment.assignmentId)
            put("assignmentVersion", bundle.assignment.version)
            put("bindingRevision", binding.bindingRevision)
        }.toString()

        return database.withWriteTransaction {
            require(
                database.pendingCommandDao()
                    .outstandingFieldCommand(identityId, bundle.workOrderId)
                    .isEmpty(),
            ) {
                "Resolve or sync the pending field transition before completion."
            }
            require(
                database.pendingCommandDao()
                    .outstandingCompletion(identityId, bundle.workOrderId)
                    .isEmpty(),
            ) {
                "A completion submission for this WorkOrder is already pending."
            }
            require(
                database.pendingCommandDao()
                    .materialUsageForWorkOrder(
                        identityId,
                        installationId,
                        bundle.workOrderId,
                    )
                    .none {
                        it.state !in setOf(
                            PendingCommandStates.APPLIED,
                            PendingCommandStates.CANCELLED_LOCAL,
                        )
                    },
            ) {
                "Sync or resolve pending material-usage reports before completion."
            }
            require(
                database.engineeringTestIntentDao()
                    .outstandingForWorkOrder(
                        identityId,
                        installationId,
                        bundle.workOrderId,
                    ) == 0,
            ) {
                "Resolve all pending engineering-test intents first."
            }
            require(
                database.evidenceQueueDao()
                    .forWorkOrder(
                        identityId,
                        installationId,
                        bundle.workOrderId,
                    ).none {
                        it.localState !in setOf(
                            LocalEvidenceStates.READY,
                            LocalEvidenceStates.REJECTED,
                            LocalEvidenceStates.DISCARDED_EXPLICITLY,
                        )
                    },
            ) {
                "Finalize or review locally captured Evidence before submitting."
            }
            require(database.pendingCommandDao().get(operationId) == null) {
                "A local operationId cannot be reused."
            }

            database.pendingCommandDao().insert(
                PendingCommandEntity(
                    operationId = operationId,
                    actorUserIdentityId = identityId,
                    deviceId = installationId,
                    commandType = "SubmitWorkCompletion",
                    targetType = "WorkOrder",
                    targetId = bundle.workOrderId,
                    baseVersion = bundle.workOrderVersion,
                    payloadVersion =
                        OfflineSchemaContract.CURRENT_PENDING_PAYLOAD_VERSION,
                    payloadJson = payload,
                    clientOccurredAtEpochMs = nowEpochMs,
                    enqueuedAtEpochMs = nowEpochMs,
                    localSequence =
                        database.pendingCommandDao().maxLocalSequence() + 1,
                    state = PendingCommandStates.PENDING,
                    retryCount = 0,
                    nextRetryAtEpochMs = null,
                    lastAttemptAtEpochMs = null,
                    lastResultCode = null,
                    lastServerVersion = null,
                    lastCorrelationId = null,
                    contractVersion =
                        OfflineSchemaContract.CURRENT_COMMAND_CONTRACT_VERSION,
                    policyBindingRef =
                        "binding:" + binding.bindingRevision,
                    updatedAtEpochMs = nowEpochMs,
                ),
            )
            FieldQueuedCompletion(
                operationId = operationId,
                workOrderId = bundle.workOrderId,
                state = PendingCommandStates.PENDING,
            )
        }
    }

    private companion object {
        val UUID =
            Regex(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}",
            )
    }
}

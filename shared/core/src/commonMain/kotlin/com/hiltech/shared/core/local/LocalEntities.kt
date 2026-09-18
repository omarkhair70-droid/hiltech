package com.hiltech.shared.core.local

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(tableName = "local_project_context")
data class LocalProjectContextEntity(
    @PrimaryKey
    @ColumnInfo(name = "project_id")
    val projectId: String,
    @ColumnInfo(name = "project_code")
    val projectCode: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "lifecycle_state")
    val lifecycleState: String,
    @ColumnInfo(name = "safe_health_summary")
    val safeHealthSummary: String?,
    @ColumnInfo(name = "source_version")
    val sourceVersion: Long,
    @ColumnInfo(name = "fetched_at_epoch_ms")
    val fetchedAtEpochMs: Long,
)

@Entity(
    tableName = "local_site_context",
    indices = [
        Index(value = ["project_id"]),
    ],
)
data class LocalSiteContextEntity(
    @PrimaryKey
    @ColumnInfo(name = "site_id")
    val siteId: String,
    @ColumnInfo(name = "project_id")
    val projectId: String,
    @ColumnInfo(name = "site_code")
    val siteCode: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "address_text")
    val addressText: String?,
    val latitude: Double?,
    val longitude: Double?,
    @ColumnInfo(name = "access_instructions")
    val accessInstructions: String?,
    val timezone: String?,
    @ColumnInfo(name = "source_version")
    val sourceVersion: Long,
    @ColumnInfo(name = "fetched_at_epoch_ms")
    val fetchedAtEpochMs: Long,
)

@Entity(
    tableName = "local_work_order",
    indices = [
        Index(value = ["lifecycle_state", "planned_start_epoch_ms"]),
        Index(value = ["project_id"]),
        Index(value = ["site_id"]),
    ],
)
data class LocalWorkOrderEntity(
    @PrimaryKey
    @ColumnInfo(name = "work_order_id")
    val workOrderId: String,
    @ColumnInfo(name = "work_order_code")
    val workOrderCode: String,
    @ColumnInfo(name = "project_id")
    val projectId: String,
    @ColumnInfo(name = "site_id")
    val siteId: String?,
    @ColumnInfo(name = "area_id")
    val areaId: String?,
    @ColumnInfo(name = "work_type_code")
    val workTypeCode: String,
    @ColumnInfo(name = "lifecycle_state")
    val lifecycleState: String,
    @ColumnInfo(name = "readiness_state")
    val readinessState: String,
    @ColumnInfo(name = "work_order_version")
    val workOrderVersion: Long,
    @ColumnInfo(name = "policy_binding_ref")
    val policyBindingRef: String?,
    @ColumnInfo(name = "planned_start_epoch_ms")
    val plannedStartEpochMs: Long?,
    @ColumnInfo(name = "planned_end_epoch_ms")
    val plannedEndEpochMs: Long?,
    @ColumnInfo(name = "priority_code")
    val priorityCode: String?,
    @ColumnInfo(name = "instruction_revision")
    val instructionRevision: Int,
    @ColumnInfo(name = "fetched_at_epoch_ms")
    val fetchedAtEpochMs: Long,
    @ColumnInfo(name = "freshness_expires_at_epoch_ms")
    val freshnessExpiresAtEpochMs: Long?,
    @ColumnInfo(name = "bundle_revision")
    val bundleRevision: String,
    @ColumnInfo(name = "local_sync_state")
    val localSyncState: String,
    @ColumnInfo(name = "updated_at_epoch_ms")
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "local_policy_binding")
data class LocalPolicyBindingEntity(
    @PrimaryKey
    @ColumnInfo(name = "work_order_id")
    val workOrderId: String,
    @ColumnInfo(name = "work_type_definition_id")
    val workTypeDefinitionId: String,
    @ColumnInfo(name = "work_type_revision")
    val workTypeRevision: Int,
    @ColumnInfo(name = "assignment_policy_id")
    val assignmentPolicyId: String,
    @ColumnInfo(name = "assignment_policy_revision")
    val assignmentPolicyRevision: Int,
    @ColumnInfo(name = "readiness_policy_id")
    val readinessPolicyId: String,
    @ColumnInfo(name = "readiness_policy_revision")
    val readinessPolicyRevision: Int,
    @ColumnInfo(name = "evidence_policy_id")
    val evidencePolicyId: String,
    @ColumnInfo(name = "evidence_policy_revision")
    val evidencePolicyRevision: Int,
    @ColumnInfo(name = "review_policy_id")
    val reviewPolicyId: String,
    @ColumnInfo(name = "review_policy_revision")
    val reviewPolicyRevision: Int,
    @ColumnInfo(name = "tracking_policy_id")
    val trackingPolicyId: String?,
    @ColumnInfo(name = "tracking_policy_revision")
    val trackingPolicyRevision: Int?,
    @ColumnInfo(name = "checklist_template_id")
    val checklistTemplateId: String?,
    @ColumnInfo(name = "checklist_template_revision")
    val checklistTemplateRevision: Int?,
    @ColumnInfo(name = "instruction_template_id")
    val instructionTemplateId: String?,
    @ColumnInfo(name = "instruction_template_revision")
    val instructionTemplateRevision: Int?,
    @ColumnInfo(name = "binding_created_at_epoch_ms")
    val bindingCreatedAtEpochMs: Long,
)

@Entity(
    tableName = "local_requirement_instance",
    indices = [
        Index(value = ["work_order_id", "requirement_family", "local_or_authoritative_state"]),
    ],
)
data class LocalRequirementInstanceEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "work_order_id")
    val workOrderId: String,
    @ColumnInfo(name = "requirement_family")
    val requirementFamily: String,
    @ColumnInfo(name = "requirement_key")
    val requirementKey: String,
    val label: String,
    val required: Boolean,
    @ColumnInfo(name = "local_or_authoritative_state")
    val localOrAuthoritativeState: String,
    @ColumnInfo(name = "source_config_id")
    val sourceConfigId: String?,
    @ColumnInfo(name = "source_config_revision")
    val sourceConfigRevision: Int?,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int,
    @ColumnInfo(name = "updated_at_epoch_ms")
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "local_assignment",
    indices = [
        Index(value = ["work_order_id", "state"]),
    ],
)
data class LocalAssignmentEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "work_order_id")
    val workOrderId: String,
    @ColumnInfo(name = "target_type")
    val targetType: String,
    @ColumnInfo(name = "target_id")
    val targetId: String,
    @ColumnInfo(name = "display_label")
    val displayLabel: String,
    val lead: Boolean,
    val state: String,
    @ColumnInfo(name = "source_version")
    val sourceVersion: Long,
    @ColumnInfo(name = "updated_at_epoch_ms")
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "local_document_ref",
    indices = [
        Index(value = ["work_order_id"]),
    ],
)
data class LocalDocumentRefEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "work_order_id")
    val workOrderId: String,
    @ColumnInfo(name = "document_id")
    val documentId: String,
    @ColumnInfo(name = "document_type_code")
    val documentTypeCode: String,
    val title: String,
    @ColumnInfo(name = "revision_code")
    val revisionCode: String,
    @ColumnInfo(name = "local_file_ref")
    val localFileRef: String?,
    val downloaded: Boolean,
    @ColumnInfo(name = "source_version")
    val sourceVersion: Long,
    @ColumnInfo(name = "fetched_at_epoch_ms")
    val fetchedAtEpochMs: Long,
    @ColumnInfo(name = "freshness_expires_at_epoch_ms")
    val freshnessExpiresAtEpochMs: Long?,
)

@Entity(
    tableName = "local_asset_ref",
    indices = [
        Index(value = ["work_order_id"]),
    ],
)
data class LocalAssetRefEntity(
    @PrimaryKey
    @ColumnInfo(name = "asset_id")
    val assetId: String,
    @ColumnInfo(name = "work_order_id")
    val workOrderId: String?,
    @ColumnInfo(name = "asset_code")
    val assetCode: String,
    @ColumnInfo(name = "type_label")
    val typeLabel: String,
    @ColumnInfo(name = "lifecycle_state")
    val lifecycleState: String,
    @ColumnInfo(name = "condition_state")
    val conditionState: String,
    @ColumnInfo(name = "calibration_summary")
    val calibrationSummary: String?,
    @ColumnInfo(name = "custody_summary")
    val custodySummary: String?,
    @ColumnInfo(name = "tag_code")
    val tagCode: String?,
    @ColumnInfo(name = "source_version")
    val sourceVersion: Long,
    @ColumnInfo(name = "fetched_at_epoch_ms")
    val fetchedAtEpochMs: Long,
)

@Entity(
    tableName = "pending_command",
    indices = [
        Index(value = ["state", "next_retry_at_epoch_ms"]),
        Index(value = ["target_type", "target_id"]),
        Index(value = ["local_sequence"]),
    ],
)
data class PendingCommandEntity(
    @PrimaryKey
    @ColumnInfo(name = "operation_id")
    val operationId: String,
    @ColumnInfo(name = "actor_user_identity_id")
    val actorUserIdentityId: String,
    @ColumnInfo(name = "device_id")
    val deviceId: String,
    @ColumnInfo(name = "command_type")
    val commandType: String,
    @ColumnInfo(name = "target_type")
    val targetType: String,
    @ColumnInfo(name = "target_id")
    val targetId: String,
    @ColumnInfo(name = "base_version")
    val baseVersion: Long?,
    @ColumnInfo(name = "payload_version")
    val payloadVersion: Int,
    @ColumnInfo(name = "payload_json")
    val payloadJson: String,
    @ColumnInfo(name = "client_occurred_at_epoch_ms")
    val clientOccurredAtEpochMs: Long,
    @ColumnInfo(name = "enqueued_at_epoch_ms")
    val enqueuedAtEpochMs: Long,
    @ColumnInfo(name = "local_sequence")
    val localSequence: Long,
    val state: String,
    @ColumnInfo(name = "retry_count")
    val retryCount: Int,
    @ColumnInfo(name = "next_retry_at_epoch_ms")
    val nextRetryAtEpochMs: Long?,
    @ColumnInfo(name = "last_attempt_at_epoch_ms")
    val lastAttemptAtEpochMs: Long?,
    @ColumnInfo(name = "last_result_code")
    val lastResultCode: String?,
    @ColumnInfo(name = "last_server_version")
    val lastServerVersion: Long?,
    @ColumnInfo(name = "last_correlation_id")
    val lastCorrelationId: String?,
    @ColumnInfo(name = "contract_version")
    val contractVersion: Int,
    @ColumnInfo(name = "policy_binding_ref")
    val policyBindingRef: String?,
    @ColumnInfo(name = "updated_at_epoch_ms")
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "pending_command_dependency",
    primaryKeys = ["operation_id", "depends_on_operation_id"],
    indices = [
        Index(value = ["depends_on_operation_id"]),
    ],
)
data class PendingCommandDependencyEntity(
    @ColumnInfo(name = "operation_id")
    val operationId: String,
    @ColumnInfo(name = "depends_on_operation_id")
    val dependsOnOperationId: String,
)

@Entity(
    tableName = "local_evidence",
    indices = [
        Index(value = ["work_order_id", "local_state"]),
        Index(value = ["local_state"]),
    ],
)
data class LocalEvidenceEntity(
    @PrimaryKey
    @ColumnInfo(name = "evidence_id")
    val evidenceId: String,
    @ColumnInfo(name = "work_order_id")
    val workOrderId: String,
    @ColumnInfo(name = "target_type")
    val targetType: String,
    @ColumnInfo(name = "target_id")
    val targetId: String,
    @ColumnInfo(name = "requirement_key")
    val requirementKey: String?,
    @ColumnInfo(name = "evidence_policy_id")
    val evidencePolicyId: String?,
    @ColumnInfo(name = "evidence_policy_revision")
    val evidencePolicyRevision: Int?,
    @ColumnInfo(name = "evidence_type_code")
    val evidenceTypeCode: String,
    @ColumnInfo(name = "local_file_ref")
    val localFileRef: String,
    val sha256: String,
    @ColumnInfo(name = "content_type")
    val contentType: String,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,
    @ColumnInfo(name = "captured_at_epoch_ms")
    val capturedAtEpochMs: Long,
    @ColumnInfo(name = "captured_by_user_id")
    val capturedByUserId: String,
    @ColumnInfo(name = "source_device_id")
    val sourceDeviceId: String,
    @ColumnInfo(name = "local_state")
    val localState: String,
    @ColumnInfo(name = "upload_session_id")
    val uploadSessionId: String?,
    @ColumnInfo(name = "remote_object_ref")
    val remoteObjectRef: String?,
    @ColumnInfo(name = "finalized_server_evidence_id")
    val finalizedServerEvidenceId: String?,
    @ColumnInfo(name = "upload_attempt_count")
    val uploadAttemptCount: Int,
    @ColumnInfo(name = "last_upload_error_code")
    val lastUploadErrorCode: String?,
    @ColumnInfo(name = "updated_at_epoch_ms")
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "conflict_record",
    indices = [
        Index(value = ["operation_id"], unique = true),
        Index(value = ["target_type", "target_id"]),
        Index(value = ["resolved_at_epoch_ms"]),
    ],
)
data class ConflictRecordEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "operation_id")
    val operationId: String,
    @ColumnInfo(name = "command_type")
    val commandType: String,
    @ColumnInfo(name = "target_type")
    val targetType: String,
    @ColumnInfo(name = "target_id")
    val targetId: String,
    @ColumnInfo(name = "conflict_type")
    val conflictType: String,
    @ColumnInfo(name = "base_version")
    val baseVersion: Long?,
    @ColumnInfo(name = "current_server_version")
    val currentServerVersion: Long?,
    @ColumnInfo(name = "safe_server_state_json")
    val safeServerStateJson: String,
    @ColumnInfo(name = "allowed_recovery_actions_json")
    val allowedRecoveryActionsJson: String,
    @ColumnInfo(name = "detected_at_epoch_ms")
    val detectedAtEpochMs: Long,
    @ColumnInfo(name = "resolved_at_epoch_ms")
    val resolvedAtEpochMs: Long?,
    @ColumnInfo(name = "resolution_type")
    val resolutionType: String?,
    @ColumnInfo(name = "resolution_operation_id")
    val resolutionOperationId: String?,
)

@Entity(tableName = "sync_cursor")
data class SyncCursorEntity(
    @PrimaryKey
    @ColumnInfo(name = "scope_key")
    val scopeKey: String,
    val cursor: String,
    @ColumnInfo(name = "last_successful_pull_at_epoch_ms")
    val lastSuccessfulPullAtEpochMs: Long,
    @ColumnInfo(name = "server_as_of_epoch_ms")
    val serverAsOfEpochMs: Long?,
    @ColumnInfo(name = "updated_at_epoch_ms")
    val updatedAtEpochMs: Long,
)

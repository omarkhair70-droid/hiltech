package com.hiltech.shared.core.local

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface WorkBundleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProject(value: LocalProjectContextEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSite(value: LocalSiteContextEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorkOrder(value: LocalWorkOrderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPolicyBinding(value: LocalPolicyBindingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRequirements(values: List<LocalRequirementInstanceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAssignments(values: List<LocalAssignmentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDocuments(values: List<LocalDocumentRefEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAssets(values: List<LocalAssetRefEntity>)

    @Query("SELECT * FROM local_work_order WHERE work_order_id = :workOrderId LIMIT 1")
    suspend fun getWorkOrder(workOrderId: String): LocalWorkOrderEntity?

    @Query("SELECT * FROM local_project_context WHERE project_id = :projectId LIMIT 1")
    suspend fun getProject(projectId: String): LocalProjectContextEntity?

    @Query("SELECT * FROM local_site_context WHERE site_id = :siteId LIMIT 1")
    suspend fun getSite(siteId: String): LocalSiteContextEntity?

    @Query(
        """
        SELECT * FROM local_requirement_instance
        WHERE work_order_id = :workOrderId
        ORDER BY sort_order ASC, id ASC
        """,
    )
    suspend fun listRequirements(workOrderId: String): List<LocalRequirementInstanceEntity>
}

@Dao
interface PendingCommandDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(command: PendingCommandEntity)

    @Query("SELECT * FROM pending_command WHERE operation_id = :operationId LIMIT 1")
    suspend fun get(operationId: String): PendingCommandEntity?

    @Query(
        """
        SELECT * FROM pending_command
        WHERE state IN ('PENDING', 'RETRYABLE')
          AND (next_retry_at_epoch_ms IS NULL OR next_retry_at_epoch_ms <= :nowEpochMs)
        ORDER BY local_sequence ASC
        """,
    )
    suspend fun listSyncable(nowEpochMs: Long): List<PendingCommandEntity>

    @Query(
        """
        SELECT COALESCE(MAX(local_sequence), 0)
        FROM pending_command
        """,
    )
    suspend fun maxLocalSequence(): Long

    @Query(
        """
        UPDATE pending_command
        SET state = :state,
            retry_count = :retryCount,
            next_retry_at_epoch_ms = :nextRetryAtEpochMs,
            last_attempt_at_epoch_ms = :lastAttemptAtEpochMs,
            last_result_code = :lastResultCode,
            last_server_version = :lastServerVersion,
            last_correlation_id = :lastCorrelationId,
            updated_at_epoch_ms = :updatedAtEpochMs
        WHERE operation_id = :operationId
        """,
    )
    suspend fun updateSyncState(
        operationId: String,
        state: String,
        retryCount: Int,
        nextRetryAtEpochMs: Long?,
        lastAttemptAtEpochMs: Long?,
        lastResultCode: String?,
        lastServerVersion: Long?,
        lastCorrelationId: String?,
        updatedAtEpochMs: Long,
    )

    @Query(
        """
        SELECT COUNT(*)
        FROM pending_command
        WHERE state NOT IN ('APPLIED', 'CANCELLED_LOCAL')
        """,
    )
    suspend fun unresolvedCount(): Int
}

@Dao
interface PendingCommandDependencyDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(value: PendingCommandDependencyEntity)

    @Query(
        """
        SELECT depends_on_operation_id
        FROM pending_command_dependency
        WHERE operation_id = :operationId
        ORDER BY depends_on_operation_id ASC
        """,
    )
    suspend fun listDependencies(operationId: String): List<String>

    @Query(
        """
        SELECT operation_id
        FROM pending_command_dependency
        WHERE depends_on_operation_id = :operationId
        ORDER BY operation_id ASC
        """,
    )
    suspend fun listDirectDependents(operationId: String): List<String>

    @Query(
        """
        UPDATE pending_command
        SET state = 'BLOCKED_BY_CONFLICT',
            last_result_code = :resultCode,
            updated_at_epoch_ms = :updatedAtEpochMs
        WHERE operation_id IN (
            SELECT operation_id
            FROM pending_command_dependency
            WHERE depends_on_operation_id = :operationId
        )
          AND state IN ('PENDING', 'RETRYABLE')
        """,
    )
    suspend fun blockDirectDependents(
        operationId: String,
        resultCode: String,
        updatedAtEpochMs: Long,
    )
}

@Dao
interface EvidenceQueueDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(value: LocalEvidenceEntity)

    @Query("SELECT * FROM local_evidence WHERE evidence_id = :evidenceId LIMIT 1")
    suspend fun get(evidenceId: String): LocalEvidenceEntity?

    @Query(
        """
        SELECT * FROM local_evidence
        WHERE local_state IN (
            'LOCAL_READY',
            'RESERVATION_REQUIRED',
            'RESERVED',
            'UPLOADING',
            'UPLOADED_UNVERIFIED',
            'RETRYABLE'
        )
        ORDER BY captured_at_epoch_ms ASC
        """,
    )
    suspend fun listPendingUpload(): List<LocalEvidenceEntity>

    @Query(
        """
        UPDATE local_evidence
        SET local_state = :state,
            upload_session_id = :uploadSessionId,
            remote_object_ref = :remoteObjectRef,
            finalized_server_evidence_id = :finalizedServerEvidenceId,
            upload_attempt_count = :uploadAttemptCount,
            last_upload_error_code = :lastUploadErrorCode,
            updated_at_epoch_ms = :updatedAtEpochMs
        WHERE evidence_id = :evidenceId
        """,
    )
    suspend fun updateUploadState(
        evidenceId: String,
        state: String,
        uploadSessionId: String?,
        remoteObjectRef: String?,
        finalizedServerEvidenceId: String?,
        uploadAttemptCount: Int,
        lastUploadErrorCode: String?,
        updatedAtEpochMs: Long,
    )
}

@Dao
interface ConflictDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(value: ConflictRecordEntity)

    @Query("SELECT * FROM conflict_record WHERE operation_id = :operationId LIMIT 1")
    suspend fun getByOperationId(operationId: String): ConflictRecordEntity?

    @Query(
        """
        SELECT * FROM conflict_record
        WHERE resolved_at_epoch_ms IS NULL
        ORDER BY detected_at_epoch_ms ASC
        """,
    )
    suspend fun listUnresolved(): List<ConflictRecordEntity>
}

@Dao
interface SyncCursorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(value: SyncCursorEntity)

    @Query("SELECT * FROM sync_cursor WHERE scope_key = :scopeKey LIMIT 1")
    suspend fun get(scopeKey: String): SyncCursorEntity?
}

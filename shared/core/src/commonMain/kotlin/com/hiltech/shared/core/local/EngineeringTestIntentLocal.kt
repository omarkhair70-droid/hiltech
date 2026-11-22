package com.hiltech.shared.core.local

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query

object EngineeringTestIntentStates {
    const val PENDING = "PENDING"
    const val RETRYABLE = "RETRYABLE"
    const val APPLIED = "APPLIED"
    const val REVIEW_REQUIRED = "REVIEW_REQUIRED"
}

@Entity(
    tableName = "local_engineering_test_intent",
    indices = [
        Index(value = ["actor_user_identity_id", "device_id", "state"]),
        Index(value = ["work_order_id", "state"]),
        Index(value = ["next_retry_at_epoch_ms"]),
    ],
)
data class LocalEngineeringTestIntentEntity(
    @PrimaryKey
    @ColumnInfo(name = "operation_id")
    val operationId: String,
    @ColumnInfo(name = "actor_user_identity_id")
    val actorUserIdentityId: String,
    @ColumnInfo(name = "device_id")
    val deviceId: String,
    @ColumnInfo(name = "work_order_id")
    val workOrderId: String,
    @ColumnInfo(name = "assignment_id")
    val assignmentId: String,
    @ColumnInfo(name = "assignment_version")
    val assignmentVersion: Long,
    @ColumnInfo(name = "work_order_version")
    val workOrderVersion: Long,
    @ColumnInfo(name = "binding_revision")
    val bindingRevision: Int,
    @ColumnInfo(name = "evidence_policy_id")
    val evidencePolicyId: String,
    @ColumnInfo(name = "evidence_policy_revision")
    val evidencePolicyRevision: Int,
    @ColumnInfo(name = "requirement_key")
    val requirementKey: String,
    @ColumnInfo(name = "schema_template_id")
    val schemaTemplateId: String,
    @ColumnInfo(name = "schema_template_revision")
    val schemaTemplateRevision: Int,
    @ColumnInfo(name = "schema_version")
    val schemaVersion: Int,
    @ColumnInfo(name = "test_subject_ref")
    val testSubjectRef: String,
    @ColumnInfo(name = "values_json")
    val valuesJson: String,
    @ColumnInfo(name = "instrument_tag")
    val instrumentTag: String?,
    @ColumnInfo(name = "raw_evidence_source")
    val rawEvidenceSource: String?,
    @ColumnInfo(name = "raw_evidence_ref")
    val rawEvidenceRef: String?,
    @ColumnInfo(name = "device_reported_status")
    val deviceReportedStatus: String?,
    @ColumnInfo(name = "captured_at_epoch_ms")
    val capturedAtEpochMs: Long,
    @ColumnInfo(name = "client_occurred_at_epoch_ms")
    val clientOccurredAtEpochMs: Long,
    @ColumnInfo(name = "supersedes_result_id")
    val supersedesResultId: String?,
    val state: String,
    @ColumnInfo(name = "retry_count")
    val retryCount: Int,
    @ColumnInfo(name = "next_retry_at_epoch_ms")
    val nextRetryAtEpochMs: Long?,
    @ColumnInfo(name = "last_error_code")
    val lastErrorCode: String?,
    @ColumnInfo(name = "last_correlation_id")
    val lastCorrelationId: String?,
    @ColumnInfo(name = "server_result_id")
    val serverResultId: String?,
    @ColumnInfo(name = "evaluated_status")
    val evaluatedStatus: String?,
    @ColumnInfo(name = "updated_at_epoch_ms")
    val updatedAtEpochMs: Long,
)

@Dao
interface EngineeringTestIntentDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(value: LocalEngineeringTestIntentEntity)

    @Query("SELECT * FROM local_engineering_test_intent WHERE operation_id = :operationId LIMIT 1")
    suspend fun get(operationId: String): LocalEngineeringTestIntentEntity?

    @Query(
        """
        SELECT * FROM local_engineering_test_intent
        WHERE actor_user_identity_id = :actorId
          AND device_id = :installationId
          AND state IN ('PENDING','RETRYABLE')
          AND (next_retry_at_epoch_ms IS NULL OR next_retry_at_epoch_ms <= :nowEpochMs)
        ORDER BY captured_at_epoch_ms ASC, operation_id ASC
        """,
    )
    suspend fun syncable(
        actorId: String,
        installationId: String,
        nowEpochMs: Long,
    ): List<LocalEngineeringTestIntentEntity>

    @Query(
        """
        SELECT COUNT(*) FROM local_engineering_test_intent
        WHERE actor_user_identity_id = :actorId
          AND device_id = :installationId
          AND work_order_id = :workOrderId
          AND state IN ('PENDING','RETRYABLE')
        """,
    )
    suspend fun outstandingForWorkOrder(
        actorId: String,
        installationId: String,
        workOrderId: String,
    ): Int

    @Query(
        """
        SELECT COUNT(*) FROM local_engineering_test_intent
        WHERE actor_user_identity_id = :actorId
          AND device_id = :installationId
          AND work_order_id = :workOrderId
          AND requirement_key = :requirementKey
          AND test_subject_ref = :subjectRef
          AND state IN ('PENDING','RETRYABLE')
        """,
    )
    suspend fun outstandingForSubject(
        actorId: String,
        installationId: String,
        workOrderId: String,
        requirementKey: String,
        subjectRef: String,
    ): Int

    @Query(
        """
        UPDATE local_engineering_test_intent
        SET state = :state,
            retry_count = :retryCount,
            next_retry_at_epoch_ms = :nextRetryAtEpochMs,
            last_error_code = :lastErrorCode,
            last_correlation_id = :lastCorrelationId,
            server_result_id = :serverResultId,
            evaluated_status = :evaluatedStatus,
            updated_at_epoch_ms = :updatedAtEpochMs
        WHERE operation_id = :operationId
          AND actor_user_identity_id = :actorId
          AND device_id = :installationId
        """,
    )
    suspend fun updateState(
        operationId: String,
        actorId: String,
        installationId: String,
        state: String,
        retryCount: Int,
        nextRetryAtEpochMs: Long?,
        lastErrorCode: String?,
        lastCorrelationId: String?,
        serverResultId: String?,
        evaluatedStatus: String?,
        updatedAtEpochMs: Long,
    ): Int
}

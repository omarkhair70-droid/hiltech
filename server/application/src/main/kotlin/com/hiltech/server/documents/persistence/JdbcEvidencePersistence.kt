package com.hiltech.server.documents.persistence

import com.hiltech.server.documents.EvidenceFinalizeRecord
import com.hiltech.server.documents.EvidencePersistencePort
import com.hiltech.server.documents.EvidenceReservationInsert
import com.hiltech.server.documents.EvidenceReservationRecord
import com.hiltech.server.documents.WorkOrderEvidencePolicySnapshot
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Array as SqlArray
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Component
class JdbcEvidencePersistence(
    private val jdbc: JdbcTemplate,
) : EvidencePersistencePort {
    override fun loadWorkOrderPolicy(
        workOrderId: UUID,
        requirementKey: String,
    ): WorkOrderEvidencePolicySnapshot? =
        jdbc.query(
            """
            SELECT
                wo.id AS work_order_id,
                p.organization_id,
                wo.version AS work_order_version,
                wo.lifecycle_state,
                wir.instruction_revision,
                wpb.evidence_policy_id,
                wpb.evidence_policy_revision,
                epr.requirement_key,
                epr.evidence_type_code,
                epr.allowed_content_types,
                ep.system_max_size_bytes,
                epr.classification_code,
                epr.client_visibility_mode,
                epr.security_scan_class
            FROM work_order wo
            JOIN project p
              ON p.id = wo.project_id
            JOIN work_policy_binding wpb
              ON wpb.id = wo.current_policy_binding_id
             AND wpb.work_order_id = wo.id
            JOIN config_revision cr
              ON cr.id = wpb.evidence_policy_id
             AND cr.family = 'evidence-policies'
             AND cr.revision_number =
                 wpb.evidence_policy_revision
            JOIN evidence_policy ep
              ON ep.config_revision_id =
                 wpb.evidence_policy_id
            JOIN evidence_policy_requirement epr
              ON epr.config_revision_id =
                 wpb.evidence_policy_id
             AND epr.requirement_key = ?
            LEFT JOIN work_instruction_revision wir
              ON wir.id =
                 wo.current_instruction_revision_id
            WHERE wo.id = ?
            """.trimIndent(),
            { rs, _ ->
                WorkOrderEvidencePolicySnapshot(
                    workOrderId =
                        rs.getObject(
                            "work_order_id",
                            UUID::class.java,
                        ),
                    organizationId =
                        rs.getObject(
                            "organization_id",
                            UUID::class.java,
                        ),
                    workOrderVersion =
                        rs.getLong(
                            "work_order_version",
                        ),
                    lifecycleState =
                        rs.getString(
                            "lifecycle_state",
                        ),
                    instructionRevision =
                        (rs.getObject(
                            "instruction_revision",
                        ) as? Number)
                            ?.toLong(),
                    evidencePolicyId =
                        rs.getObject(
                            "evidence_policy_id",
                            UUID::class.java,
                        ),
                    evidencePolicyRevision =
                        rs.getInt(
                            "evidence_policy_revision",
                        ),
                    requirementKey =
                        rs.getString(
                            "requirement_key",
                        ),
                    evidenceTypeCode =
                        rs.getString(
                            "evidence_type_code",
                        ),
                    allowedContentTypes =
                        rs.getArray(
                            "allowed_content_types",
                        ).toStringSet(),
                    systemMaxSizeBytes =
                        rs.getLong(
                            "system_max_size_bytes",
                        ),
                    classificationCode =
                        rs.getString(
                            "classification_code",
                        ),
                    clientVisibilityMode =
                        rs.getString(
                            "client_visibility_mode",
                        ),
                    securityScanClass =
                        rs.getString(
                            "security_scan_class",
                        ),
                )
            },
            requirementKey,
            workOrderId,
        ).singleOrNull()

    override fun isValidSupersededEvidence(
        supersedesEvidenceId: UUID,
        organizationId: UUID,
        workOrderId: UUID,
    ): Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM evidence
                WHERE id = ?
                  AND organization_id = ?
                  AND work_order_id = ?
                  AND target_type = 'WORK_ORDER'
                  AND target_id = ?
            )
            """.trimIndent(),
            Boolean::class.java,
            supersedesEvidenceId,
            organizationId,
            workOrderId,
            workOrderId,
        ) == true

    override fun insertReservation(
        value: EvidenceReservationInsert,
    ) {
        jdbc.update(
            """
            INSERT INTO evidence (
                id,
                organization_id,
                target_type,
                target_id,
                work_order_id,
                evidence_requirement_key,
                evidence_policy_id,
                evidence_policy_revision,
                evidence_type_code,
                content_type,
                original_file_name,
                size_bytes,
                sha256,
                captured_at,
                client_occurred_at,
                captured_by_user_id,
                source_device_id,
                instruction_revision,
                work_order_version_at_capture,
                storage_state,
                object_key_ref,
                finalized_at,
                classification_code,
                client_visibility_mode,
                supersedes_evidence_id,
                created_at,
                version
            )
            VALUES (
                ?, ?,
                'WORK_ORDER',
                ?, ?,
                ?, ?, ?, ?,
                ?, ?, ?, ?,
                ?, ?, ?,
                NULL,
                ?, ?,
                'RESERVED',
                ?,
                NULL,
                ?, ?,
                ?,
                ?,
                1
            )
            """.trimIndent(),
            value.evidenceId,
            value.policy.organizationId,
            value.policy.workOrderId,
            value.policy.workOrderId,
            value.policy.requirementKey,
            value.policy.evidencePolicyId,
            value.policy.evidencePolicyRevision,
            value.policy.evidenceTypeCode,
            value.contentType,
            value.originalFileName,
            value.sizeBytes,
            value.sha256,
            value.capturedAt.atOffset(
                ZoneOffset.UTC,
            ),
            value.clientOccurredAt
                ?.atOffset(ZoneOffset.UTC),
            value.capturedByUserId,
            value.policy.instructionRevision,
            value.policy.workOrderVersion,
            value.objectKey,
            value.policy.classificationCode,
            value.policy.clientVisibilityMode,
            value.supersedesEvidenceId,
            value.createdAt.atOffset(
                ZoneOffset.UTC,
            ),
        )

        jdbc.update(
            """
            INSERT INTO evidence_upload_session (
                id,
                evidence_id,
                expected_sha256,
                expected_size_bytes,
                expires_at,
                state,
                created_at,
                finalized_at,
                operation_id,
                version
            )
            VALUES (
                ?, ?, ?, ?, ?,
                'RESERVED',
                ?,
                NULL,
                ?,
                1
            )
            """.trimIndent(),
            value.uploadSessionId,
            value.evidenceId,
            value.sha256,
            value.sizeBytes,
            value.uploadExpiresAt.atOffset(
                ZoneOffset.UTC,
            ),
            value.createdAt.atOffset(
                ZoneOffset.UTC,
            ),
            value.operationId,
        )
    }

    override fun findReservationByOperationId(
        operationId: UUID,
    ): EvidenceReservationRecord? =
        jdbc.query(
            reservationSelect(
                "us.operation_id = ?",
            ),
            reservationMapper,
            operationId,
        ).singleOrNull()

    override fun loadEvidenceRecord(
        evidenceId: UUID,
    ): EvidenceFinalizeRecord? =
        jdbc.query(
            """
            SELECT
                e.id AS evidence_id,
                us.id AS upload_session_id,
                e.organization_id,
                e.work_order_id,
                wo.lifecycle_state
                    AS work_order_lifecycle_state,
                e.captured_by_user_id,
                e.evidence_requirement_key,
                e.evidence_policy_id,
                e.evidence_policy_revision,
                e.evidence_type_code,
                e.content_type,
                us.expected_size_bytes,
                us.expected_sha256,
                e.storage_state,
                us.state AS upload_state,
                e.object_key_ref,
                us.expires_at,
                e.classification_code,
                e.client_visibility_mode,
                epr.security_scan_class,
                e.version AS evidence_version,
                us.version AS upload_session_version
            FROM evidence e
            JOIN evidence_upload_session us
              ON us.evidence_id = e.id
            JOIN work_order wo
              ON wo.id = e.work_order_id
            JOIN evidence_policy_requirement epr
              ON epr.config_revision_id =
                 e.evidence_policy_id
             AND epr.requirement_key =
                 e.evidence_requirement_key
            WHERE e.id = ?
              AND e.target_type = 'WORK_ORDER'
              AND e.target_id = e.work_order_id
            ORDER BY us.created_at DESC, us.id DESC
            LIMIT 1
            """.trimIndent(),
            finalizeRecordMapper,
            evidenceId,
        ).singleOrNull()

    override fun loadFinalizeRecord(
        evidenceId: UUID,
        uploadSessionId: UUID,
    ): EvidenceFinalizeRecord? =
        jdbc.query(
            """
            SELECT
                e.id AS evidence_id,
                us.id AS upload_session_id,
                e.organization_id,
                e.work_order_id,
                wo.lifecycle_state
                    AS work_order_lifecycle_state,
                e.captured_by_user_id,
                e.evidence_requirement_key,
                e.evidence_policy_id,
                e.evidence_policy_revision,
                e.evidence_type_code,
                e.content_type,
                us.expected_size_bytes,
                us.expected_sha256,
                e.storage_state,
                us.state AS upload_state,
                e.object_key_ref,
                us.expires_at,
                e.classification_code,
                e.client_visibility_mode,
                epr.security_scan_class,
                e.version AS evidence_version,
                us.version AS upload_session_version
            FROM evidence e
            JOIN evidence_upload_session us
              ON us.evidence_id = e.id
            JOIN work_order wo
              ON wo.id = e.work_order_id
            JOIN evidence_policy_requirement epr
              ON epr.config_revision_id =
                 e.evidence_policy_id
             AND epr.requirement_key =
                 e.evidence_requirement_key
            WHERE e.id = ?
              AND us.id = ?
              AND e.target_type = 'WORK_ORDER'
              AND e.target_id = e.work_order_id
            """.trimIndent(),
            finalizeRecordMapper,
            evidenceId,
            uploadSessionId,
        ).singleOrNull()

    override fun markFinalized(
        evidenceId: UUID,
        uploadSessionId: UUID,
        expectedEvidenceVersion: Long,
        expectedUploadSessionVersion: Long,
        state: String,
        finalizedAt: Instant,
    ): Boolean {
        require(
            state in setOf(
                "READY",
                "QUARANTINED",
                "REJECTED",
            ),
        )

        val counts =
            jdbc.queryForObject(
                """
                WITH updated_evidence AS (
                    UPDATE evidence
                    SET storage_state = ?,
                        finalized_at = ?,
                        version = version + 1
                    WHERE id = ?
                      AND version = ?
                      AND storage_state IN (
                          'RESERVED',
                          'UPLOADED_UNVERIFIED'
                      )
                    RETURNING id
                ),
                updated_session AS (
                    UPDATE evidence_upload_session
                    SET state = ?,
                        finalized_at = ?,
                        version = version + 1
                    WHERE id = ?
                      AND evidence_id = ?
                      AND version = ?
                      AND state IN (
                          'RESERVED',
                          'UPLOADED_UNVERIFIED'
                      )
                      AND EXISTS (
                          SELECT 1
                          FROM updated_evidence
                      )
                    RETURNING id
                )
                SELECT
                    (SELECT count(*)
                     FROM updated_evidence)
                        AS evidence_count,
                    (SELECT count(*)
                     FROM updated_session)
                        AS session_count
                """.trimIndent(),
                { rs, _ ->
                    rs.getInt(
                        "evidence_count",
                    ) to
                        rs.getInt(
                            "session_count",
                        )
                },
                state,
                finalizedAt.atOffset(
                    ZoneOffset.UTC,
                ),
                evidenceId,
                expectedEvidenceVersion,
                state,
                finalizedAt.atOffset(
                    ZoneOffset.UTC,
                ),
                uploadSessionId,
                evidenceId,
                expectedUploadSessionVersion,
            )

        return counts?.first == 1 &&
            counts.second == 1
    }

    private val finalizeRecordMapper =
        { rs: java.sql.ResultSet, _: Int ->
            EvidenceFinalizeRecord(
                evidenceId =
                    rs.getObject(
                        "evidence_id",
                        UUID::class.java,
                    ),
                uploadSessionId =
                    rs.getObject(
                        "upload_session_id",
                        UUID::class.java,
                    ),
                organizationId =
                    rs.getObject(
                        "organization_id",
                        UUID::class.java,
                    ),
                workOrderId =
                    requireNotNull(
                        rs.getObject(
                            "work_order_id",
                            UUID::class.java,
                        ),
                    ),
                workOrderLifecycleState =
                    rs.getString(
                        "work_order_lifecycle_state",
                    ),
                capturedByUserId =
                    rs.getObject(
                        "captured_by_user_id",
                        UUID::class.java,
                    ),
                evidenceRequirementKey =
                    requireNotNull(
                        rs.getString(
                            "evidence_requirement_key",
                        ),
                    ),
                evidencePolicyId =
                    requireNotNull(
                        rs.getObject(
                            "evidence_policy_id",
                            UUID::class.java,
                        ),
                    ),
                evidencePolicyRevision =
                    rs.getInt(
                        "evidence_policy_revision",
                    ),
                evidenceTypeCode =
                    rs.getString(
                        "evidence_type_code",
                    ),
                contentType =
                    rs.getString(
                        "content_type",
                    ),
                expectedSizeBytes =
                    rs.getLong(
                        "expected_size_bytes",
                    ),
                expectedSha256 =
                    rs.getString(
                        "expected_sha256",
                    ),
                storageState =
                    rs.getString(
                        "storage_state",
                    ),
                uploadState =
                    rs.getString(
                        "upload_state",
                    ),
                objectKey =
                    requireNotNull(
                        rs.getString(
                            "object_key_ref",
                        ),
                    ),
                uploadExpiresAt =
                    rs.getObject(
                        "expires_at",
                        OffsetDateTime::class.java,
                    ).toInstant(),
                classificationCode =
                    rs.getString(
                        "classification_code",
                    ),
                clientVisibilityMode =
                    rs.getString(
                        "client_visibility_mode",
                    ),
                securityScanClass =
                    rs.getString(
                        "security_scan_class",
                    ),
                evidenceVersion =
                    rs.getLong(
                        "evidence_version",
                    ),
                uploadSessionVersion =
                    rs.getLong(
                        "upload_session_version",
                    ),
            )
        }

    private val reservationMapper =
        { rs: java.sql.ResultSet, _: Int ->
            EvidenceReservationRecord(
                evidenceId =
                    rs.getObject(
                        "evidence_id",
                        UUID::class.java,
                    ),
                uploadSessionId =
                    rs.getObject(
                        "upload_session_id",
                        UUID::class.java,
                    ),
                operationId =
                    rs.getObject(
                        "operation_id",
                        UUID::class.java,
                    ),
                organizationId =
                    rs.getObject(
                        "organization_id",
                        UUID::class.java,
                    ),
                workOrderId =
                    requireNotNull(
                        rs.getObject(
                            "work_order_id",
                            UUID::class.java,
                        ),
                    ),
                evidenceRequirementKey =
                    requireNotNull(
                        rs.getString(
                            "evidence_requirement_key",
                        ),
                    ),
                evidencePolicyId =
                    requireNotNull(
                        rs.getObject(
                            "evidence_policy_id",
                            UUID::class.java,
                        ),
                    ),
                evidencePolicyRevision =
                    rs.getInt(
                        "evidence_policy_revision",
                    ),
                evidenceTypeCode =
                    rs.getString(
                        "evidence_type_code",
                    ),
                contentType =
                    rs.getString(
                        "content_type",
                    ),
                sizeBytes =
                    rs.getLong(
                        "size_bytes",
                    ),
                sha256 =
                    rs.getString("sha256"),
                capturedByUserId =
                    rs.getObject(
                        "captured_by_user_id",
                        UUID::class.java,
                    ),
                storageState =
                    rs.getString(
                        "storage_state",
                    ),
                objectKey =
                    requireNotNull(
                        rs.getString(
                            "object_key_ref",
                        ),
                    ),
                classificationCode =
                    rs.getString(
                        "classification_code",
                    ),
                clientVisibilityMode =
                    rs.getString(
                        "client_visibility_mode",
                    ),
                uploadState =
                    rs.getString(
                        "upload_state",
                    ),
                uploadExpiresAt =
                    rs.getObject(
                        "expires_at",
                        OffsetDateTime::class.java,
                    ).toInstant(),
                evidenceVersion =
                    rs.getLong(
                        "evidence_version",
                    ),
                uploadSessionVersion =
                    rs.getLong(
                        "upload_session_version",
                    ),
            )
        }

    private fun reservationSelect(
        predicate: String,
    ): String =
        """
        SELECT
            e.id AS evidence_id,
            us.id AS upload_session_id,
            us.operation_id,
            e.organization_id,
            e.work_order_id,
            e.evidence_requirement_key,
            e.evidence_policy_id,
            e.evidence_policy_revision,
            e.evidence_type_code,
            e.content_type,
            e.size_bytes,
            e.sha256,
            e.captured_by_user_id,
            e.storage_state,
            e.object_key_ref,
            e.classification_code,
            e.client_visibility_mode,
            us.state AS upload_state,
            us.expires_at,
            e.version AS evidence_version,
            us.version AS upload_session_version
        FROM evidence_upload_session us
        JOIN evidence e
          ON e.id = us.evidence_id
        WHERE $predicate
        """.trimIndent()

    private fun SqlArray?.toStringSet(): Set<String> =
        when (val raw = this?.array) {
            is Array<*> ->
                raw
                    .filterIsInstance<String>()
                    .map {
                        it.trim().lowercase()
                    }
                    .filter { it.isNotEmpty() }
                    .toSet()

            else ->
                emptySet()
        }
}

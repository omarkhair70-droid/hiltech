package com.hiltech.server.documents.persistence

import com.hiltech.server.documents.EvidenceFinalizeRecord
import com.hiltech.server.documents.EvidencePersistencePort
import com.hiltech.server.documents.EvidenceReservationInsert
import com.hiltech.server.documents.EvidenceReservationRecord
import com.hiltech.server.documents.EmployeeDocumentEvidenceReservationInsert
import com.hiltech.server.documents.SupplierQuoteDocumentEvidenceReservationInsert
import com.hiltech.server.documents.SupplierInvoiceDocumentEvidenceReservationInsert
import com.hiltech.server.documents.PoReceivingDeliveryNoteEvidenceReservationInsert
import com.hiltech.server.documents.FinanceExpenseReceiptEvidenceReservationInsert
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
        // Serialize competing reservations for the same WorkOrder inside the
        // idempotent command transaction. A client-side maxCount check is not
        // authoritative when two technicians reserve concurrently.
        val lockedWorkOrder =
            jdbc.query(
                "SELECT id FROM work_order WHERE id = ? FOR UPDATE",
                { rs, _ -> rs.getObject("id", UUID::class.java) },
                value.policy.workOrderId,
            ).singleOrNull()
        if (lockedWorkOrder == null) {
            throw org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT,
                "WorkOrder is no longer available for Evidence reservation.",
            )
        }

        val maxCount =
            jdbc.query(
                """
                SELECT epr.max_count
                FROM work_order wo
                JOIN work_policy_binding wpb
                  ON wpb.id = wo.current_policy_binding_id
                 AND wpb.work_order_id = wo.id
                JOIN evidence_policy_requirement epr
                  ON epr.config_revision_id = wpb.evidence_policy_id
                 AND epr.requirement_key = ?
                WHERE wo.id = ?
                  AND wpb.evidence_policy_id = ?
                  AND wpb.evidence_policy_revision = ?
                """.trimIndent(),
                { rs, _ -> (rs.getObject("max_count") as? Number)?.toInt() },
                value.policy.requirementKey,
                value.policy.workOrderId,
                value.policy.evidencePolicyId,
                value.policy.evidencePolicyRevision,
            ).singleOrNull()

        // A missing or changed binding must not become an unlimited quota.
        val stillBound =
            jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1 FROM work_order wo
                    JOIN work_policy_binding wpb
                      ON wpb.id = wo.current_policy_binding_id
                     AND wpb.work_order_id = wo.id
                    JOIN evidence_policy_requirement epr
                      ON epr.config_revision_id = wpb.evidence_policy_id
                     AND epr.requirement_key = ?
                    WHERE wo.id = ?
                      AND wpb.evidence_policy_id = ?
                      AND wpb.evidence_policy_revision = ?
                )
                """.trimIndent(),
                Boolean::class.java,
                value.policy.requirementKey,
                value.policy.workOrderId,
                value.policy.evidencePolicyId,
                value.policy.evidencePolicyRevision,
            ) == true
        if (!stillBound) {
            throw org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT,
                "The bound Evidence policy has changed. Refresh the JobBundle.",
            )
        }
        if (maxCount != null) {
            val used =
                jdbc.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM evidence e
                    WHERE e.work_order_id = ?
                      AND e.evidence_requirement_key = ?
                      AND e.target_type = 'WORK_ORDER'
                      AND (
                          e.storage_state IN (
                              'READY', 'QUARANTINED', 'UPLOADED_UNVERIFIED'
                          )
                          OR (
                              e.storage_state = 'RESERVED'
                              AND EXISTS (
                                  SELECT 1 FROM evidence_upload_session us
                                  WHERE us.evidence_id = e.id
                                    AND us.expires_at > ?
                              )
                          )
                      )
                    """.trimIndent(),
                    Long::class.java,
                    value.policy.workOrderId,
                    value.policy.requirementKey,
                    value.createdAt.atOffset(ZoneOffset.UTC),
                ) ?: 0L
            if (used >= maxCount) {
                throw com.hiltech.server.platform.ProductApiException(
                    code = "EVIDENCE_MAX_COUNT_EXCEEDED",
                    message = "The bound Evidence requirement has reached its maximum count.",
                    status = org.springframework.http.HttpStatus.CONFLICT,
                )
            }
        }

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
                security_scan_class,
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
                ?, ?, ?,
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
            value.policy.securityScanClass,
            value.supersedesEvidenceId,
            value.createdAt.atOffset(
                ZoneOffset.UTC,
            ),
        )

        insertUploadSession(
            evidenceId = value.evidenceId,
            uploadSessionId = value.uploadSessionId,
            operationId = value.operationId,
            sha256 = value.sha256,
            sizeBytes = value.sizeBytes,
            uploadExpiresAt = value.uploadExpiresAt,
            createdAt = value.createdAt,
        )
    }

    override fun insertEmployeeDocumentReservation(
        value: EmployeeDocumentEvidenceReservationInsert,
    ) = insertRestrictedEvidenceReservation(
        evidenceId = value.evidenceId,
        uploadSessionId = value.uploadSessionId,
        operationId = value.operationId,
        objectKey = value.objectKey,
        organizationId = value.organizationId,
        targetType = "EMPLOYEE_DOCUMENT",
        targetId = value.employeeDocumentId,
        requirementKey = "EMPLOYEE_DOCUMENT_BINARY",
        evidenceTypeCode = value.evidenceTypeCode,
        contentType = value.contentType,
        originalFileName = value.originalFileName,
        sizeBytes = value.sizeBytes,
        sha256 = value.sha256,
        capturedAt = value.capturedAt,
        clientOccurredAt = value.clientOccurredAt,
        capturedByUserId = value.capturedByUserId,
        securityScanClass = value.securityScanClass,
        uploadExpiresAt = value.uploadExpiresAt,
        createdAt = value.createdAt,
    )

    override fun insertSupplierQuoteDocumentReservation(
        value: SupplierQuoteDocumentEvidenceReservationInsert,
    ) = insertRestrictedEvidenceReservation(
        evidenceId = value.evidenceId,
        uploadSessionId = value.uploadSessionId,
        operationId = value.operationId,
        objectKey = value.objectKey,
        organizationId = value.organizationId,
        targetType = "SUPPLIER_QUOTE_DOCUMENT",
        targetId = value.documentSlotId,
        requirementKey = "SUPPLIER_QUOTE_BINARY",
        evidenceTypeCode = "SUPPLIER_QUOTE",
        contentType = value.contentType,
        originalFileName = value.originalFileName,
        sizeBytes = value.sizeBytes,
        sha256 = value.sha256,
        capturedAt = value.capturedAt,
        clientOccurredAt = value.clientOccurredAt,
        capturedByUserId = value.capturedByUserId,
        securityScanClass = value.securityScanClass,
        uploadExpiresAt = value.uploadExpiresAt,
        createdAt = value.createdAt,
    )

    override fun insertServiceQuoteDocumentReservation(
        value: SupplierQuoteDocumentEvidenceReservationInsert,
    ) = insertRestrictedEvidenceReservation(
        evidenceId = value.evidenceId,
        uploadSessionId = value.uploadSessionId,
        operationId = value.operationId,
        objectKey = value.objectKey,
        organizationId = value.organizationId,
        targetType = "SERVICE_QUOTE_DOCUMENT",
        targetId = value.documentSlotId,
        requirementKey = "SERVICE_QUOTE_BINARY",
        evidenceTypeCode = "SERVICE_QUOTE",
        contentType = value.contentType,
        originalFileName = value.originalFileName,
        sizeBytes = value.sizeBytes,
        sha256 = value.sha256,
        capturedAt = value.capturedAt,
        clientOccurredAt = value.clientOccurredAt,
        capturedByUserId = value.capturedByUserId,
        securityScanClass = value.securityScanClass,
        uploadExpiresAt = value.uploadExpiresAt,
        createdAt = value.createdAt,
    )

    override fun insertServicePurchaseDocumentReservation(
        value: SupplierQuoteDocumentEvidenceReservationInsert,
        documentKind: String,
    ) {
        val expected = when (documentKind) {
            "DELIVERY" -> "SERVICE_DELIVERY"
            "INVOICE" -> "SERVICE_INVOICE"
            else -> throw IllegalArgumentException("Unsupported service purchase document kind")
        }
        insertRestrictedEvidenceReservation(
            evidenceId = value.evidenceId,
            uploadSessionId = value.uploadSessionId,
            operationId = value.operationId,
            objectKey = value.objectKey,
            organizationId = value.organizationId,
            targetType = "SERVICE_PURCHASE_DOCUMENT",
            targetId = value.documentSlotId,
            requirementKey = expected + "_BINARY",
            evidenceTypeCode = expected,
            contentType = value.contentType,
            originalFileName = value.originalFileName,
            sizeBytes = value.sizeBytes,
            sha256 = value.sha256,
            capturedAt = value.capturedAt,
            clientOccurredAt = value.clientOccurredAt,
            capturedByUserId = value.capturedByUserId,
            securityScanClass = value.securityScanClass,
            uploadExpiresAt = value.uploadExpiresAt,
            createdAt = value.createdAt,
        )
    }

    override fun insertSupplierInvoiceDocumentReservation(
        value: SupplierInvoiceDocumentEvidenceReservationInsert,
    ) = insertRestrictedEvidenceReservation(
        evidenceId = value.evidenceId,
        uploadSessionId = value.uploadSessionId,
        operationId = value.operationId,
        objectKey = value.objectKey,
        organizationId = value.organizationId,
        targetType = "SUPPLIER_INVOICE_DOCUMENT",
        targetId = value.documentSlotId,
        requirementKey = "SUPPLIER_INVOICE_BINARY",
        evidenceTypeCode = "SUPPLIER_INVOICE",
        contentType = value.contentType,
        originalFileName = value.originalFileName,
        sizeBytes = value.sizeBytes,
        sha256 = value.sha256,
        capturedAt = value.capturedAt,
        clientOccurredAt = value.clientOccurredAt,
        capturedByUserId = value.capturedByUserId,
        securityScanClass = value.securityScanClass,
        uploadExpiresAt = value.uploadExpiresAt,
        createdAt = value.createdAt,
    )

    override fun insertPoReceivingDeliveryNoteReservation(
        value: PoReceivingDeliveryNoteEvidenceReservationInsert,
    ) = insertRestrictedEvidenceReservation(
        evidenceId = value.evidenceId,
        uploadSessionId = value.uploadSessionId,
        operationId = value.operationId,
        objectKey = value.objectKey,
        organizationId = value.organizationId,
        targetType = "PO_RECEIVING_DELIVERY_NOTE",
        targetId = value.receivingRecordId,
        requirementKey = "PO_DELIVERY_NOTE_BINARY",
        evidenceTypeCode = "PO_DELIVERY_NOTE",
        contentType = value.contentType,
        originalFileName = value.originalFileName,
        sizeBytes = value.sizeBytes,
        sha256 = value.sha256,
        capturedAt = value.capturedAt,
        clientOccurredAt = value.clientOccurredAt,
        capturedByUserId = value.capturedByUserId,
        securityScanClass = value.securityScanClass,
        uploadExpiresAt = value.uploadExpiresAt,
        createdAt = value.createdAt,
    )

    override fun insertFinanceExpenseReceiptReservation(
        value: FinanceExpenseReceiptEvidenceReservationInsert,
    ) = insertRestrictedEvidenceReservation(
        evidenceId = value.evidenceId,
        uploadSessionId = value.uploadSessionId,
        operationId = value.operationId,
        objectKey = value.objectKey,
        organizationId = value.organizationId,
        targetType = "FINANCE_EXPENSE_RECEIPT",
        targetId = value.expenseDraftId,
        requirementKey = "FINANCE_EXPENSE_RECEIPT_BINARY",
        evidenceTypeCode = "FINANCE_EXPENSE_RECEIPT",
        contentType = value.contentType,
        originalFileName = value.originalFileName,
        sizeBytes = value.sizeBytes,
        sha256 = value.sha256,
        capturedAt = value.capturedAt,
        clientOccurredAt = value.clientOccurredAt,
        capturedByUserId = value.capturedByUserId,
        securityScanClass = value.securityScanClass,
        uploadExpiresAt = value.uploadExpiresAt,
        createdAt = value.createdAt,
    )

    /** Fixed internal classification for sensitive HR and supplier documents. */
    private fun insertRestrictedEvidenceReservation(
        evidenceId: UUID,
        uploadSessionId: UUID,
        operationId: UUID,
        objectKey: String,
        organizationId: UUID,
        targetType: String,
        targetId: UUID,
        requirementKey: String,
        evidenceTypeCode: String,
        contentType: String,
        originalFileName: String?,
        sizeBytes: Long,
        sha256: String,
        capturedAt: Instant,
        clientOccurredAt: Instant?,
        capturedByUserId: UUID,
        securityScanClass: String,
        uploadExpiresAt: Instant,
        createdAt: Instant,
    ) {
        require(targetType in setOf(
            "EMPLOYEE_DOCUMENT", "SUPPLIER_QUOTE_DOCUMENT", "SUPPLIER_INVOICE_DOCUMENT",
            "PO_RECEIVING_DELIVERY_NOTE", "SERVICE_QUOTE_DOCUMENT",
            "SERVICE_PURCHASE_DOCUMENT", "FINANCE_EXPENSE_RECEIPT",
        ))
        jdbc.update(
            """
            INSERT INTO evidence (
                id, organization_id, target_type, target_id, work_order_id,
                evidence_requirement_key, evidence_policy_id, evidence_policy_revision,
                evidence_type_code, content_type, original_file_name, size_bytes, sha256,
                captured_at, client_occurred_at, captured_by_user_id,
                source_device_id, instruction_revision, work_order_version_at_capture,
                storage_state, object_key_ref, finalized_at, classification_code,
                client_visibility_mode, security_scan_class, supersedes_evidence_id,
                created_at, version
            ) VALUES (
                ?, ?, ?, ?, NULL,
                ?, NULL, NULL,
                ?, ?, ?, ?, ?,
                ?, ?, ?,
                NULL, NULL, NULL,
                'RESERVED', ?, NULL, 'HIGHLY_RESTRICTED',
                'INTERNAL_ONLY', ?, NULL,
                ?, 1
            )
            """.trimIndent(),
            evidenceId, organizationId, targetType, targetId, requirementKey,
            evidenceTypeCode, contentType, originalFileName, sizeBytes, sha256,
            capturedAt.atOffset(ZoneOffset.UTC),
            clientOccurredAt?.atOffset(ZoneOffset.UTC),
            capturedByUserId, objectKey, securityScanClass,
            createdAt.atOffset(ZoneOffset.UTC),
        )
        insertUploadSession(
            evidenceId = evidenceId,
            uploadSessionId = uploadSessionId,
            operationId = operationId,
            sha256 = sha256,
            sizeBytes = sizeBytes,
            uploadExpiresAt = uploadExpiresAt,
            createdAt = createdAt,
        )
    }

    private fun insertUploadSession(
        evidenceId: UUID,
        uploadSessionId: UUID,
        operationId: UUID,
        sha256: String,
        sizeBytes: Long,
        uploadExpiresAt: Instant,
        createdAt: Instant,
    ) {
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
            uploadSessionId,
            evidenceId,
            sha256,
            sizeBytes,
            uploadExpiresAt.atOffset(
                ZoneOffset.UTC,
            ),
            createdAt.atOffset(
                ZoneOffset.UTC,
            ),
            operationId,
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
            finalizeSelect(
                predicate = "e.id = ?",
                includeLimit = true,
            ),
            finalizeRecordMapper,
            evidenceId,
        ).singleOrNull()

    override fun loadFinalizeRecord(
        evidenceId: UUID,
        uploadSessionId: UUID,
    ): EvidenceFinalizeRecord? =
        jdbc.query(
            finalizeSelect(
                predicate =
                    "e.id = ? AND us.id = ?",
                includeLimit = false,
            ),
            finalizeRecordMapper,
            evidenceId,
            uploadSessionId,
        ).singleOrNull()

    private fun finalizeSelect(
        predicate: String,
        includeLimit: Boolean,
    ): String =
        """
        SELECT
            e.id AS evidence_id,
            us.id AS upload_session_id,
            e.organization_id,
            e.target_type,
            e.target_id,
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
            COALESCE(
                e.security_scan_class,
                epr.security_scan_class
            ) AS security_scan_class,
            e.version AS evidence_version,
            us.version AS upload_session_version
        FROM evidence e
        JOIN evidence_upload_session us
          ON us.evidence_id = e.id
        LEFT JOIN work_order wo
          ON wo.id = e.work_order_id
        LEFT JOIN evidence_policy_requirement epr
          ON epr.config_revision_id =
             e.evidence_policy_id
         AND epr.requirement_key =
             e.evidence_requirement_key
        WHERE $predicate
        ${if (includeLimit) "ORDER BY us.created_at DESC, us.id DESC LIMIT 1" else ""}
        """.trimIndent()

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
                targetType =
                    rs.getString(
                        "target_type",
                    ),
                targetId =
                    rs.getObject(
                        "target_id",
                        UUID::class.java,
                    ),
                workOrderId =
                    rs.getObject(
                        "work_order_id",
                        UUID::class.java,
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
                    rs.getString(
                        "evidence_requirement_key",
                    ),
                evidencePolicyId =
                    rs.getObject(
                        "evidence_policy_id",
                        UUID::class.java,
                    ),
                evidencePolicyRevision =
                    (rs.getObject(
                        "evidence_policy_revision",
                    ) as? Number)
                        ?.toInt(),
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
                    requireNotNull(
                        rs.getString(
                            "security_scan_class",
                        ),
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
                targetType =
                    rs.getString(
                        "target_type",
                    ),
                targetId =
                    rs.getObject(
                        "target_id",
                        UUID::class.java,
                    ),
                workOrderId =
                    rs.getObject(
                        "work_order_id",
                        UUID::class.java,
                    ),
                evidenceRequirementKey =
                    rs.getString(
                        "evidence_requirement_key",
                    ),
                evidencePolicyId =
                    rs.getObject(
                        "evidence_policy_id",
                        UUID::class.java,
                    ),
                evidencePolicyRevision =
                    (rs.getObject(
                        "evidence_policy_revision",
                    ) as? Number)
                        ?.toInt(),
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
                securityScanClass =
                    rs.getString(
                        "security_scan_class",
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
            e.target_type,
            e.target_id,
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
            e.security_scan_class,
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

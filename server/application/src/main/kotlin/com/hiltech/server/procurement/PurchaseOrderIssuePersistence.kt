package com.hiltech.server.procurement

import com.hiltech.server.approval.ApprovalRequestState
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Canonical current ApprovalEngine decision, locked against concurrent finalization. */
data class PurchaseOrderApprovalSnapshot(
    val approvalRequestId: UUID,
    val policyVersion: Int,
    val state: ApprovalRequestState,
    val approvedDecisionCount: Int,
)

data class PurchaseOrderIssuedRecord(
    val id: UUID,
    val operationId: UUID,
    val organizationId: UUID,
    val candidateId: UUID,
    val approvalRequestId: UUID,
    val approvalPolicyKey: String,
    val approvalPolicyVersion: Int,
    val issuerAuthorityKey: String,
    val issuerAuthorityBindingId: UUID,
    val code: String,
    val serial: Long,
    val supplierOrganizationId: UUID,
    val deliveryStorageLocationId: UUID,
    val stockItemId: UUID,
    val quoteId: UUID,
    val quoteSha256: String,
    val issuer: UUID,
    val issuedAt: Instant,
    val correlationId: String,
)

data class PurchaseOrderIssuerQueueItem(
    val candidateId: UUID,
    val supplierOrganizationId: UUID,
    val supplierName: String,
    val stockItemId: UUID,
    val quantity: String,
    val unitCode: String,
    val currency: String,
    val total: String,
    val approvalRequestId: UUID,
    val approvalPolicyVersion: Int,
)

data class PurchaseOrderDeliveryDestination(
    val storageLocationId: UUID,
    val code: String,
    val name: String,
    val kind: String,
)

@Repository
class PurchaseOrderIssuePersistence(private val jdbc: JdbcTemplate) {
    fun issuerQueue(
        organizationId: UUID,
        policyKey: String,
        policyVersion: Int,
    ): List<PurchaseOrderIssuerQueueItem> = jdbc.query(
        """
        SELECT c.id, c.supplier_organization_id, o.display_name AS supplier_name,
               c.stock_item_id, c.quantity, c.unit_code, c.currency, c.total_amount,
               a.id AS approval_request_id, a.policy_version
        FROM procurement_po_candidate c
        JOIN approval_request a ON a.organization_id = c.organization_id
          AND a.subject_type = 'PROCUREMENT_PO_CANDIDATE'
          AND a.subject_id = c.id AND a.subject_version = 1
          AND a.policy_key = ? AND a.policy_version = ? AND a.state = 'APPROVED'
        JOIN organization o ON o.id = c.supplier_organization_id
        LEFT JOIN procurement_purchase_order po ON po.candidate_id = c.id
        WHERE c.organization_id = ? AND po.id IS NULL
        ORDER BY c.created_at DESC, c.id DESC LIMIT 100
        """.trimIndent(),
        { rs, _ -> PurchaseOrderIssuerQueueItem(
            rs.getObject("id", UUID::class.java),
            rs.getObject("supplier_organization_id", UUID::class.java),
            rs.getString("supplier_name"), rs.getObject("stock_item_id", UUID::class.java),
            rs.getBigDecimal("quantity").toPlainString(), rs.getString("unit_code"),
            rs.getString("currency"), rs.getBigDecimal("total_amount").toPlainString(),
            rs.getObject("approval_request_id", UUID::class.java),
            rs.getInt("policy_version"),
        ) },
        policyKey, policyVersion, organizationId,
    )

    fun deliveryDestinations(
        organizationId: UUID,
        projectId: UUID,
        siteId: UUID,
        at: Instant,
    ): List<PurchaseOrderDeliveryDestination> = jdbc.query(
        """
        SELECT id, code, name, kind FROM storage_location l
        WHERE organization_id = ?
          AND (active_from IS NULL OR active_from <= ?)
          AND (active_until IS NULL OR active_until > ?)
          AND (project_id IS NULL OR project_id = ?)
          AND kind IN ('MAIN_WAREHOUSE','WAREHOUSE','PROJECT_STORAGE','SITE_STORAGE')
          AND (kind <> 'SITE_STORAGE' OR site_id = ?)
        ORDER BY kind, name, id LIMIT 100
        """.trimIndent(),
        { rs, _ -> PurchaseOrderDeliveryDestination(
            rs.getObject("id", UUID::class.java),
            rs.getString("code"), rs.getString("name"), rs.getString("kind"),
        ) },
        organizationId, at.atOffset(ZoneOffset.UTC), at.atOffset(ZoneOffset.UTC),
        projectId, siteId,
    )

    fun byCandidate(candidateId: UUID): PurchaseOrderIssuedRecord? = jdbc.query(
        "SELECT * FROM procurement_purchase_order WHERE candidate_id = ?",
        { rs, _ ->
            PurchaseOrderIssuedRecord(
                rs.getObject("id", UUID::class.java),
                rs.getObject("operation_id", UUID::class.java),
                rs.getObject("organization_id", UUID::class.java),
                rs.getObject("candidate_id", UUID::class.java),
                rs.getObject("approval_request_id", UUID::class.java),
                rs.getString("approval_policy_key"),
                rs.getInt("approval_policy_version"),
                rs.getString("issuer_authority_key"),
                rs.getObject("issuer_authority_binding_id", UUID::class.java),
                rs.getString("po_code"), rs.getLong("po_serial"),
                rs.getObject("supplier_organization_id", UUID::class.java),
                rs.getObject("delivery_storage_location_id", UUID::class.java),
                rs.getObject("stock_item_id", UUID::class.java),
                rs.getObject("quote_evidence_id", UUID::class.java),
                rs.getString("quote_evidence_sha256"),
                rs.getObject("issued_by_user_id", UUID::class.java),
                rs.getObject("issued_at", OffsetDateTime::class.java).toInstant(),
                rs.getString("correlation_id"),
            )
        }, candidateId,
    ).singleOrNull()

    /**
     * Hold current WorkOrder/StockItem rows through PO issuance. Writers must
     * wait, so the checked source version cannot change before commit.
     */
    fun lockWorkAndStockSource(
        organizationId: UUID,
        workOrderId: UUID,
        stockItemId: UUID,
    ): Boolean {
        val workLocked = jdbc.query(
            "SELECT id FROM work_order WHERE id = ? AND organization_id = ? FOR SHARE",
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            workOrderId, organizationId,
        ).isNotEmpty()
        if (!workLocked) return false
        return jdbc.query(
            "SELECT id FROM stock_item WHERE id = ? AND organization_id = ? FOR SHARE",
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            stockItemId, organizationId,
        ).isNotEmpty()
    }

    /** Existing Phase-2 request is the ONLY approval evidence; no client attestation. */
    fun lockApprovedCandidate(
        organizationId: UUID,
        candidateId: UUID,
        policyKey: String,
    ): PurchaseOrderApprovalSnapshot? = jdbc.query(
        """
        SELECT a.id, a.policy_version, a.state,
          (SELECT count(*) FROM approval_decision d
           WHERE d.approval_request_id = a.id AND d.decision = 'APPROVE') AS approved_decision_count
        FROM approval_request a
        WHERE a.organization_id = ? AND a.subject_type = 'PROCUREMENT_PO_CANDIDATE'
          AND a.subject_id = ? AND a.subject_version = 1 AND a.policy_key = ?
        FOR UPDATE OF a
        """.trimIndent(),
        { rs, _ -> PurchaseOrderApprovalSnapshot(
            rs.getObject("id", UUID::class.java),
            rs.getInt("policy_version"),
            ApprovalRequestState.valueOf(rs.getString("state")),
            rs.getInt("approved_decision_count"),
        ) }, organizationId, candidateId, policyKey,
    ).singleOrNull()

    fun validDeliveryLocation(
        organizationId: UUID,
        destinationId: UUID,
        projectId: UUID,
        siteId: UUID,
        at: Instant,
    ): Boolean = jdbc.queryForObject(
        """
        SELECT EXISTS (
          SELECT 1 FROM storage_location l
          WHERE l.id = ? AND l.organization_id = ?
            AND (l.active_from IS NULL OR l.active_from <= ?)
            AND (l.active_until IS NULL OR l.active_until > ?)
            AND (l.project_id IS NULL OR l.project_id = ?)
            AND l.kind IN ('MAIN_WAREHOUSE','WAREHOUSE','PROJECT_STORAGE','SITE_STORAGE')
            AND (l.kind <> 'SITE_STORAGE' OR l.site_id = ?)
        )
        """.trimIndent(),
        Boolean::class.java, destinationId, organizationId,
        at.atOffset(ZoneOffset.UTC), at.atOffset(ZoneOffset.UTC), projectId, siteId,
    ) == true

    /** Atomic company-scoped PO serial, allocated only inside idempotent issue transaction. */
    fun allocateSerial(organizationId: UUID): Long = jdbc.queryForObject(
        """
        INSERT INTO procurement_po_number_counter (organization_id, last_number)
        VALUES (?, 1)
        ON CONFLICT (organization_id)
        DO UPDATE SET last_number = procurement_po_number_counter.last_number + 1
        RETURNING last_number
        """.trimIndent(),
        Long::class.java, organizationId,
    ) ?: error("PO sequence did not allocate a number.")

    fun insert(record: PurchaseOrderIssuedRecord, candidate: PurchaseOrderCandidateRecord) {
        check(jdbc.update(
            """
            INSERT INTO procurement_purchase_order (
              id, operation_id, organization_id, candidate_id,
              approval_request_id, approval_policy_key, approval_policy_version,
              issuer_authority_key, issuer_authority_binding_id, po_code, po_serial,
              supplier_organization_id, delivery_storage_location_id, stock_item_id,
              quantity, unit_code, currency, unit_price, tax_amount, total_amount,
              quote_evidence_id, quote_evidence_sha256, issued_by_user_id, issued_at,
              correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            record.id, record.operationId, record.organizationId, record.candidateId,
            record.approvalRequestId, record.approvalPolicyKey, record.approvalPolicyVersion,
            record.issuerAuthorityKey, record.issuerAuthorityBindingId,
            record.code, record.serial, record.supplierOrganizationId,
            record.deliveryStorageLocationId, record.stockItemId,
            candidate.quantity, candidate.unitCode, candidate.currency,
            candidate.unitPrice, candidate.taxAmount, candidate.totalAmount,
            record.quoteId, record.quoteSha256, record.issuer,
            record.issuedAt.atOffset(ZoneOffset.UTC), record.correlationId,
        ) == 1)
    }
}

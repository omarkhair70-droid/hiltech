package com.hiltech.server.procurement

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Immutable internal candidate for a later approved PO.
 * It is not an order, not an award, and not an ApprovalEngine decision.
 */
data class PurchaseOrderCandidateRecord(
    val id: UUID,
    val operationId: UUID,
    val organizationId: UUID,
    val rfqId: UUID,
    val requirementId: UUID,
    val requirementVersion: Long,
    val quoteId: UUID,
    val quoteRevision: Int,
    val supplierOrganizationId: UUID,
    val stockItemId: UUID,
    val quantity: BigDecimal,
    val unitCode: String,
    val currency: String,
    val unitPrice: BigDecimal,
    val taxAmount: BigDecimal,
    val totalAmount: BigDecimal,
    val sourceSha256: String,
    val rationale: String,
    val proposedBy: UUID,
    val createdAt: Instant,
    val correlationId: String,
    val revisionNumber: Int = 1,
    val replacesCandidateId: UUID? = null,
)

data class PurchaseCandidateReviewState(val requestId: UUID, val state: String, val requestVersion: Long, val policyVersion: Int, val comment: String?)

@Repository
class PurchaseOrderCandidatePersistence(private val jdbc: JdbcTemplate) {
    /** Serializes approval and issue preflight against the immutable candidate. */
    fun lockCandidate(candidateId: UUID): Boolean = jdbc.query(
        "SELECT id FROM procurement_po_candidate WHERE id = ? FOR UPDATE",
        { rs, _ -> rs.getObject("id", UUID::class.java) }, candidateId,
    ).isNotEmpty()

    /** ApprovalEngine subject lookup; never trusts an arbitrary RFQ identifier. */
    fun byId(candidateId: UUID): PurchaseOrderCandidateRecord? = jdbc.query(
        "SELECT * FROM procurement_po_candidate WHERE id=?", { row, _ -> row.candidate() }, candidateId,
    ).singleOrNull()

    fun byRfq(rfqId: UUID): PurchaseOrderCandidateRecord? = jdbc.query(
        "SELECT * FROM procurement_po_candidate WHERE rfq_id = ? ORDER BY revision_number DESC LIMIT 1",
        { row, _ -> row.candidate() }, rfqId,
    ).singleOrNull()

    fun hasIssuedOrder(rfqId: UUID): Boolean = jdbc.queryForObject(
        """SELECT EXISTS(SELECT 1 FROM procurement_purchase_order po JOIN procurement_po_candidate c ON c.id=po.candidate_id WHERE c.rfq_id=?)""",
        Boolean::class.java, rfqId,
    ) == true

    fun hasPendingReview(candidateId: UUID): Boolean = jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM approval_request WHERE subject_type='PROCUREMENT_PO_CANDIDATE' AND subject_id=? AND state='PENDING')",
        Boolean::class.java, candidateId,
    ) == true

    fun latestReview(candidateId: UUID): PurchaseCandidateReviewState? = jdbc.query(
        """SELECT a.id,a.state,a.version,a.policy_version,
            (SELECT d.comment_text FROM approval_decision d WHERE d.approval_request_id=a.id ORDER BY d.decided_at DESC LIMIT 1) AS comment
           FROM approval_request a WHERE a.subject_type='PROCUREMENT_PO_CANDIDATE' AND a.subject_id=? AND a.subject_version=1
           ORDER BY a.created_at DESC,a.id DESC LIMIT 1""".trimIndent(),
        { rs, _ -> PurchaseCandidateReviewState(rs.getObject("id", UUID::class.java), rs.getString("state"),
            rs.getLong("version"), rs.getInt("policy_version"), rs.getString("comment")) }, candidateId,
    ).singleOrNull()

    private fun ResultSet.candidate() = PurchaseOrderCandidateRecord(
        getObject("id", UUID::class.java), getObject("operation_id", UUID::class.java), getObject("organization_id", UUID::class.java),
        getObject("rfq_id", UUID::class.java), getObject("purchase_requirement_id", UUID::class.java), getLong("requirement_version"),
        getObject("quote_evidence_id", UUID::class.java), getInt("quote_revision"), getObject("supplier_organization_id", UUID::class.java),
        getObject("stock_item_id", UUID::class.java), getBigDecimal("quantity"), getString("unit_code"), getString("currency"),
        getBigDecimal("unit_price"), getBigDecimal("tax_amount"), getBigDecimal("total_amount"), getString("quote_evidence_sha256"),
        getString("rationale"), getObject("selected_by_user_id", UUID::class.java), getObject("created_at", OffsetDateTime::class.java).toInstant(),
        getString("correlation_id"), getInt("revision_number"), getObject("replaces_candidate_id", UUID::class.java),
    )

    fun insert(candidate: PurchaseOrderCandidateRecord) {
        check(jdbc.update(
            """
            INSERT INTO procurement_po_candidate (
                id, operation_id, organization_id, rfq_id,
                purchase_requirement_id, requirement_version,
                quote_evidence_id, quote_revision, supplier_organization_id,
                stock_item_id, quantity, unit_code, currency,
                unit_price, tax_amount, total_amount, quote_evidence_sha256,
                rationale, selected_by_user_id, created_at, correlation_id,
                state, version, revision_number, replaces_candidate_id
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT_FOR_APPROVAL', 1, ?, ?
            )
            """.trimIndent(),
            candidate.id, candidate.operationId, candidate.organizationId,
            candidate.rfqId, candidate.requirementId, candidate.requirementVersion,
            candidate.quoteId, candidate.quoteRevision, candidate.supplierOrganizationId,
            candidate.stockItemId, candidate.quantity, candidate.unitCode,
            candidate.currency, candidate.unitPrice, candidate.taxAmount,
            candidate.totalAmount, candidate.sourceSha256, candidate.rationale,
            candidate.proposedBy, candidate.createdAt.atOffset(ZoneOffset.UTC),
            candidate.correlationId,
            candidate.revisionNumber, candidate.replacesCandidateId,
        ) == 1)
    }
}

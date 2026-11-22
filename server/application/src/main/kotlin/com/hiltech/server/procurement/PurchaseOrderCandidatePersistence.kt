package com.hiltech.server.procurement

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
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
)

@Repository
class PurchaseOrderCandidatePersistence(private val jdbc: JdbcTemplate) {
    /** ApprovalEngine subject lookup; never trusts an arbitrary RFQ identifier. */
    fun byId(candidateId: UUID): PurchaseOrderCandidateRecord? =
        jdbc.query(
            "SELECT rfq_id FROM procurement_po_candidate WHERE id = ?",
            { row, _ -> row.getObject("rfq_id", UUID::class.java) }, candidateId,
        ).singleOrNull()?.let(::byRfq)?.takeIf { it.id == candidateId }

    fun byRfq(rfqId: UUID): PurchaseOrderCandidateRecord? = jdbc.query(
        "SELECT * FROM procurement_po_candidate WHERE rfq_id = ?",
        { row, _ -> PurchaseOrderCandidateRecord(
            id = row.getObject("id", UUID::class.java),
            operationId = row.getObject("operation_id", UUID::class.java),
            organizationId = row.getObject("organization_id", UUID::class.java),
            rfqId = row.getObject("rfq_id", UUID::class.java),
            requirementId = row.getObject("purchase_requirement_id", UUID::class.java),
            requirementVersion = row.getLong("requirement_version"),
            quoteId = row.getObject("quote_evidence_id", UUID::class.java),
            quoteRevision = row.getInt("quote_revision"),
            supplierOrganizationId = row.getObject("supplier_organization_id", UUID::class.java),
            stockItemId = row.getObject("stock_item_id", UUID::class.java),
            quantity = row.getBigDecimal("quantity"),
            unitCode = row.getString("unit_code"),
            currency = row.getString("currency"),
            unitPrice = row.getBigDecimal("unit_price"),
            taxAmount = row.getBigDecimal("tax_amount"),
            totalAmount = row.getBigDecimal("total_amount"),
            sourceSha256 = row.getString("quote_evidence_sha256"),
            rationale = row.getString("rationale"),
            proposedBy = row.getObject("selected_by_user_id", UUID::class.java),
            createdAt = row.getObject("created_at", OffsetDateTime::class.java).toInstant(),
            correlationId = row.getString("correlation_id"),
        ) }, rfqId,
    ).singleOrNull()

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
                state, version
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT_FOR_APPROVAL', 1
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
        ) == 1)
    }
}

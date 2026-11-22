package com.hiltech.server.procurement

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class SupplierRfqRecord(
    val id: UUID,
    val operationId: UUID,
    val organizationId: UUID,
    val requirementId: UUID,
    val requirementVersion: Long,
    val stockItemId: UUID,
    val sourceQuantity: BigDecimal,
    val sourceUnitCode: String,
    val createdBy: UUID,
    val createdAt: Instant,
    val correlationId: String,
)

data class SupplierQuoteRecord(
    val evidence: SupplierQuoteEvidence,
    val operationId: UUID,
    val organizationId: UUID,
    val recordedBy: UUID,
    val recordedAt: Instant,
    val correlationId: String,
)

@Repository
class SupplierSourcingPersistence(private val jdbc: JdbcTemplate) {
    fun insertRfq(record: SupplierRfqRecord) {
        check(jdbc.update(
            """
            INSERT INTO procurement_supplier_rfq (
                id, operation_id, organization_id, requirement_id, requirement_version,
                stock_item_id, source_quantity, source_unit_code, created_by_user_id,
                created_at, correlation_id, state, version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', 1)
            """.trimIndent(),
            record.id, record.operationId, record.organizationId,
            record.requirementId, record.requirementVersion, record.stockItemId,
            record.sourceQuantity, record.sourceUnitCode, record.createdBy,
            record.createdAt.atOffset(ZoneOffset.UTC), record.correlationId,
        ) == 1)
    }

    /** Lock the RFQ row while appending a supplier revision; root itself is immutable. */
    fun lockRfq(id: UUID): Boolean = jdbc.query(
        "SELECT id FROM procurement_supplier_rfq WHERE id = ? FOR UPDATE",
        { row, _ -> row.getObject("id", UUID::class.java) }, id,
    ).isNotEmpty()

    fun rfq(id: UUID): SupplierRfqRecord? = jdbc.query(
        "SELECT * FROM procurement_supplier_rfq WHERE id = ?",
        { row, _ -> row.rfqRecord() }, id,
    ).singleOrNull()

    /**
     * Candidate needs only after a current technical READY_FOR_SOURCING event.
     * Caller must be an explicitly scoped buyer. No buyer price fields here.
     */
    fun eligibleRequirementIds(organizationId: UUID): List<UUID> = jdbc.query(
        """
        SELECT p.id FROM procurement_purchase_requirement p
        JOIN LATERAL (
            SELECT t.to_state FROM procurement_requirement_transition t
            WHERE t.requirement_id = p.id
            ORDER BY t.result_version DESC LIMIT 1
        ) current_state ON true
        WHERE p.organization_id = ? AND current_state.to_state = 'READY_FOR_SOURCING'
        ORDER BY p.created_at DESC, p.id DESC LIMIT 100
        """.trimIndent(),
        { row, _ -> row.getObject("id", UUID::class.java) },
        organizationId,
    )

    /** The buyer's own organization's internal RFQ rounds for this requirement. */
    fun roundsForRequirement(organizationId: UUID, requirementId: UUID): List<SupplierRfqRecord> =
        jdbc.query(
            """
            SELECT * FROM procurement_supplier_rfq
            WHERE organization_id = ? AND requirement_id = ?
            ORDER BY requirement_version DESC, created_at DESC, id DESC
            LIMIT 100
            """.trimIndent(),
            { row, _ -> row.rfqRecord() }, organizationId, requirementId,
        )

    fun countQuotes(rfqId: UUID): Int = jdbc.query(
        "SELECT COUNT(*) FROM procurement_supplier_quote_evidence WHERE rfq_id = ?",
        { row, _ -> row.getInt(1) }, rfqId,
    ).single()

    fun byRequirementAndVersion(requirementId: UUID, version: Long): SupplierRfqRecord? =
        jdbc.query(
            """
            SELECT * FROM procurement_supplier_rfq
            WHERE requirement_id = ? AND requirement_version = ?
            """.trimIndent(),
            { row, _ -> row.rfqRecord() }, requirementId, version,
        ).singleOrNull()

    fun supplierTypeAndStatus(id: UUID): Pair<String, String>? = jdbc.query(
        "SELECT organization_type, status FROM organization WHERE id = ?",
        { row, _ -> row.getString("organization_type") to row.getString("status") }, id,
    ).singleOrNull()

    fun quoteTechnicalTarget(quoteEvidenceId: UUID): SupplierQuoteTechnicalTarget? = jdbc.query(
        """
        SELECT id, rfq_id, organization_id
        FROM procurement_supplier_quote_evidence WHERE id = ?
        """.trimIndent(),
        { row, _ -> SupplierQuoteTechnicalTarget(
            row.getObject("id", UUID::class.java),
            row.getObject("rfq_id", UUID::class.java),
            row.getObject("organization_id", UUID::class.java),
        ) }, quoteEvidenceId,
    ).singleOrNull()

    fun latestTechnicalReviewRevision(quoteEvidenceId: UUID): Int = jdbc.query(
        """
        SELECT review_revision FROM procurement_supplier_quote_technical_assessment
        WHERE quote_evidence_id = ?
        ORDER BY review_revision DESC LIMIT 1
        """.trimIndent(),
        { row, _ -> row.getInt("review_revision") }, quoteEvidenceId,
    ).firstOrNull() ?: 0

    fun insertTechnicalAssessment(record: SupplierQuoteTechnicalAssessmentRecord) {
        check(jdbc.update(
            """
            INSERT INTO procurement_supplier_quote_technical_assessment (
                id, operation_id, quote_evidence_id, rfq_id, organization_id,
                review_revision, decision, justification, reviewed_by_user_id,
                reviewed_at, correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            record.id, record.operationId, record.quoteEvidenceId, record.rfqId,
            record.organizationId, record.reviewRevision, record.decision,
            record.justification, record.reviewedBy,
            record.reviewedAt.atOffset(ZoneOffset.UTC), record.correlationId,
        ) == 1)
    }

    /** Restricted buyer projection, latest event per exact quote evidence revision. */
    fun latestTechnicalAssessments(rfqId: UUID): Map<UUID, String> = jdbc.query(
        """
        SELECT DISTINCT ON (quote_evidence_id) quote_evidence_id, decision
        FROM procurement_supplier_quote_technical_assessment
        WHERE rfq_id = ?
        ORDER BY quote_evidence_id, review_revision DESC
        """.trimIndent(),
        { row, _ ->
            row.getObject("quote_evidence_id", UUID::class.java) to row.getString("decision")
        }, rfqId,
    ).toMap()

    fun latestRevision(rfqId: UUID, supplierId: UUID): Int = jdbc.query(
        """
        SELECT revision_number FROM procurement_supplier_quote_evidence
        WHERE rfq_id = ? AND supplier_organization_id = ?
        ORDER BY revision_number DESC LIMIT 1
        """.trimIndent(),
        { row, _ -> row.getInt("revision_number") }, rfqId, supplierId,
    ).firstOrNull() ?: 0

    fun insertQuote(record: SupplierQuoteRecord) {
        val q = record.evidence
        check(jdbc.update(
            """
            INSERT INTO procurement_supplier_quote_evidence (
                id, operation_id, organization_id, rfq_id,
                supplier_organization_id, supplier_quote_reference, revision_number,
                currency, quoted_quantity, quoted_unit_code, unit_price, tax_amount,
                delivery_days, valid_until, delivery_terms, payment_terms,
                warranty_terms, source_evidence_ref, source_sha256,
                technical_compliance, recorded_by_user_id, recorded_at, correlation_id
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                'NOT_ASSESSED', ?, ?, ?
            )
            """.trimIndent(),
            q.id, record.operationId, record.organizationId, q.rfqId,
            q.supplierOrganizationId, q.supplierQuoteReference, q.revisionNumber,
            q.currency, q.quantity, q.unitCode, q.unitPrice, q.taxAmount,
            q.deliveryDays, q.validUntil, q.deliveryTerms, q.paymentTerms,
            q.warrantyTerms, q.sourceEvidenceRef, q.sourceSha256,
            record.recordedBy, record.recordedAt.atOffset(ZoneOffset.UTC),
            record.correlationId,
        ) == 1)
    }

    fun quotes(rfqId: UUID): List<SupplierQuoteEvidence> = jdbc.query(
        """
        SELECT * FROM procurement_supplier_quote_evidence
        WHERE rfq_id = ?
        ORDER BY supplier_organization_id, revision_number, id
        """.trimIndent(),
        { row, _ -> SupplierQuoteEvidence(
            id = row.getObject("id", UUID::class.java),
            rfqId = row.getObject("rfq_id", UUID::class.java),
            supplierOrganizationId = row.getObject("supplier_organization_id", UUID::class.java),
            supplierQuoteReference = row.getString("supplier_quote_reference"),
            revisionNumber = row.getInt("revision_number"),
            currency = row.getString("currency"),
            quantity = row.getBigDecimal("quoted_quantity"),
            unitCode = row.getString("quoted_unit_code"),
            unitPrice = row.getBigDecimal("unit_price"),
            taxAmount = row.getBigDecimal("tax_amount"),
            deliveryDays = row.getObject("delivery_days")?.let { (it as Number).toInt() },
            validUntil = row.getDate("valid_until")?.toLocalDate(),
            deliveryTerms = row.getString("delivery_terms"),
            paymentTerms = row.getString("payment_terms"),
            warrantyTerms = row.getString("warranty_terms"),
            sourceEvidenceRef = row.getString("source_evidence_ref"),
            sourceSha256 = row.getString("source_sha256"),
            technicalCompliance = row.getString("technical_compliance"),
        ) }, rfqId,
    )

    private fun ResultSet.rfqRecord() = SupplierRfqRecord(
        id = getObject("id", UUID::class.java),
        operationId = getObject("operation_id", UUID::class.java),
        organizationId = getObject("organization_id", UUID::class.java),
        requirementId = getObject("requirement_id", UUID::class.java),
        requirementVersion = getLong("requirement_version"),
        stockItemId = getObject("stock_item_id", UUID::class.java),
        sourceQuantity = getBigDecimal("source_quantity"),
        sourceUnitCode = getString("source_unit_code"),
        createdBy = getObject("created_by_user_id", UUID::class.java),
        createdAt = getObject("created_at", OffsetDateTime::class.java).toInstant(),
        correlationId = getString("correlation_id"),
    )
}

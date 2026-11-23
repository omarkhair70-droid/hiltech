package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalPolicyMode
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.approval.ApprovalSubjectVersionSource
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

const val FINANCE_SUPPLIER_ACCOUNTING_REVIEW_SUBJECT =
    "FINANCE_SUPPLIER_ACCOUNTING_REVIEW"

data class FinanceSupplierAccountingSnapshot(
    val candidateId: UUID,
    val organizationId: UUID,
    val supplierOrganizationId: UUID,
    val supplierInvoiceId: UUID,
    val invoiceEvidenceId: UUID,
    val invoiceNumber: String,
    val currency: String,
    val amount: BigDecimal,
    val sourceSha256: String,
    val sourceVersion: Long,
    val policyKey: String,
    val policyVersion: Int,
)

private data class SupplierAccountingSourceRow(
    val candidateId: UUID,
    val organizationId: UUID,
    val supplierOrganizationId: UUID,
    val supplierInvoiceId: UUID,
    val invoiceEvidenceId: UUID,
    val invoiceNumber: String,
    val currency: String,
    val amount: BigDecimal,
    val sourceSha256: String,
    val matchEventId: UUID,
    val matchRevision: Long,
    val purchaseOrderId: UUID,
    val handoffId: UUID,
    val evidenceVersion: Long,
    val evidenceSha256: String,
    val createdByUserId: UUID,
)

/**
 * Source-based Phase2 approval guard, not an AP posting.
 * A request is always tied to the actual P7 matched supplier invoice,
 * immutable P8A Finance candidate, private READY Evidence and current
 * company policy/authority. If an updated match or evidence arrives the
 * old request MUST fail the existing ApprovalEngine decision-time check.
 */
@Component
class FinanceSupplierAccountingApprovalSubject(
    private val jdbc: JdbcTemplate,
    private val approvals: ApprovalPersistencePort,
    private val environment: Environment,
    private val clock: Clock,
) : ApprovalSubjectVersionSource {
    override fun supports(subjectType: String): Boolean =
        subjectType == FINANCE_SUPPLIER_ACCOUNTING_REVIEW_SUBJECT

    override fun isCurrent(subject: ApprovalSubjectRef): Boolean =
        supports(subject.subjectType) &&
            current(subject.organizationId, subject.subjectId)
                ?.sourceVersion == subject.subjectVersion

    fun current(
        org: UUID, candidateId: UUID,
    ): FinanceSupplierAccountingSnapshot? {
        if (!environment.getProperty(
                "hiltech.finance.supplier-accounting-review.enabled",
                Boolean::class.java, false,
            ) ||
            !environment.getProperty(
                "hiltech.finance.payable-candidates.enabled",
                Boolean::class.java, false,
            )) return null
        val key = environment.getProperty(
            "hiltech.finance.supplier-accounting-review.policy-key", "",
        ).trim()
        if (!Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(key)) return null
        val now = clock.instant()
        val policy = approvals.loadActivePolicy(org, key, now)?.takeIf {
            it.organizationId == org && it.policyKey == key &&
                it.approvalRequired && it.mode == ApprovalPolicyMode.SINGLE &&
                !it.authorityKey.isNullOrBlank()
        } ?: return null
        val authority = approvals.resolveCurrentAuthority(
            org, policy.authorityKey!!, now,
        ) ?: return null
        if (authority.organizationId != org ||
            authority.authorityKey != policy.authorityKey) return null

        val rows = jdbc.query(
            """
            SELECT c.id AS candidate_id,c.organization_id,
                c.supplier_organization_id,c.supplier_invoice_id,
                c.invoice_evidence_id,c.invoice_number,c.currency,
                c.gross_amount,c.source_sha256,
                c.matched_event_id,c.match_revision,
                c.purchase_order_id,c.finance_handoff_id,
                c.recorded_by_user_id,
                e.version AS evidence_version,
                e.sha256 AS evidence_sha256
            FROM finance_payable_candidate c
            JOIN procurement_supplier_invoice_finance_handoff h
                ON h.id=c.finance_handoff_id
                AND h.organization_id=c.organization_id
                AND h.invoice_id=c.supplier_invoice_id
                AND h.matched_event_id=c.matched_event_id
            JOIN procurement_supplier_invoice_intake i
                ON i.id=c.supplier_invoice_id
                AND i.organization_id=c.organization_id
                AND i.purchase_order_id=c.purchase_order_id
                AND i.supplier_organization_id=c.supplier_organization_id
                AND i.evidence_id=c.invoice_evidence_id
                AND i.invoice_number=c.invoice_number
                AND i.currency=c.currency AND i.total_amount=c.gross_amount
            JOIN procurement_supplier_invoice_match_event m
                ON m.id=c.matched_event_id
                AND m.invoice_id=c.supplier_invoice_id
                AND m.purchase_order_id=c.purchase_order_id
                AND m.organization_id=c.organization_id
                AND m.revision=c.match_revision
                AND m.source_sha256=c.source_sha256
                AND m.result_code='MATCHED'
            JOIN procurement_purchase_order po
                ON po.id=c.purchase_order_id
                AND po.organization_id=c.organization_id
                AND po.supplier_organization_id=c.supplier_organization_id
            JOIN evidence e
                ON e.id=c.invoice_evidence_id
                AND e.organization_id=c.organization_id
                AND e.target_type='SUPPLIER_INVOICE_DOCUMENT'
                AND e.target_id=i.document_slot_id
                AND e.storage_state='READY'
                AND e.finalized_at IS NOT NULL
                AND e.evidence_requirement_key='SUPPLIER_INVOICE_BINARY'
                AND e.evidence_type_code='SUPPLIER_INVOICE'
                AND e.classification_code='HIGHLY_RESTRICTED'
                AND e.client_visibility_mode='INTERNAL_ONLY'
                AND e.object_key_ref IS NOT NULL
            JOIN evidence_upload_session session
                ON session.evidence_id=e.id
                AND session.state='READY'
                AND session.finalized_at IS NOT NULL
                AND session.expected_sha256=e.sha256
                AND session.expected_size_bytes=e.size_bytes
            JOIN organization company
                ON company.id=c.organization_id
                AND company.status='ACTIVE'
            WHERE c.id=? AND c.organization_id=?
                AND c.state='ACCOUNTING_REVIEW_REQUIRED'
                AND c.gross_amount>0
                AND m.revision=(
                    SELECT MAX(last_match.revision)
                    FROM procurement_supplier_invoice_match_event last_match
                    WHERE last_match.invoice_id=c.supplier_invoice_id
                )
            """.trimIndent(),
            { rs, _ ->
                SupplierAccountingSourceRow(
                    candidateId = rs.getObject("candidate_id", UUID::class.java),
                    organizationId = rs.getObject("organization_id", UUID::class.java),
                    supplierOrganizationId = rs.getObject(
                        "supplier_organization_id", UUID::class.java,
                    ),
                    supplierInvoiceId = rs.getObject("supplier_invoice_id", UUID::class.java),
                    invoiceEvidenceId = rs.getObject("invoice_evidence_id", UUID::class.java),
                    invoiceNumber = rs.getString("invoice_number"),
                    currency = rs.getString("currency"),
                    amount = rs.getBigDecimal("gross_amount"),
                    sourceSha256 = rs.getString("source_sha256"),
                    matchEventId = rs.getObject("matched_event_id", UUID::class.java),
                    matchRevision = rs.getLong("match_revision"),
                    purchaseOrderId = rs.getObject("purchase_order_id", UUID::class.java),
                    handoffId = rs.getObject("finance_handoff_id", UUID::class.java),
                    evidenceVersion = rs.getLong("evidence_version"),
                    evidenceSha256 = rs.getString("evidence_sha256"),
                    createdByUserId = rs.getObject("recorded_by_user_id", UUID::class.java),
                )
            },
            candidateId, org,
        )
        val row = rows.singleOrNull() ?: return null
        if (!approvals.isActiveOrganizationMember(org, row.createdByUserId, now) ||
            approvals.actorMatchesAuthority(row.createdByUserId, authority, now)) return null
        val fingerprint = listOf(
            "phase8-supplier-accounting-review-v1", org, row.candidateId,
            row.supplierOrganizationId, row.supplierInvoiceId, row.invoiceEvidenceId,
            row.invoiceNumber, row.currency,
            row.amount.stripTrailingZeros().toPlainString(), row.sourceSha256,
            row.matchEventId, row.matchRevision, row.purchaseOrderId, row.handoffId,
            row.evidenceVersion, row.evidenceSha256,
            key, policy.versionNumber, authority.id, authority.version,
        ).joinToString("|")
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(fingerprint.toByteArray(Charsets.UTF_8))
        val version = (ByteBuffer.wrap(hash, 0, 8).long and Long.MAX_VALUE)
            .coerceAtLeast(1L)
        return FinanceSupplierAccountingSnapshot(
            candidateId = row.candidateId,
            organizationId = row.organizationId,
            supplierOrganizationId = row.supplierOrganizationId,
            supplierInvoiceId = row.supplierInvoiceId,
            invoiceEvidenceId = row.invoiceEvidenceId,
            invoiceNumber = row.invoiceNumber,
            currency = row.currency,
            amount = row.amount,
            sourceSha256 = row.sourceSha256,
            sourceVersion = version,
            policyKey = key,
            policyVersion = policy.versionNumber,
        )
    }
}

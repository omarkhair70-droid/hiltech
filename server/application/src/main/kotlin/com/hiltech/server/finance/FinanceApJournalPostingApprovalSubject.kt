package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalPolicyMode
import com.hiltech.server.approval.ApprovalRequestState
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.approval.ApprovalSubjectVersionSource
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

const val FINANCE_AP_JOURNAL_POSTING_SUBJECT = "FINANCE_AP_JOURNAL_POSTING"

data class FinanceApPostingVerifiedSnapshot(
    val proposalId: UUID,
    val candidateId: UUID,
    val organizationId: UUID,
    val originalInvoiceSourceVersion: Long,
    val exactLedgerSourceVersion: Long,
    val ledgerFingerprintSha256: String,
    val documentReviewApprovalId: UUID,
    val postingPolicyKey: String,
    val postingPolicyVersion: Int,
    val requesterUserId: UUID,
    val periodId: UUID,
    val grossAmount: BigDecimal,
    val currency: String,
    val lines: List<FinanceApLedgerLine>,
)

/** Shared proposal/decision exact fingerprint; never a default approver. */
object FinanceApPostingReviewVersion {
    fun derive(
        checked: FinanceApLedgerValidation,
        policyVersion: Int,
        authorityId: UUID,
        authorityVersion: Long,
        documentReviewApprovalId: UUID,
    ): Long {
        require(policyVersion >= 1 && authorityVersion >= 1)
        val material = listOf(
            checked.fingerprintSha256, checked.approvalSubjectVersion,
            policyVersion, authorityId, authorityVersion, documentReviewApprovalId,
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
        return (ByteBuffer.wrap(digest, 0, 8).long and Long.MAX_VALUE)
            .coerceAtLeast(1L)
    }
}

private data class FinanceApProposalSourceRow(
    val proposalId: UUID,
    val org: UUID,
    val candidateId: UUID,
    val supplierInvoiceId: UUID,
    val supplierOrganizationId: UUID,
    val originalReviewApprovalId: UUID,
    val invoiceSourceSha256: String,
    val invoiceReviewSourceVersion: Long,
    val ledgerSourceVersion: Long,
    val ledgerFingerprintSha256: String,
    val periodId: UUID,
    val currency: String,
    val gross: BigDecimal,
    val accountingReference: String,
    val taxDisposition: String,
    val taxEvidenceId: UUID?,
    val policyKey: String,
    val createdBy: UUID,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val periodState: String,
    val line: FinanceApLedgerLine,
)

/**
 * ApprovalEngine revalidates the EXACT company reviewed AP journal lines,
 * current supplier match, approved original documentation, chart and posting
 * authority at decision time. A prior document approval is not posting auth.
 */
@Component
class FinanceApJournalPostingApprovalSubject(
    private val jdbc: JdbcTemplate,
    private val approvals: ApprovalPersistencePort,
    private val supplierReview: FinanceSupplierAccountingApprovalSubject,
    private val environment: Environment,
    private val clock: Clock,
) : ApprovalSubjectVersionSource {
    override fun supports(subjectType: String): Boolean =
        subjectType == FINANCE_AP_JOURNAL_POSTING_SUBJECT

    override fun isCurrent(subject: ApprovalSubjectRef): Boolean =
        supports(subject.subjectType) &&
            current(subject.organizationId, subject.subjectId)
                ?.exactLedgerSourceVersion == subject.subjectVersion

    fun current(org: UUID, proposalId: UUID): FinanceApPostingVerifiedSnapshot? {
        if (!enabled()) return null
        val key = policyKey() ?: return null
        if (jdbc.queryForObject(
                "SELECT to_regclass('public.finance_ap_posting_proposal') IS NOT NULL",
                Boolean::class.java,
            ) != true) return null
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
            SELECT p.id,p.organization_id,p.payable_candidate_id,
              p.supplier_invoice_id,p.supplier_organization_id,
              p.original_accounting_approval_id,p.invoice_source_sha256,
              p.invoice_review_source_version,p.ledger_subject_version,
              p.ledger_fingerprint_sha256,p.fiscal_period_id,p.currency,
              p.gross_amount,p.accounting_treatment_reference,
              p.accounting_tax_disposition,p.tax_evidence_id,p.posting_policy_key,
              p.created_by_user_id,
              period.date_start,period.date_end,period.state AS period_state,
              l.gl_account_id,l.side,l.amount,l.line_number,
              a.account_kind
            FROM finance_ap_posting_proposal p
            JOIN finance_gl_period period ON period.id=p.fiscal_period_id
              AND period.organization_id=p.organization_id
            JOIN finance_ap_posting_proposal_line l ON l.proposal_id=p.id
              AND l.organization_id=p.organization_id
            JOIN finance_gl_account a ON a.id=l.gl_account_id
              AND a.organization_id=p.organization_id
              AND a.state='APPROVED'
              AND (a.currency IS NULL OR a.currency=p.currency)
            WHERE p.organization_id=? AND p.id=? AND p.state='SEALED'
            ORDER BY l.line_number
            LIMIT 33
            """.trimIndent(),
            { rs, _ ->
                FinanceApProposalSourceRow(
                    proposalId = rs.getObject("id",UUID::class.java),
                    org = rs.getObject("organization_id",UUID::class.java),
                    candidateId = rs.getObject("payable_candidate_id",UUID::class.java),
                    supplierInvoiceId = rs.getObject("supplier_invoice_id",UUID::class.java),
                    supplierOrganizationId = rs.getObject(
                        "supplier_organization_id",UUID::class.java),
                    originalReviewApprovalId = rs.getObject(
                        "original_accounting_approval_id",UUID::class.java),
                    invoiceSourceSha256 = rs.getString("invoice_source_sha256"),
                    invoiceReviewSourceVersion = rs.getLong("invoice_review_source_version"),
                    ledgerSourceVersion = rs.getLong("ledger_subject_version"),
                    ledgerFingerprintSha256 = rs.getString("ledger_fingerprint_sha256"),
                    periodId = rs.getObject("fiscal_period_id",UUID::class.java),
                    currency = rs.getString("currency"),
                    gross = rs.getBigDecimal("gross_amount"),
                    accountingReference = rs.getString("accounting_treatment_reference"),
                    taxDisposition = rs.getString("accounting_tax_disposition"),
                    taxEvidenceId = rs.getObject("tax_evidence_id",UUID::class.java),
                    policyKey = rs.getString("posting_policy_key"),
                    createdBy = rs.getObject("created_by_user_id",UUID::class.java),
                    periodStart = rs.getDate("date_start").toLocalDate(),
                    periodEnd = rs.getDate("date_end").toLocalDate(),
                    periodState = rs.getString("period_state"),
                    line = FinanceApLedgerLine(
                        accountId = rs.getObject("gl_account_id",UUID::class.java),
                        accountKind = FinanceApAccountKind.valueOf(rs.getString("account_kind")),
                        side = FinanceJournalSide.valueOf(rs.getString("side")),
                        amount = rs.getBigDecimal("amount"),
                    ),
                )
            },
            org, proposalId,
        )
        if (rows.size !in 2..32) return null
        val first = rows.first()
        // Append-only period close invalidates old pending AP approvals,
        // even though the immutable base finance_gl_period.state stays OPEN.
        if (periodHasClose(org,first.periodId)) return null
        if (first.policyKey != key || first.org != org ||
            rows.any {
                it.proposalId != proposalId || it.candidateId != first.candidateId ||
                    it.ledgerSourceVersion != first.ledgerSourceVersion ||
                    it.ledgerFingerprintSha256 != first.ledgerFingerprintSha256
            } ||
            first.periodState != "OPEN" ||
            first.periodStart.isAfter(clock.instant().atZone(clock.zone).toLocalDate()) ||
            first.periodEnd.isBefore(clock.instant().atZone(clock.zone).toLocalDate()) ||
            !approvals.isActiveOrganizationMember(org, first.createdBy, now) ||
            approvals.actorMatchesAuthority(first.createdBy, authority, now)
        ) return null
        // Without a real Finance tax-treatment Evidence target, tax recovery
        // must remain closed. Never claim source invoice PDF proves recoverability.
        if (first.taxEvidenceId != null ||
            first.taxDisposition != "TAX_REVIEWED_NO_RECOVERABLE_TAX") return null
        val currentInvoice = supplierReview.current(org, first.candidateId) ?: return null
        if (currentInvoice.supplierInvoiceId != first.supplierInvoiceId ||
            currentInvoice.supplierOrganizationId != first.supplierOrganizationId ||
            currentInvoice.sourceSha256 != first.invoiceSourceSha256 ||
            currentInvoice.sourceVersion != first.invoiceReviewSourceVersion ||
            currentInvoice.amount.compareTo(first.gross) != 0 ||
            currentInvoice.currency != first.currency) return null

        val original = approvals.loadSubjectApproval(
            ApprovalSubjectRef(
                org, FINANCE_SUPPLIER_ACCOUNTING_REVIEW_SUBJECT,
                first.candidateId, first.invoiceReviewSourceVersion,
            ),
            currentInvoice.policyKey,
        )
        if (original?.requestId != first.originalReviewApprovalId ||
            original.state != ApprovalRequestState.APPROVED) return null

        val plan = FinanceApLedgerPlan(
            organizationId = org,
            candidateId = first.candidateId,
            sourceSha256 = first.invoiceSourceSha256,
            sourceVersion = first.invoiceReviewSourceVersion,
            fiscalPeriodId = first.periodId,
            grossAmount = first.gross,
            currency = first.currency,
            accountingTreatmentReference = first.accountingReference,
            accountingTaxDisposition = first.taxDisposition,
            taxEvidenceId = first.taxEvidenceId,
            lines = rows.map { it.line },
        )
        val checked = runCatching { FinanceApLedgerRules.validate(plan) }
            .getOrNull() ?: return null
        if (checked.fingerprintSha256 != first.ledgerFingerprintSha256) return null
        val finalVersion = FinanceApPostingReviewVersion.derive(
            checked, policy.versionNumber, authority.id, authority.version,
            original.requestId,
        )
        if (finalVersion != first.ledgerSourceVersion) return null
        return FinanceApPostingVerifiedSnapshot(
            proposalId = proposalId,
            candidateId = first.candidateId,
            organizationId = org,
            originalInvoiceSourceVersion = currentInvoice.sourceVersion,
            exactLedgerSourceVersion = finalVersion,
            ledgerFingerprintSha256 = checked.fingerprintSha256,
            documentReviewApprovalId = original.requestId,
            postingPolicyKey = key,
            postingPolicyVersion = policy.versionNumber,
            requesterUserId = first.createdBy,
            periodId = first.periodId,
            grossAmount = first.gross,
            currency = first.currency,
            lines = rows.map { it.line },
        )
    }

    private fun periodHasClose(org: UUID, periodId: UUID): Boolean {
        if (jdbc.queryForObject(
                "SELECT to_regclass('public.finance_gl_period_close') IS NOT NULL",
                Boolean::class.java,
            ) != true) return false
        return jdbc.queryForObject(
            """
            SELECT EXISTS(
                SELECT 1 FROM public.finance_gl_period_close
                WHERE organization_id=? AND period_id=?
            )
            """.trimIndent(),Boolean::class.java,org,periodId,
        ) == true
    }

    fun policyKey(): String? = environment.getProperty(
        "hiltech.finance.ap-posting.policy-key","",
    ).trim().takeIf { Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(it) }

    fun enabled(): Boolean = environment.getProperty(
        "hiltech.finance.ap-posting.enabled",Boolean::class.java,false,
    )
}

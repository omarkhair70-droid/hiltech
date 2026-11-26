package com.hiltech.server.finance

import com.hiltech.server.approval.CompanyReviewPolicyKeys

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
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

const val FINANCE_AR_JOURNAL_POSTING_SUBJECT="FINANCE_AR_JOURNAL_POSTING"

object FinanceArPostingReviewVersion {
    fun derive(validated:FinanceArJournalValidation,policyVersion:Int,
               reviewerBindingId:UUID,reviewerVersion:Long,
               originalInvoiceApprovalId:UUID):Long {
        require(policyVersion>0 && reviewerVersion>0)
        val payload=listOf(
            "FINANCE_AR_POSTING_REVIEW_V1",validated.fingerprintSha256,
            validated.approvalSubjectVersion,policyVersion,
            reviewerBindingId,reviewerVersion,originalInvoiceApprovalId,
        ).joinToString("|")
        val d=MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
        return (ByteBuffer.wrap(d,0,8).long and Long.MAX_VALUE).coerceAtLeast(1)
    }
}
data class FinanceArPostingVerifiedSource(
    val proposalId:UUID,val organizationId:UUID,
    val issuedInvoiceId:UUID,val invoiceApprovalId:UUID,
    val periodId:UUID,val amount:BigDecimal,val currency:String,
    val sourceHash:String,val ledgerHash:String,val subjectVersion:Long,
    val policyKey:String,val policyVersion:Int,val preparedBy:UUID,
    val lines:List<FinanceArJournalAllocation>,
)
private data class FinanceArPostingJoinedRow(
    val proposalId:UUID,val invoiceId:UUID,val invoiceApprovalId:UUID,
    val periodId:UUID,val sourceHash:String,val ledgerHash:String,
    val version:Long,val currency:String,val amount:BigDecimal,
    val accountingReference:String,val taxDisposition:String,val taxEvidenceId:UUID?,
    val policyKey:String,val preparedBy:UUID,val periodState:String,
    val periodStart:LocalDate,val periodEnd:LocalDate,
    val invoiceCurrency:String,val invoiceAmount:BigDecimal,
    val invoiceHash:String,
    val line:FinanceArJournalAllocation,
)

/** ApprovalEngine exact-current source for AR journal recognition. */
@Component
class FinanceArJournalPostingApprovalSubject(
    private val jdbc:JdbcTemplate,
    private val approvals:ApprovalPersistencePort,
    private val environment:Environment,
    private val clock:Clock,
):ApprovalSubjectVersionSource {
    override fun supports(subjectType:String):Boolean =
        subjectType==FINANCE_AR_JOURNAL_POSTING_SUBJECT
    override fun isCurrent(subject:ApprovalSubjectRef):Boolean =
        supports(subject.subjectType) &&
            current(subject.organizationId,subject.subjectId)
                ?.subjectVersion==subject.subjectVersion
    fun enabled():Boolean=environment.getProperty(
        "hiltech.finance.receivables.ar-posting.enabled",
        Boolean::class.java,false,
    )
    fun policyKey():String?=CompanyReviewPolicyKeys.resolve(environment, "hiltech.finance.receivables.ar-posting.policy-key").takeIf { Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(it) }
    fun current(org:UUID,id:UUID):FinanceArPostingVerifiedSource? {
        if(!enabled()) return null
        val key=policyKey() ?: return null
        val now=clock.instant()
        val policy=approvals.loadActivePolicy(org,key,now)?.takeIf {
            it.organizationId==org && it.policyKey==key &&
                it.approvalRequired && it.mode==ApprovalPolicyMode.SINGLE &&
                !it.authorityKey.isNullOrBlank()
        } ?: return null
        val reviewer=approvals.resolveCurrentAuthority(
            org,policy.authorityKey!!,now,
        ) ?: return null
        if(reviewer.organizationId!=org) return null
        val rows=jdbc.query(
            """
            SELECT p.id,p.issued_invoice_id,p.original_issuance_approval_id,
              p.source_fingerprint_sha256,p.ledger_fingerprint_sha256,
              p.ledger_subject_version,p.fiscal_period_id,p.currency,
              p.gross_amount,p.accounting_treatment_reference,
              p.tax_disposition_reference,p.tax_evidence_id,
              p.policy_key,p.created_by_user_id,
              period.state AS period_state,period.date_start,period.date_end,
              i.currency AS invoice_currency,i.gross_amount AS invoice_gross,
              i.source_fingerprint_sha256 AS invoice_hash,
              l.gl_account_id,l.side,l.amount AS line_amount,
              a.account_kind
            FROM finance_ar_posting_proposal p
            JOIN finance_gl_period period
              ON period.id=p.fiscal_period_id AND period.organization_id=p.organization_id
            JOIN finance_ar_issued_invoice i
              ON i.id=p.issued_invoice_id AND i.organization_id=p.organization_id
            JOIN finance_ar_posting_proposal_line l
              ON l.proposal_id=p.id AND l.organization_id=p.organization_id
            JOIN finance_gl_account a
              ON a.id=l.gl_account_id AND a.organization_id=l.organization_id
              AND a.state='APPROVED'
              AND (a.currency IS NULL OR a.currency=p.currency)
            WHERE p.id=? AND p.organization_id=? AND p.state='SEALED'
            ORDER BY l.line_number LIMIT 33
            """.trimIndent(),
            {rs,_ ->FinanceArPostingJoinedRow(
                proposalId=rs.getObject("id",UUID::class.java),
                invoiceId=rs.getObject("issued_invoice_id",UUID::class.java),
                invoiceApprovalId=rs.getObject("original_issuance_approval_id",UUID::class.java),
                periodId=rs.getObject("fiscal_period_id",UUID::class.java),
                sourceHash=rs.getString("source_fingerprint_sha256"),
                ledgerHash=rs.getString("ledger_fingerprint_sha256"),
                version=rs.getLong("ledger_subject_version"),
                currency=rs.getString("currency"),amount=rs.getBigDecimal("gross_amount"),
                accountingReference=rs.getString("accounting_treatment_reference"),
                taxDisposition=rs.getString("tax_disposition_reference"),
                taxEvidenceId=rs.getObject("tax_evidence_id",UUID::class.java),
                policyKey=rs.getString("policy_key"),
                preparedBy=rs.getObject("created_by_user_id",UUID::class.java),
                periodState=rs.getString("period_state"),
                periodStart=rs.getDate("date_start").toLocalDate(),
                periodEnd=rs.getDate("date_end").toLocalDate(),
                invoiceCurrency=rs.getString("invoice_currency"),
                invoiceAmount=rs.getBigDecimal("invoice_gross"),
                invoiceHash=rs.getString("invoice_hash"),
                line=FinanceArJournalAllocation(
                    rs.getObject("gl_account_id",UUID::class.java),
                    FinanceArAccountKind.valueOf(rs.getString("account_kind")),
                    FinanceJournalSide.valueOf(rs.getString("side")),
                    rs.getBigDecimal("line_amount"),
                ),
            )},id,org,
        )
        if(rows.size !in 2..32) return null
        val h=rows.first()
        val date=LocalDate.ofInstant(now,ZoneOffset.UTC)
        if(h.proposalId!=id || h.policyKey!=key || h.periodState!="OPEN" ||
            date.isBefore(h.periodStart) || date.isAfter(h.periodEnd) ||
            h.invoiceCurrency!=h.currency ||
            h.invoiceAmount.compareTo(h.amount)!=0 ||
            h.invoiceHash!=h.sourceHash ||
            rows.any { it.proposalId!=h.proposalId ||
                it.version!=h.version || it.ledgerHash!=h.ledgerHash } ||
            !approvals.isActiveOrganizationMember(org,h.preparedBy,now) ||
            approvals.actorMatchesAuthority(h.preparedBy,reviewer,now))
            return null
        val closed=jdbc.queryForObject(
            """SELECT EXISTS(SELECT 1 FROM finance_gl_period_close
               WHERE organization_id=? AND period_id=?)""",
            Boolean::class.java,org,h.periodId,
        )==true
        val posted=jdbc.queryForObject(
            """SELECT EXISTS(SELECT 1 FROM finance_ar_posted_journal
               WHERE organization_id=? AND issued_invoice_id=?)""",
            Boolean::class.java,org,h.invoiceId,
        )==true
        if(closed || posted) return null
        val plan=FinanceArJournalPlan(
            org,h.invoiceId,h.sourceHash,h.periodId,h.amount,h.currency,
            h.accountingReference,h.taxDisposition,h.taxEvidenceId,
            rows.map { it.line },
        )
        val checked=runCatching { FinanceArJournalRules.validate(plan) }
            .getOrNull() ?: return null
        if(checked.fingerprintSha256!=h.ledgerHash) return null
        val version=FinanceArPostingReviewVersion.derive(
            checked,policy.versionNumber,reviewer.id,reviewer.version,
            h.invoiceApprovalId,
        )
        if(version!=h.version) return null
        return FinanceArPostingVerifiedSource(
            id,org,h.invoiceId,h.invoiceApprovalId,h.periodId,h.amount,
            h.currency,h.sourceHash,h.ledgerHash,version,key,
            policy.versionNumber,h.preparedBy,rows.map{it.line},
        )
    }
}

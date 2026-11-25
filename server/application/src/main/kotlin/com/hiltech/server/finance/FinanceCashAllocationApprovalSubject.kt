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

const val FINANCE_AP_AR_CASH_ALLOCATION_SUBJECT="FINANCE_AP_AR_CASH_ALLOCATION"

data class FinanceCashVerifiedProposal(
    val proposalId: UUID,
    val organizationId: UUID,
    val sourceKind: String,
    val sourceJournalId: UUID,
    val bankStatementLineId: UUID,
    val sourceSha256: String,
    val bankStatementSha256: String,
    val currency: String,
    val sourceAmount: BigDecimal,
    val bankAmount: BigDecimal,
    val allocationAmount: BigDecimal,
    val subjectVersion: Long,
    val policyKey: String,
    val policyVersion: Int,
    val preparedBy: UUID,
)
object FinanceCashReviewVersion {
    fun derive(
        organization: UUID,kind: String,sourceJournal: UUID,
        bankLine: UUID,sourceHash: String,bankHash: String,
        currency: String,allocated: BigDecimal,
        reason: String,policyVersion: Int,
        reviewerBindingId: UUID,reviewerBindingVersion: Long,
    ): Long {
        require(policyVersion>0 && reviewerBindingVersion>0)
        val material=listOf(
            "FINANCE_CASH_ALLOCATION_REVIEW_V1",organization,kind,
            sourceJournal,bankLine,sourceHash,bankHash,currency,
            allocated.stripTrailingZeros().toPlainString(),reason.trim(),
            policyVersion,reviewerBindingId,reviewerBindingVersion,
        ).joinToString("|")
        val bytes=MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
        return (ByteBuffer.wrap(bytes,0,8).long and Long.MAX_VALUE).coerceAtLeast(1L)
    }
}
private data class FinanceCashJoined(
    val proposalId: UUID,val kind: String,val journalId: UUID,
    val bankLineId: UUID,val sourceHash: String,val bankHash: String,
    val currency: String,val amount: BigDecimal,val version: Long,
    val policyKey: String,val reason: String,val preparedBy: UUID,
    val bankAmount: BigDecimal,val bankCurrency: String,val bankFlow: String,
    val statementSha: String,
)

/** ApprovalEngine re-reads each exact source and current available balance. */
@Component
class FinanceCashAllocationApprovalSubject(
    private val jdbc: JdbcTemplate,
    private val approvals: ApprovalPersistencePort,
    private val environment: Environment,
    private val clock: Clock,
): ApprovalSubjectVersionSource {
    override fun supports(subjectType: String): Boolean =
        subjectType==FINANCE_AP_AR_CASH_ALLOCATION_SUBJECT
    override fun isCurrent(subject: ApprovalSubjectRef): Boolean =
        supports(subject.subjectType) &&
            current(subject.organizationId,subject.subjectId)
                ?.subjectVersion==subject.subjectVersion
    fun enabled(): Boolean = environment.getProperty(
        "hiltech.finance.cash-allocation.enabled",Boolean::class.java,false,
    )
    fun policyKey(): String? = environment.getProperty(
        "hiltech.finance.cash-allocation.policy-key","",
    ).trim().takeIf{Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(it)}
    fun current(org: UUID,id: UUID): FinanceCashVerifiedProposal? {
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
        val p=jdbc.query(
            """
            SELECT p.id,p.source_kind,p.source_journal_id,
              p.bank_statement_line_id,p.source_journal_sha256,
              p.bank_statement_sha256,p.currency,p.allocated_amount,
              p.approval_subject_version,p.policy_key,p.allocation_reason,
              p.prepared_by_user_id,b.amount AS bank_amount,
              b.currency AS bank_currency,b.flow AS bank_flow,
              h.statement_sha256 AS statement_sha
            FROM finance_cash_allocation_proposal p
            JOIN finance_bank_statement_intake_line b
              ON b.id=p.bank_statement_line_id AND b.organization_id=p.organization_id
            JOIN finance_bank_statement_intake h
              ON h.id=b.intake_id AND h.organization_id=b.organization_id
            WHERE p.id=? AND p.organization_id=?
            """.trimIndent(),
            {rs,_ ->FinanceCashJoined(
                rs.getObject("id",UUID::class.java),
                rs.getString("source_kind"),
                rs.getObject("source_journal_id",UUID::class.java),
                rs.getObject("bank_statement_line_id",UUID::class.java),
                rs.getString("source_journal_sha256"),
                rs.getString("bank_statement_sha256"),rs.getString("currency"),
                rs.getBigDecimal("allocated_amount"),
                rs.getLong("approval_subject_version"),
                rs.getString("policy_key"),rs.getString("allocation_reason"),
                rs.getObject("prepared_by_user_id",UUID::class.java),
                rs.getBigDecimal("bank_amount"),rs.getString("bank_currency"),
                rs.getString("bank_flow"),rs.getString("statement_sha"),
            )},id,org,
        ).singleOrNull() ?: return null
        if(p.policyKey!=key || p.currency!=p.bankCurrency ||
            p.bankHash!=p.statementSha ||
            (p.kind=="AR" && p.bankFlow!="CREDIT") ||
            (p.kind=="AP" && p.bankFlow!="DEBIT") ||
            !approvals.isActiveOrganizationMember(org,p.preparedBy,now) ||
            approvals.actorMatchesAuthority(p.preparedBy,reviewer,now))
            return null
        val table=when(p.kind){
            "AP"->"finance_ap_posted_journal"
            "AR"->"finance_ar_posted_journal"
            else->return null
        }
        val posted=jdbc.query(
            """SELECT gross_amount,currency,ledger_fingerprint_sha256
               FROM $table WHERE id=? AND organization_id=?""",
            {rs,_ ->Triple(rs.getBigDecimal("gross_amount"),
                rs.getString("currency"),
                rs.getString("ledger_fingerprint_sha256"))},
            p.journalId,org,
        ).singleOrNull() ?: return null
        if(posted.second!=p.currency || posted.third!=p.sourceHash) return null
        if(p.kind=="AP" && jdbc.queryForObject(
            """SELECT EXISTS(SELECT 1 FROM finance_ap_journal_reversal
               WHERE organization_id=? AND original_journal_id=?)""",
            Boolean::class.java,org,p.journalId,
        )==true)return null
        val sums=jdbc.queryForList(
            """SELECT source_kind,source_journal_id,bank_statement_line_id,
               allocated_amount FROM finance_cash_allocation_effective
               WHERE organization_id=? AND
                 ((source_kind=? AND source_journal_id=?) OR bank_statement_line_id=?)""",
            org,p.kind,p.journalId,p.bankLineId,
        )
        val usedSource=sums.filter {
            it["source_kind"]==p.kind && it["source_journal_id"]==p.journalId
        }.fold(BigDecimal.ZERO){a,row->a.add(row["allocated_amount"] as BigDecimal)}
        val usedBank=jdbc.queryForObject(
            "SELECT coalesce(sum(amount),0) FROM finance_bank_committed_usage WHERE organization_id=? AND bank_statement_line_id=?",
            BigDecimal::class.java,org,p.bankLineId,
        ) ?: BigDecimal.ZERO
        // A posted customer/supplier invoice can be settled partly in a
        // company cashbox and partly by bank: never advertise the same
        // remaining invoice balance twice.
        val cashboxInstalled=jdbc.queryForObject(
            "SELECT to_regclass('public.finance_cash_voucher_record') IS NOT NULL",
            Boolean::class.java,
        )==true
        val usedPhysical=if(cashboxInstalled) jdbc.queryForObject(
            """SELECT COALESCE(SUM(amount),0)
               FROM finance_cash_voucher_record
               WHERE organization_id=? AND source_kind=? AND source_journal_id=?""",
            BigDecimal::class.java,org,p.kind,p.journalId,
        ) ?: BigDecimal.ZERO else BigDecimal.ZERO
        if(p.amount<=BigDecimal.ZERO ||
            usedSource.add(usedPhysical).add(p.amount)>posted.first ||
            usedBank.add(p.amount)>p.bankAmount)return null
        val version=FinanceCashReviewVersion.derive(
            org,p.kind,p.journalId,p.bankLineId,p.sourceHash,p.bankHash,
            p.currency,p.amount,p.reason,
            policy.versionNumber,reviewer.id,reviewer.version,
        )
        if(version!=p.version)return null
        return FinanceCashVerifiedProposal(
            proposalId=id,organizationId=org,
            sourceKind=p.kind,sourceJournalId=p.journalId,
            bankStatementLineId=p.bankLineId,
            sourceSha256=p.sourceHash,bankStatementSha256=p.bankHash,
            currency=p.currency,sourceAmount=posted.first,
            bankAmount=p.bankAmount,allocationAmount=p.amount,
            subjectVersion=version,policyKey=key,
            policyVersion=policy.versionNumber,preparedBy=p.preparedBy,
        )
    }
}

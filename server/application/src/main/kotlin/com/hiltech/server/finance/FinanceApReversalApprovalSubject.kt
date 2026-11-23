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
import java.time.LocalDate
import java.util.UUID

const val FINANCE_AP_JOURNAL_REVERSAL_SUBJECT = "FINANCE_AP_JOURNAL_REVERSAL"

data class FinanceApReversalVerifiedSnapshot(
    val proposalId: UUID,
    val originalJournalId: UUID,
    val organizationId: UUID,
    val targetPeriodId: UUID,
    val currency: String,
    val grossAmount: BigDecimal,
    val sourceFingerprint: String,
    val subjectVersion: Long,
    val policyKey: String,
    val policyVersion: Int,
    val preparedBy: UUID,
    val reasonReference: String,
    val lines: List<FinanceApReversalSourceLine>,
)

/** Deterministic, exact-input version used by proposal and ApprovalEngine. */
object FinanceApReversalReviewVersion {
    fun derive(
        org: UUID, original: UUID, targetPeriod: UUID,
        sourceHash: String, currency: String, amount: BigDecimal,
        reason: String, originalLines: List<FinanceApReversalSourceLine>,
        policyVersion: Int, authorityId: UUID, authorityVersion: Long,
    ): Long {
        require(policyVersion>0 && authorityVersion>0)
        val material=listOf(
            "FINANCE_AP_REVERSAL_V1",org,original,targetPeriod,
            sourceHash,currency,amount.toPlainString(),reason.trim(),
            policyVersion,authorityId,authorityVersion,
            originalLines.joinToString(";") {
                listOf(it.sourceLineId,it.glAccountId,it.side.name,
                    it.amount.toPlainString()).joinToString(":")
            },
        ).joinToString("|")
        val digest=MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
        return (ByteBuffer.wrap(digest,0,8).long and Long.MAX_VALUE).coerceAtLeast(1)
    }
}

/**
 * ApprovalEngine must re-read this exact immutable accounting proposal
 * on every decision. Missing company policy/authority => fail closed.
 */
@Component
class FinanceApReversalApprovalSubject(
    private val jdbc: JdbcTemplate,
    private val approvals: ApprovalPersistencePort,
    private val environment: Environment,
    private val clock: Clock,
) : ApprovalSubjectVersionSource {
    override fun supports(subjectType: String): Boolean =
        subjectType==FINANCE_AP_JOURNAL_REVERSAL_SUBJECT

    override fun isCurrent(subject: ApprovalSubjectRef): Boolean =
        supports(subject.subjectType) &&
            current(subject.organizationId,subject.subjectId)
                ?.subjectVersion==subject.subjectVersion

    fun enabled(): Boolean = environment.getProperty(
        "hiltech.finance.ap-reversal.enabled",Boolean::class.java,false,
    )

    fun policyKey(): String? = environment.getProperty(
        "hiltech.finance.ap-reversal.policy-key","",
    ).trim().takeIf { Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(it) }

    fun current(org: UUID, id: UUID): FinanceApReversalVerifiedSnapshot? {
        if(!enabled()) return null
        val key=policyKey() ?: return null
        if(jdbc.queryForObject(
            "SELECT to_regclass('public.finance_ap_reversal_proposal') IS NOT NULL",
            Boolean::class.java,
        )!=true) return null
        val now=clock.instant()
        val policy=approvals.loadActivePolicy(org,key,now)?.takeIf {
            it.organizationId==org && it.policyKey==key &&
                it.approvalRequired && it.mode==ApprovalPolicyMode.SINGLE &&
                !it.authorityKey.isNullOrBlank()
        } ?: return null
        val authority=approvals.resolveCurrentAuthority(
            org,policy.authorityKey!!,now,
        ) ?: return null
        if(authority.organizationId!=org ||
            authority.authorityKey!=policy.authorityKey) return null
        val proposal=jdbc.query(
            """
            SELECT p.original_journal_id,p.target_period_id,p.currency,
              p.gross_amount,p.source_ledger_fingerprint_sha256,
              p.approval_subject_version,p.approval_policy_key,
              p.reason_reference,p.prepared_by_user_id,
              j.posted_at,j.currency AS original_currency,
              j.gross_amount AS original_gross,
              j.ledger_fingerprint_sha256 AS original_fingerprint,
              period.date_start,period.date_end,period.state AS period_state
            FROM finance_ap_reversal_proposal p
            JOIN finance_ap_posted_journal j
              ON j.id=p.original_journal_id AND j.organization_id=p.organization_id
            JOIN finance_gl_period period
              ON period.id=p.target_period_id AND period.organization_id=p.organization_id
            WHERE p.id=? AND p.organization_id=?
            """.trimIndent(),
            { rs,_ ->
                FinanceApReversalProposalState(
                    original=rs.getObject("original_journal_id",UUID::class.java),
                    period=rs.getObject("target_period_id",UUID::class.java),
                    currency=rs.getString("currency"),
                    gross=rs.getBigDecimal("gross_amount"),
                    sourceHash=rs.getString("source_ledger_fingerprint_sha256"),
                    version=rs.getLong("approval_subject_version"),
                    policyKey=rs.getString("approval_policy_key"),
                    reason=rs.getString("reason_reference"),
                    preparer=rs.getObject("prepared_by_user_id",UUID::class.java),
                    postedDate=rs.getObject(
                        "posted_at",java.time.OffsetDateTime::class.java,
                    ).toLocalDate(),
                    originalCurrency=rs.getString("original_currency"),
                    originalGross=rs.getBigDecimal("original_gross"),
                    originalHash=rs.getString("original_fingerprint"),
                    start=rs.getDate("date_start").toLocalDate(),
                    end=rs.getDate("date_end").toLocalDate(),
                    state=rs.getString("period_state"),
                )
            },id,org,
        ).singleOrNull() ?: return null
        if(proposal.policyKey!=key || proposal.state!="OPEN" ||
            proposal.currency!=proposal.originalCurrency ||
            proposal.gross.compareTo(proposal.originalGross)!=0 ||
            proposal.sourceHash!=proposal.originalHash ||
            proposal.start.isAfter(LocalDate.ofInstant(now,java.time.ZoneOffset.UTC)) ||
            proposal.end.isBefore(LocalDate.ofInstant(now,java.time.ZoneOffset.UTC)) ||
            !approvals.isActiveOrganizationMember(org,proposal.preparer,now) ||
            approvals.actorMatchesAuthority(proposal.preparer,authority,now)) return null
        val closed=jdbc.queryForObject(
            """SELECT EXISTS(SELECT 1 FROM finance_gl_period_close
               WHERE organization_id=? AND period_id=?)""",
            Boolean::class.java,org,proposal.period,
        )==true
        val already=jdbc.queryForObject(
            """SELECT EXISTS(SELECT 1 FROM finance_ap_journal_reversal
               WHERE organization_id=? AND original_journal_id=?)""",
            Boolean::class.java,org,proposal.original,
        )==true
        val originalClosed=jdbc.queryForObject(
            """SELECT EXISTS(
                 SELECT 1 FROM finance_gl_period_close c
                 JOIN finance_ap_posted_journal j ON j.fiscal_period_id=c.period_id
                   AND j.organization_id=c.organization_id
                 WHERE j.organization_id=? AND j.id=?
               )""",Boolean::class.java,org,proposal.original,
        )==true
        val lines=jdbc.query(
            """SELECT id,gl_account_id,side,amount
               FROM finance_ap_posted_journal_line
               WHERE journal_id=? AND organization_id=? ORDER BY line_number""",
            { rs,_ -> FinanceApReversalSourceLine(
                rs.getObject("id",UUID::class.java),
                rs.getObject("gl_account_id",UUID::class.java),
                FinanceJournalSide.valueOf(rs.getString("side")),
                rs.getBigDecimal("amount"),
            ) },proposal.original,org,
        )
        val target=FinanceApReversalTarget(
            organizationId=org,fiscalPeriodId=proposal.period,
            currency=proposal.currency,postingDate=LocalDate.ofInstant(
                now,java.time.ZoneOffset.UTC,
            ),periodOpen=true,periodClosedByEvent=closed,
        )
        val source=FinanceApReversalSnapshot(
            organizationId=org,sourceJournalId=proposal.original,
            sourceCurrency=proposal.currency,sourceGross=proposal.gross,
            sourcePostedDate=proposal.postedDate,sourceLines=lines,
            sourcePeriodClosed=originalClosed,alreadyReversed=already,
        )
        if(runCatching { FinanceApReversalRules.plan(source,target) }.isFailure)
            return null
        val version=FinanceApReversalReviewVersion.derive(
            org,proposal.original,proposal.period,proposal.sourceHash,
            proposal.currency,proposal.gross,proposal.reason,lines,
            policy.versionNumber,authority.id,authority.version,
        )
        if(version!=proposal.version) return null
        return FinanceApReversalVerifiedSnapshot(
            proposalId=id,originalJournalId=proposal.original,
            organizationId=org,targetPeriodId=proposal.period,
            currency=proposal.currency,grossAmount=proposal.gross,
            sourceFingerprint=proposal.sourceHash,
            subjectVersion=version,policyKey=key,
            policyVersion=policy.versionNumber,
            preparedBy=proposal.preparer,reasonReference=proposal.reason,
            lines=lines,
        )
    }
}
private data class FinanceApReversalProposalState(
    val original:UUID,val period:UUID,val currency:String,val gross:BigDecimal,
    val sourceHash:String,val version:Long,val policyKey:String,
    val reason:String,val preparer:UUID,val postedDate:LocalDate,
    val originalCurrency:String,val originalGross:BigDecimal,val originalHash:String,
    val start:LocalDate,val end:LocalDate,val state:String,
)

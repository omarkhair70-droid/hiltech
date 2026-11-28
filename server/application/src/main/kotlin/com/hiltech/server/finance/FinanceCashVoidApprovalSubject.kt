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
import java.util.UUID

const val FINANCE_CASH_ALLOCATION_VOID_SUBJECT="FINANCE_CASH_ALLOCATION_VOID"

object FinanceCashVoidReviewVersion {
    fun derive(
        org:UUID,original:UUID,kind:String,source:UUID,bank:UUID,
        sourceSha:String,bankSha:String,amount:BigDecimal,currency:String,
        reason:String,policyVersion:Int,reviewer:UUID,reviewerVersion:Long,
    ):Long {
        require(policyVersion>0 && reviewerVersion>0)
        val material=listOf(
            "FINANCE_CASH_ALLOCATION_VOID_V1",org,original,kind,source,bank,
            sourceSha,bankSha,amount.stripTrailingZeros().toPlainString(),
            currency,reason.trim(),policyVersion,reviewer,reviewerVersion,
        ).joinToString("|")
        val hash=MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
        return (ByteBuffer.wrap(hash,0,8).long and Long.MAX_VALUE).coerceAtLeast(1L)
    }
}

data class FinanceCashVoidVerified(
    val id:UUID,val org:UUID,val originalId:UUID,val kind:String,
    val journalId:UUID,val bankLineId:UUID,val sourceHash:String,
    val bankHash:String,val amount:BigDecimal,val currency:String,
    val reason:String,val reviewVersion:Long,val policyKey:String,
    val policyVersion:Int,val preparedBy:UUID,
    val originalPreparedBy:UUID,val originalRecordedBy:UUID,
)

data class FinanceCashVoidSource(
    val originalId:UUID,val kind:String,val journalId:UUID,
    val bankLineId:UUID,val amount:BigDecimal,val currency:String,
    val originalPreparedBy:UUID,val originalRecordedBy:UUID,
    val sourceHash:String,val bankHash:String,val periodId:UUID,
    val periodOpen:Boolean,
)

private data class FinanceCashVoidProposalRow(
    val id:UUID,val originalId:UUID,val kind:String,val journal:UUID,
    val bank:UUID,val amount:BigDecimal,val currency:String,
    val sourceHash:String,val bankHash:String,val version:Long,
    val key:String,val reason:String,val preparer:UUID,
)

@Component
class FinanceCashVoidApprovalSubject(
    private val jdbc:JdbcTemplate,
    private val approvals:ApprovalPersistencePort,
    private val environment:Environment,
    private val clock:Clock,
):ApprovalSubjectVersionSource {
    override fun supports(subjectType:String):Boolean =
        subjectType==FINANCE_CASH_ALLOCATION_VOID_SUBJECT
    override fun isCurrent(subject:ApprovalSubjectRef):Boolean=
        supports(subject.subjectType) &&
            current(subject.organizationId,subject.subjectId)
                ?.reviewVersion==subject.subjectVersion

    fun enabled()=environment.getProperty(
        "hiltech.finance.cash-allocation-void.enabled",Boolean::class.java,false)
    fun policyKey():String?=CompanyReviewPolicyKeys.resolve(environment, "hiltech.finance.cash-allocation-void.policy-key").takeIf{Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(it)}

    /** Company org, immutable original, no closed-period financial changes. */
    fun original(org:UUID,id:UUID):FinanceCashVoidSource? {
        val raw=jdbc.query(
            """
            SELECT r.id,r.source_kind,r.source_journal_id,
                r.bank_statement_line_id,r.allocated_amount,r.currency,
                p.prepared_by_user_id,r.recorded_by_user_id,
                h.statement_sha256,l.currency AS bank_currency,l.flow
            FROM finance_cash_allocation_record r
            JOIN finance_cash_allocation_proposal p
              ON p.id=r.proposal_id AND p.organization_id=r.organization_id
            JOIN finance_bank_statement_intake_line l
              ON l.id=r.bank_statement_line_id AND l.organization_id=r.organization_id
            JOIN finance_bank_statement_intake h
              ON h.id=l.intake_id AND h.organization_id=r.organization_id
            WHERE r.id=? AND r.organization_id=?
              AND NOT EXISTS(
                SELECT 1 FROM finance_cash_allocation_void_record v
                WHERE v.organization_id=r.organization_id AND v.original_record_id=r.id)
            """.trimIndent(),
            {rs,_ -> Triple(
                FinanceCashVoidSource(
                    originalId=rs.getObject("id",UUID::class.java),
                    kind=rs.getString("source_kind"),
                    journalId=rs.getObject("source_journal_id",UUID::class.java),
                    bankLineId=rs.getObject("bank_statement_line_id",UUID::class.java),
                    amount=rs.getBigDecimal("allocated_amount"),
                    currency=rs.getString("currency"),
                    originalPreparedBy=rs.getObject("prepared_by_user_id",UUID::class.java),
                    originalRecordedBy=rs.getObject("recorded_by_user_id",UUID::class.java),
                    sourceHash="",bankHash=rs.getString("statement_sha256"),
                    periodId=UUID(0,0),periodOpen=false,
                ),rs.getString("bank_currency"),rs.getString("flow")
            )},id,org,
        ).singleOrNull() ?: return null
        val r=raw.first
        if(raw.second!=r.currency ||
            (r.kind=="AP" && raw.third!="DEBIT") ||
            (r.kind=="AR" && raw.third!="CREDIT"))return null
        if(r.kind !in setOf("AP","AR"))return null
        val journal=jdbc.query(
            """SELECT fiscal_period_id,ledger_fingerprint_sha256,gross_amount,currency
               FROM finance_obligation_source WHERE id=? AND organization_id=? AND source_kind=?""",
            {rs,_ ->listOf(
                rs.getObject("fiscal_period_id",UUID::class.java),
                rs.getString("ledger_fingerprint_sha256"),
                rs.getBigDecimal("gross_amount"),
                rs.getString("currency"),
            )},r.journalId,org,r.kind,
        ).singleOrNull() ?: return null
        val periodId=journal[0] as UUID
        val periodOpen=jdbc.queryForObject(
            """SELECT EXISTS(
                SELECT 1 FROM finance_gl_period gl
                WHERE gl.id=? AND gl.organization_id=? AND gl.state='OPEN'
                  AND NOT EXISTS(
                    SELECT 1 FROM finance_gl_period_close close
                    WHERE close.period_id=gl.id
                      AND close.organization_id=gl.organization_id))""",
            Boolean::class.java,periodId,org,
        )==true
        if(!periodOpen || journal[3]!=r.currency ||
            (journal[2] as BigDecimal)<r.amount)return null
        return r.copy(
            sourceHash=journal[1] as String,
            periodId=periodId,periodOpen=periodOpen,
        )
    }

    fun current(org:UUID,id:UUID):FinanceCashVoidVerified? {
        if(!enabled())return null
        val key=policyKey() ?: return null
        val now=clock.instant()
        val policy=approvals.loadActivePolicy(org,key,now)?.takeIf {
            it.organizationId==org && it.policyKey==key &&
                it.approvalRequired && it.mode==ApprovalPolicyMode.SINGLE &&
                !it.authorityKey.isNullOrBlank()
        } ?: return null
        val reviewer=approvals.resolveCurrentAuthority(org,policy.authorityKey!!,now)
            ?:return null
        if(reviewer.organizationId!=org)return null
        val row=jdbc.query(
            """SELECT id,original_record_id,source_kind,source_journal_id,
               bank_statement_line_id,amount,currency,source_ledger_sha256,
               bank_statement_sha256,approval_subject_version,policy_key,
               reason_reference,prepared_by_user_id
               FROM finance_cash_allocation_void_proposal
               WHERE id=? AND organization_id=?""",
            {rs,_ ->FinanceCashVoidProposalRow(
                rs.getObject("id",UUID::class.java),
                rs.getObject("original_record_id",UUID::class.java),
                rs.getString("source_kind"),
                rs.getObject("source_journal_id",UUID::class.java),
                rs.getObject("bank_statement_line_id",UUID::class.java),
                rs.getBigDecimal("amount"),rs.getString("currency"),
                rs.getString("source_ledger_sha256"),
                rs.getString("bank_statement_sha256"),
                rs.getLong("approval_subject_version"),rs.getString("policy_key"),
                rs.getString("reason_reference"),
                rs.getObject("prepared_by_user_id",UUID::class.java),
            )},id,org,
        ).singleOrNull() ?: return null
        val original=original(org,row.originalId) ?: return null
        if(row.key!=key || row.kind!=original.kind ||
            row.journal!=original.journalId || row.bank!=original.bankLineId ||
            row.amount!=original.amount || row.currency!=original.currency ||
            row.sourceHash!=original.sourceHash || row.bankHash!=original.bankHash ||
            row.preparer==original.originalPreparedBy ||
            row.preparer==original.originalRecordedBy ||
            !approvals.isActiveOrganizationMember(org,row.preparer,now) ||
            approvals.actorMatchesAuthority(row.preparer,reviewer,now))return null
        val expected=FinanceCashVoidReviewVersion.derive(
            org,row.originalId,row.kind,row.journal,row.bank,
            row.sourceHash,row.bankHash,row.amount,row.currency,row.reason,
            policy.versionNumber,reviewer.id,reviewer.version,
        )
        if(expected!=row.version)return null
        return FinanceCashVoidVerified(
            id,org,row.originalId,row.kind,row.journal,row.bank,
            row.sourceHash,row.bankHash,row.amount,row.currency,row.reason,
            expected,key,policy.versionNumber,row.preparer,
            original.originalPreparedBy,original.originalRecordedBy,
        )
    }
}

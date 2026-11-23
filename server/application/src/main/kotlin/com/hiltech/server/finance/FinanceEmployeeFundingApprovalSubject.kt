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

const val FINANCE_EMPLOYEE_FUNDING_SUBJECT="FINANCE_EMPLOYEE_FUNDING"

object FinanceEmployeeFundingOrigin {
    fun derive(
        org:UUID,kind:String,draftId:UUID,employeeId:UUID,
        amount:BigDecimal,currency:String,originalPolicyRequestId:UUID,
        originalPolicySubjectVersion:Long,
    ):String {
        val source=listOf(
            "HILTECH_EMPLOYEE_FUNDING_ORIGIN_V1",org,kind,draftId,employeeId,
            amount.setScale(6).toPlainString(),currency,
            originalPolicyRequestId,originalPolicySubjectVersion,
        ).joinToString("|")
        return sha(source)
    }
    fun reviewVersion(
        org:UUID,kind:String,draftId:UUID,employeeId:UUID,
        amount:BigDecimal,currency:String,
        sourceHash:String,bankLineId:UUID,bankStatementHash:String,
        policyVersion:Int,reviewerId:UUID,reviewerVersion:Long,
    ):Long {
        require(policyVersion>0 && reviewerVersion>0)
        val raw=listOf(
            "HILTECH_EMPLOYEE_FUNDING_REVIEW_V1",org,kind,draftId,employeeId,
            amount.stripTrailingZeros().toPlainString(),currency,
            sourceHash,bankLineId,bankStatementHash,
            policyVersion,reviewerId,reviewerVersion,
        ).joinToString("|")
        val bytes=MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        return (ByteBuffer.wrap(bytes,0,8).long and Long.MAX_VALUE).coerceAtLeast(1)
    }
    private fun sha(s:String):String=MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray(Charsets.UTF_8))
        .joinToString(""){"%02x".format(it)}
}
data class FinanceEmployeeFundingVerified(
    val proposalId:UUID,val org:UUID,val kind:String,val draftId:UUID,
    val employeeId:UUID,val originalPolicyApprovalId:UUID,
    val bankLineId:UUID,val amount:BigDecimal,val currency:String,
    val sourceHash:String,val bankHash:String,
    val reviewVersion:Long,val policyKey:String,val policyVersion:Int,
    val preparedBy:UUID,
)
private data class FundingDbRow(
    val proposalId:UUID,val kind:String,val draftId:UUID,
    val employeeId:UUID,val originalApproval:UUID,val bankLineId:UUID,
    val sourceHash:String,val reviewVersion:Long,val policyKey:String,
    val preparedBy:UUID,val amount:BigDecimal,val currency:String,
    val bankAmount:BigDecimal,val bankFlow:String,val bankCurrency:String,
    val statementHash:String,val policyState:String,
    val policySubjectType:String,val policySubjectId:UUID,
    val policySubjectVersion:Long,val policyRequester:UUID,
)

@Component
class FinanceEmployeeFundingApprovalSubject(
    private val jdbc:JdbcTemplate,
    private val approvals:ApprovalPersistencePort,
    private val source:FinanceMoneyDraftPolicyApprovalSubject,
    private val environment:Environment,
    private val clock:Clock,
):ApprovalSubjectVersionSource {
    override fun supports(subjectType:String):Boolean =
        subjectType==FINANCE_EMPLOYEE_FUNDING_SUBJECT
    override fun isCurrent(subject:ApprovalSubjectRef):Boolean =
        supports(subject.subjectType) &&
            current(subject.organizationId,subject.subjectId)
                ?.reviewVersion==subject.subjectVersion
    fun enabled():Boolean=environment.getProperty(
        "hiltech.finance.employee-funding.enabled",Boolean::class.java,false,
    )
    fun policyKey():String?=environment.getProperty(
        "hiltech.finance.employee-funding.policy-key","",
    ).trim().takeIf{Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(it)}

    fun current(org:UUID,id:UUID):FinanceEmployeeFundingVerified? {
        if(!enabled())return null
        val key=policyKey() ?: return null
        val now=clock.instant()
        val policy=approvals.loadActivePolicy(org,key,now)?.takeIf{
            it.organizationId==org && it.policyKey==key &&
                it.approvalRequired && it.mode==ApprovalPolicyMode.SINGLE &&
                !it.authorityKey.isNullOrBlank()
        } ?: return null
        val reviewer=approvals.resolveCurrentAuthority(
            org,policy.authorityKey!!,now,
        ) ?: return null
        if(reviewer.organizationId!=org)return null
        val row=jdbc.query(
            """
            SELECT p.id,p.draft_kind,p.employee_draft_id,p.employee_id,
              p.source_policy_approval_id,p.bank_statement_line_id,
              p.amount,p.currency,p.source_fingerprint_sha256,p.review_version,
              p.policy_key,p.prepared_by_user_id,
              b.amount AS bank_amount,b.currency AS bank_currency,
              b.flow,h.statement_sha256,
              a.state AS policy_state,a.subject_type AS policy_subject_type,
              a.subject_id AS policy_subject_id,
              a.subject_version AS policy_subject_version,
              a.requester_user_id AS policy_requester
            FROM finance_employee_funding_proposal p
            JOIN finance_bank_statement_intake_line b
              ON b.id=p.bank_statement_line_id AND b.organization_id=p.organization_id
            JOIN finance_bank_statement_intake h
              ON h.id=b.intake_id AND h.organization_id=b.organization_id
            JOIN approval_request a
              ON a.id=p.source_policy_approval_id AND a.organization_id=p.organization_id
            WHERE p.id=? AND p.organization_id=?
            """.trimIndent(),
            {rs,_ ->FundingDbRow(
                rs.getObject("id",UUID::class.java),
                rs.getString("draft_kind"),
                rs.getObject("employee_draft_id",UUID::class.java),
                rs.getObject("employee_id",UUID::class.java),
                rs.getObject("source_policy_approval_id",UUID::class.java),
                rs.getObject("bank_statement_line_id",UUID::class.java),
                rs.getString("source_fingerprint_sha256"),
                rs.getLong("review_version"),rs.getString("policy_key"),
                rs.getObject("prepared_by_user_id",UUID::class.java),
                rs.getBigDecimal("amount"),rs.getString("currency"),
                rs.getBigDecimal("bank_amount"),rs.getString("bank_currency"),
                rs.getString("flow"),rs.getString("statement_sha256"),
                rs.getString("policy_state"),
                rs.getString("policy_subject_type"),
                rs.getObject("policy_subject_id",UUID::class.java),
                rs.getLong("policy_subject_version"),
                rs.getObject("policy_requester",UUID::class.java),
            )},id,org,
        ).singleOrNull() ?: return null
        val kind=when(row.kind){
            "EMPLOYEE_ADVANCE"->FinanceEmployeeMoneyDraftKind.ADVANCE
            "FINANCIAL_IMPREST"->FinanceEmployeeMoneyDraftKind.IMPREST
            else->return null
        }
        if(row.policyKey!=key || row.policyState!="APPROVED" ||
            row.policySubjectType!=kind.subjectType ||
            row.policySubjectId!=row.draftId ||
            row.bankFlow!="DEBIT" ||
            row.currency!=row.bankCurrency || row.amount>row.bankAmount ||
            !approvals.isActiveOrganizationMember(org,row.preparedBy,now) ||
            approvals.actorMatchesAuthority(row.preparedBy,reviewer,now))
            return null
        val draft=source.current(kind,org,row.draftId) ?: return null
        if(draft.employeeId!=row.employeeId ||
            draft.currency!=row.currency ||
            draft.amount.compareTo(row.amount)!=0 ||
            draft.createdByUserId!=row.policyRequester ||
            draft.createdByUserId==row.preparedBy ||
            draft.sourceVersion!=row.policySubjectVersion)return null
        val hash=FinanceEmployeeFundingOrigin.derive(
            org,row.kind,row.draftId,row.employeeId,
            row.amount,row.currency,row.originalApproval,row.policySubjectVersion,
        )
        if(hash!=row.sourceHash)return null
        val review=FinanceEmployeeFundingOrigin.reviewVersion(
            org,row.kind,row.draftId,row.employeeId,row.amount,row.currency,
            hash,row.bankLineId,row.statementHash,
            policy.versionNumber,reviewer.id,reviewer.version,
        )
        if(review!=row.reviewVersion)return null
        if(jdbc.queryForObject(
            """SELECT EXISTS(SELECT 1 FROM finance_employee_funding_record
               WHERE organization_id=? AND draft_kind=? AND employee_draft_id=?)""",
            Boolean::class.java,org,row.kind,row.draftId,
        )==true)return null
        val used=jdbc.queryForObject(
            """SELECT coalesce(sum(amount),0) FROM finance_employee_funding_record
               WHERE organization_id=? AND bank_statement_line_id=?""",
            BigDecimal::class.java,org,row.bankLineId,
        ) ?: BigDecimal.ZERO
        val cashUsed=jdbc.queryForObject(
            """SELECT coalesce(sum(allocated_amount),0)
               FROM finance_cash_allocation_effective
               WHERE organization_id=? AND bank_statement_line_id=?""",
            BigDecimal::class.java,org,row.bankLineId,
        ) ?: BigDecimal.ZERO
        if(used.add(cashUsed).add(row.amount)>row.bankAmount)return null
        return FinanceEmployeeFundingVerified(
            proposalId=id,org=org,kind=row.kind,draftId=row.draftId,
            employeeId=row.employeeId,originalPolicyApprovalId=row.originalApproval,
            bankLineId=row.bankLineId,amount=row.amount,currency=row.currency,
            sourceHash=hash,bankHash=row.statementHash,
            reviewVersion=review,policyKey=key,policyVersion=policy.versionNumber,
            preparedBy=row.preparedBy,
        )
    }
}

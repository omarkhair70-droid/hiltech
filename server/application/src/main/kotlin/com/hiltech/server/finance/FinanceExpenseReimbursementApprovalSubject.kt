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

const val FINANCE_EXPENSE_REIMBURSEMENT_SUBJECT="FINANCE_EXPENSE_REIMBURSEMENT"

object FinanceExpenseReimbursementVersion {
 fun derive(
   org:UUID,expense:UUID,employee:UUID,
   documentationApproval:UUID,documentationVersion:Long,
   bankLine:UUID,bankStatementHash:String,amount:BigDecimal,currency:String,
   policyVersion:Int,reviewerId:UUID,reviewerVersion:Long,
 ):Long {
   require(documentationVersion>0 && policyVersion>0 && reviewerVersion>0)
   val data=listOf(
     "FINANCE_EXPENSE_REIMBURSEMENT_V1",org,expense,employee,
     documentationApproval,documentationVersion,bankLine,
     bankStatementHash,amount.stripTrailingZeros().toPlainString(),
     currency,policyVersion,reviewerId,reviewerVersion,
   ).joinToString("|")
   val bytes=MessageDigest.getInstance("SHA-256")
     .digest(data.toByteArray(Charsets.UTF_8))
   return (ByteBuffer.wrap(bytes,0,8).long and Long.MAX_VALUE).coerceAtLeast(1L)
 }
}
data class FinanceExpenseReimbursementVerified(
 val proposalId:UUID,val organizationId:UUID,
 val expenseDraftId:UUID,val employeeId:UUID,
 val documentationApprovalId:UUID,val documentationVersion:Long,
 val bankStatementLineId:UUID,val bankStatementHash:String,
 val amount:BigDecimal,val currency:String,
 val reviewVersion:Long,val policyKey:String,
 val policyVersion:Int,val preparedBy:UUID,
)
private data class ExpenseReimbursementRow(
 val id:UUID,val expense:UUID,val employee:UUID,
 val docApproval:UUID,val docVersion:Long,
 val bankLine:UUID,val amount:BigDecimal,val currency:String,
 val reviewVersion:Long,val policyKey:String,val preparedBy:UUID,
 val reviewedState:String,val reviewedType:String,
 val reviewedSubject:UUID,val reviewedVersion:Long,
 val reviewedRequester:UUID,
 val bankAmount:BigDecimal,val bankCurrency:String,
 val flow:String,val bankStatementHash:String,
)
@Component
class FinanceExpenseReimbursementApprovalSubject(
 private val jdbc:JdbcTemplate,
 private val approvals:ApprovalPersistencePort,
 private val documents:FinanceExpenseDocumentationApprovalSubject,
 private val environment:Environment,
 private val clock:Clock,
):ApprovalSubjectVersionSource {
 override fun supports(subjectType:String):Boolean=
   subjectType==FINANCE_EXPENSE_REIMBURSEMENT_SUBJECT
 override fun isCurrent(subject:ApprovalSubjectRef):Boolean=
   supports(subject.subjectType) &&
     current(subject.organizationId,subject.subjectId)?.reviewVersion==
       subject.subjectVersion
 fun enabled():Boolean=environment.getProperty(
   "hiltech.finance.expense-reimbursement.enabled",
   Boolean::class.java,false,
 )
 fun policyKey():String?=environment.getProperty(
   "hiltech.finance.expense-reimbursement.policy-key","",
 ).trim().takeIf{Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(it)}
 fun current(org:UUID,id:UUID):FinanceExpenseReimbursementVerified? {
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
   val source=jdbc.query(
     """
     SELECT p.id,p.expense_draft_id,p.employee_id,
       p.documentation_approval_request_id,p.documentation_version,
       p.bank_statement_line_id,p.amount,p.currency,
       p.review_version,p.policy_key,p.prepared_by_user_id,
       a.state,a.subject_type,a.subject_id,a.subject_version,
       a.requester_user_id,
       b.amount AS bank_amount,b.currency AS bank_currency,
       b.flow,h.statement_sha256
     FROM finance_expense_reimbursement_proposal p
     JOIN approval_request a
       ON a.id=p.documentation_approval_request_id
       AND a.organization_id=p.organization_id
     JOIN finance_bank_statement_intake_line b
       ON b.id=p.bank_statement_line_id AND b.organization_id=p.organization_id
     JOIN finance_bank_statement_intake h
       ON h.id=b.intake_id AND h.organization_id=b.organization_id
     WHERE p.id=? AND p.organization_id=?
     """.trimIndent(),
     {rs,_ ->ExpenseReimbursementRow(
       rs.getObject("id",UUID::class.java),
       rs.getObject("expense_draft_id",UUID::class.java),
       rs.getObject("employee_id",UUID::class.java),
       rs.getObject("documentation_approval_request_id",UUID::class.java),
       rs.getLong("documentation_version"),
       rs.getObject("bank_statement_line_id",UUID::class.java),
       rs.getBigDecimal("amount"),rs.getString("currency"),
       rs.getLong("review_version"),rs.getString("policy_key"),
       rs.getObject("prepared_by_user_id",UUID::class.java),
       rs.getString("state"),rs.getString("subject_type"),
       rs.getObject("subject_id",UUID::class.java),
       rs.getLong("subject_version"),
       rs.getObject("requester_user_id",UUID::class.java),
       rs.getBigDecimal("bank_amount"),rs.getString("bank_currency"),
       rs.getString("flow"),rs.getString("statement_sha256"),
     )},id,org,
   ).singleOrNull() ?: return null
   if(source.policyKey!=key || source.reviewedState!="APPROVED" ||
     source.reviewedType!=FINANCE_EXPENSE_DOCUMENTATION_REVIEW_SUBJECT ||
     source.reviewedSubject!=source.expense ||
     source.reviewedVersion!=source.docVersion ||
     source.bankCurrency!=source.currency || source.flow!="DEBIT" ||
     !approvals.isActiveOrganizationMember(org,source.preparedBy,now) ||
     approvals.actorMatchesAuthority(source.preparedBy,reviewer,now))
     return null
   val claim=documents.current(org,source.expense) ?: return null
   if(claim.employeeId!=source.employee ||
     claim.createdByUserId!=source.reviewedRequester ||
     claim.createdByUserId==source.preparedBy ||
     claim.sourceVersion!=source.docVersion ||
     claim.currency!=source.currency)return null
   val gross=runCatching{BigDecimal(claim.amount)}.getOrNull() ?: return null
   val spent=jdbc.queryForObject(
     """SELECT coalesce(sum(amount),0)
        FROM finance_expense_reimbursement_record
        WHERE organization_id=? AND expense_draft_id=?""",
     BigDecimal::class.java,org,source.expense,
   ) ?: BigDecimal.ZERO
   val allocated=jdbc.queryForObject(
     """SELECT coalesce(sum(amount),0)
        FROM finance_expense_reimbursement_record
        WHERE organization_id=? AND bank_statement_line_id=?""",
     BigDecimal::class.java,org,source.bankLine,
   ) ?: BigDecimal.ZERO
   if(source.amount<=BigDecimal.ZERO ||
     source.amount.add(spent)>gross ||
     source.amount.add(allocated)>source.bankAmount)return null
   val version=FinanceExpenseReimbursementVersion.derive(
     org,source.expense,source.employee,source.docApproval,
     source.docVersion,source.bankLine,source.bankStatementHash,
     source.amount,source.currency,
     policy.versionNumber,reviewer.id,reviewer.version,
   )
   if(version!=source.reviewVersion)return null
   if(jdbc.queryForObject(
     """SELECT EXISTS(SELECT 1 FROM finance_expense_reimbursement_record
        WHERE organization_id=? AND proposal_id=?)""",
     Boolean::class.java,org,id,
   )==true)return null
   return FinanceExpenseReimbursementVerified(
     proposalId=id,organizationId=org,
     expenseDraftId=source.expense,employeeId=source.employee,
     documentationApprovalId=source.docApproval,
     documentationVersion=source.docVersion,
     bankStatementLineId=source.bankLine,
     bankStatementHash=source.bankStatementHash,
     amount=source.amount,currency=source.currency,
     reviewVersion=version,policyKey=key,
     policyVersion=policy.versionNumber,preparedBy=source.preparedBy,
   )
 }
}

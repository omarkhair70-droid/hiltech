package com.hiltech.server.finance

import com.hiltech.server.approval.CompanyReviewPolicyKeys

import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalPolicyMode
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.approval.ApprovalSubjectVersionSource
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

const val FINANCE_CASHBOX_OPENING_SUBJECT="FINANCE_CASHBOX_OPENING"
const val FINANCE_CASH_VOUCHER_SUBJECT="FINANCE_CASH_VOUCHER"

private fun financePositiveVersion(parts:List<Any>):Long {
 val digest=MessageDigest.getInstance("SHA-256").digest(
   parts.joinToString("|").toByteArray(Charsets.UTF_8))
 return (ByteBuffer.wrap(digest,0,8).long and Long.MAX_VALUE).coerceAtLeast(1L)
}

object FinanceCashboxOpeningVersion {
 fun derive(org:UUID,code:String,currency:String,custodian:UUID,
            companyReference:String,policyVersion:Int,
            reviewerBinding:UUID,reviewerVersion:Long):Long {
    require(policyVersion>0 && reviewerVersion>0)
    return financePositiveVersion(listOf(
      "FINANCE_ZERO_OPENING_CASHBOX_V1",org,code,currency,
      custodian,companyReference,policyVersion,reviewerBinding,reviewerVersion))
 }
}

data class FinanceCashboxOpeningVerified(
 val id:UUID,val org:UUID,val code:String,val currency:String,
 val custodian:UUID,val companyReference:String,val version:Long,
 val policyKey:String,val policyVersion:Int,val preparedBy:UUID,
)
private data class FinanceCashboxOpeningRow(
 val id:UUID,val code:String,val currency:String,val custodian:UUID,
 val companyReference:String,val opening:String,val version:Long,
 val policyKey:String,val preparer:UUID,val codeSource:String,
)
@Component
class FinanceCashboxOpeningApprovalSubject(
 private val jdbc:JdbcTemplate,
 private val approvals:ApprovalPersistencePort,
 private val environment:Environment,
 private val clock:Clock,
):ApprovalSubjectVersionSource {
 override fun supports(subjectType:String):Boolean=
    subjectType==FINANCE_CASHBOX_OPENING_SUBJECT
 override fun isCurrent(subject:ApprovalSubjectRef):Boolean=
    supports(subject.subjectType) && current(subject.organizationId,subject.subjectId)
        ?.version==subject.subjectVersion
 fun enabled():Boolean=environment.getProperty(
    "hiltech.finance.cashbox.enabled",Boolean::class.java,false)
 fun policyKey():String?=CompanyReviewPolicyKeys.resolve(environment, "hiltech.finance.cashbox.opening-policy-key").takeIf{Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(it)}
 fun current(org:UUID,id:UUID):FinanceCashboxOpeningVerified? {
   if(!enabled())return null
   val key=policyKey() ?: return null
   val now=clock.instant()
   val policy=approvals.loadActivePolicy(org,key,now)?.takeIf{
      it.organizationId==org && it.policyKey==key &&
      it.approvalRequired && it.mode==ApprovalPolicyMode.SINGLE &&
      !it.authorityKey.isNullOrBlank()
   } ?: return null
   val reviewer=approvals.resolveCurrentAuthority(
      org,policy.authorityKey!!,now) ?: return null
   if(reviewer.organizationId!=org)return null
   val p=jdbc.query(
     """SELECT id,cashbox_code,currency,custodian_user_id,
        approved_company_reference,opening_balance,subject_version,
        policy_key,prepared_by_user_id,code_source
        FROM finance_cashbox_opening_proposal
        WHERE id=? AND organization_id=?""",
     {rs,_ ->FinanceCashboxOpeningRow(
       rs.getObject("id",UUID::class.java),
       rs.getString("cashbox_code"),rs.getString("currency"),
       rs.getObject("custodian_user_id",UUID::class.java),
       rs.getString("approved_company_reference"),
       rs.getBigDecimal("opening_balance").toPlainString(),
       rs.getLong("subject_version"),rs.getString("policy_key"),
       rs.getObject("prepared_by_user_id",UUID::class.java),rs.getString("code_source"),
     )},id,org).singleOrNull() ?: return null
   if(p.policyKey!=key || p.opening.toBigDecimal().signum()!=0 ||
     !approvals.isActiveOrganizationMember(org,p.preparer,now) ||
     !approvals.isActiveOrganizationMember(org,p.custodian,now) ||
     approvals.actorMatchesAuthority(p.preparer,reviewer,now) ||
     approvals.actorMatchesAuthority(p.custodian,reviewer,now))
     return null
   if(p.codeSource=="AUTOMATIC" && jdbc.queryForObject(
      """SELECT EXISTS(SELECT 1 FROM employee e JOIN user_identity u ON u.person_id=e.person_id
         JOIN organization_membership m ON m.user_identity_id=u.id AND m.organization_id=e.organization_id
         WHERE e.organization_id=? AND u.id=? AND e.state='ACTIVE' AND u.status='ACTIVE'
           AND m.membership_type='EMPLOYEE' AND m.state='ACTIVE' AND m.valid_from<=?
           AND (m.valid_until IS NULL OR m.valid_until>?))""", Boolean::class.java, org,p.custodian,
      now.atOffset(java.time.ZoneOffset.UTC),now.atOffset(java.time.ZoneOffset.UTC))!=true)
      return null
   if(jdbc.queryForObject(
      """SELECT EXISTS(SELECT 1 FROM finance_cashbox_register
         WHERE id=? AND organization_id=?)""",Boolean::class.java,id,org)==true)
      return null
   val version=FinanceCashboxOpeningVersion.derive(
     org,p.code,p.currency,p.custodian,p.companyReference,
     policy.versionNumber,reviewer.id,reviewer.version)
   if(version!=p.version)return null
   return FinanceCashboxOpeningVerified(
     id,org,p.code,p.currency,p.custodian,p.companyReference,
     version,key,policy.versionNumber,p.preparer)
 }
}

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
import java.util.UUID

const val FINANCE_CASHBOX_COUNT_SUBJECT = "FINANCE_CASHBOX_COUNT"

object FinanceCashCountVersion {
 fun derive(
   org:UUID,box:UUID,currency:String,date:LocalDate,
   counted:BigDecimal,ledger:BigDecimal,vouchers:Long,
   reference:String,policy:Int,reviewer:UUID,reviewerVersion:Long,
 ):Long {
   require(policy>0 && reviewerVersion>0 && vouchers>=0)
   fun amount(v:BigDecimal)=v.stripTrailingZeros().toPlainString()
   val input=listOf("FINANCE_PHYSICAL_CASH_COUNT_V1",org,box,currency,date,
     amount(counted),amount(ledger),vouchers,reference,
     policy,reviewer,reviewerVersion).joinToString("|")
   val digest=MessageDigest.getInstance("SHA-256")
     .digest(input.toByteArray(Charsets.UTF_8))
   return (ByteBuffer.wrap(digest,0,8).long and Long.MAX_VALUE).coerceAtLeast(1L)
 }
}

data class FinanceCashCountVerified(
 val id:UUID,val org:UUID,val box:UUID,val currency:String,
 val date:LocalDate,val counted:BigDecimal,val ledger:BigDecimal,
 val vouchers:Long,val reference:String,val version:Long,
 val policyKey:String,val policyVersion:Int,val custodian:UUID,
)

private data class FinanceCashCountRow(
 val snapshot:FinanceCashCountVerified,val preparedBy:UUID,val boxCurrency:String,
)
@Component
class FinanceCashCountApprovalSubject(
 private val jdbc:JdbcTemplate,
 private val approvals:ApprovalPersistencePort,
 private val environment:Environment,
 private val clock:Clock,
):ApprovalSubjectVersionSource {
 override fun supports(subjectType:String):Boolean =
   subjectType==FINANCE_CASHBOX_COUNT_SUBJECT
 override fun isCurrent(subject:ApprovalSubjectRef):Boolean =
   supports(subject.subjectType) &&
     current(subject.organizationId,subject.subjectId)?.version==subject.subjectVersion
 fun enabled():Boolean=
   environment.getProperty(
     "hiltech.finance.cashbox.enabled",Boolean::class.java,false) &&
   environment.getProperty(
     "hiltech.finance.cashbox.count-enabled",Boolean::class.java,false)
 fun policyKey():String?=CompanyReviewPolicyKeys.resolve(environment, "hiltech.finance.cashbox.count-policy-key").takeIf{Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(it)}

 fun current(org:UUID,id:UUID):FinanceCashCountVerified? {
   if(!enabled())return null
   val key=policyKey() ?:return null
   val now=clock.instant()
   val policy=approvals.loadActivePolicy(org,key,now)?.takeIf{
     it.organizationId==org && it.policyKey==key &&
       it.approvalRequired && it.mode==ApprovalPolicyMode.SINGLE &&
       !it.authorityKey.isNullOrBlank()
   } ?:return null
   val reviewer=approvals.resolveCurrentAuthority(
     org,policy.authorityKey!!,now) ?:return null
   if(reviewer.organizationId!=org)return null
   val p=jdbc.query(
     """SELECT p.id,p.cashbox_id,p.currency,p.counted_on,
        p.counted_amount,p.ledger_amount,p.ledger_voucher_count,
        p.count_reference,p.subject_version,p.policy_key,
        p.prepared_by_user_id,b.custodian_user_id,
        b.currency AS box_currency
        FROM finance_cash_count_proposal p
        JOIN finance_cashbox_register b
          ON b.id=p.cashbox_id AND b.organization_id=p.organization_id
        WHERE p.id=? AND p.organization_id=? AND b.status='ACTIVE'""",
     {rs,_ -> FinanceCashCountRow(FinanceCashCountVerified(
       rs.getObject("id",UUID::class.java),org,
       rs.getObject("cashbox_id",UUID::class.java),
       rs.getString("currency"),rs.getDate("counted_on").toLocalDate(),
       rs.getBigDecimal("counted_amount"),rs.getBigDecimal("ledger_amount"),
       rs.getLong("ledger_voucher_count"),rs.getString("count_reference"),
       rs.getLong("subject_version"),rs.getString("policy_key"),
       policy.versionNumber,
       rs.getObject("custodian_user_id",UUID::class.java)),
       rs.getObject("prepared_by_user_id",UUID::class.java),
       rs.getString("box_currency"))
     },id,org).singleOrNull() ?:return null
   val s=p.snapshot
   val prepared=p.preparedBy
   if(s.policyKey!=key || s.custodian!=prepared ||
      s.currency!=p.boxCurrency ||
      !approvals.isActiveOrganizationMember(org,prepared,now) ||
      approvals.actorMatchesAuthority(prepared,reviewer,now))
     return null
   val actual=jdbc.query(
     """SELECT cash_on_hand,recorded_vouchers FROM finance_cashbox_balance
        WHERE organization_id=? AND cashbox_id=?""",
     {rs,_ ->rs.getBigDecimal("cash_on_hand") to
       rs.getLong("recorded_vouchers")},org,s.box).singleOrNull() ?:return null
   if(actual.first.compareTo(s.ledger)!=0 || actual.second!=s.vouchers)
     return null
   val expected=FinanceCashCountVersion.derive(
     org,s.box,s.currency,s.date,s.counted,s.ledger,s.vouchers,
     s.reference,policy.versionNumber,reviewer.id,reviewer.version)
   if(expected!=s.version ||
     jdbc.queryForObject(
       """SELECT EXISTS(SELECT 1 FROM finance_cash_count_record
          WHERE organization_id=? AND proposal_id=?)""",
       Boolean::class.java,org,id)==true) return null
   return s
 }
}

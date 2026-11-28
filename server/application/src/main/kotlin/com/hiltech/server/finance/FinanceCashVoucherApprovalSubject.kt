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

object FinanceCashVoucherVersion {
 fun derive(org:UUID,box:UUID,kind:String,source:UUID,
            sourceHash:String,currency:String,amount:BigDecimal,
            reference:String,reason:String,policyVersion:Int,
            reviewer:UUID,reviewerVersion:Long):Long {
    require(policyVersion>0 && reviewerVersion>0)
    val data=listOf("FINANCE_PHYSICAL_CASH_VOUCHER_V1",org,box,
       kind,source,sourceHash,currency,
       amount.stripTrailingZeros().toPlainString(),reference,reason,
       policyVersion,reviewer,reviewerVersion).joinToString("|")
    val sha=MessageDigest.getInstance("SHA-256")
       .digest(data.toByteArray(Charsets.UTF_8))
    return (ByteBuffer.wrap(sha,0,8).long and Long.MAX_VALUE).coerceAtLeast(1L)
 }
}
data class FinanceCashVoucherVerified(
 val id:UUID,val org:UUID,val box:UUID,val kind:String,
 val sourceJournal:UUID,val direction:String,val amount:BigDecimal,
 val currency:String,val reference:String,val reason:String,
 val sourceHash:String,val version:Long,val policyKey:String,
 val policyVersion:Int,val preparedBy:UUID,val custodian:UUID,
)
private data class FinanceCashVoucherSource(
 val amount:BigDecimal,val currency:String,val hash:String,
)
private data class FinanceCashVoucherProposalRow(
 val id:UUID,val box:UUID,val kind:String,val journal:UUID,
 val direction:String,val amount:BigDecimal,val currency:String,
 val reference:String,val reason:String,val sourceHash:String,
 val version:Long,val policyKey:String,val preparer:UUID,
 val boxCurrency:String,val custodian:UUID,
)
@Component
class FinanceCashVoucherApprovalSubject(
 private val jdbc:JdbcTemplate,
 private val approvals:ApprovalPersistencePort,
 private val environment:Environment,
 private val clock:Clock,
):ApprovalSubjectVersionSource {
 override fun supports(subjectType:String):Boolean=
    subjectType==FINANCE_CASH_VOUCHER_SUBJECT
 override fun isCurrent(subject:ApprovalSubjectRef):Boolean=
    supports(subject.subjectType) &&
     current(subject.organizationId,subject.subjectId)
       ?.version==subject.subjectVersion
 fun enabled():Boolean=environment.getProperty(
    "hiltech.finance.cashbox.enabled",Boolean::class.java,false)
 fun policyKey():String?=CompanyReviewPolicyKeys.resolve(environment, "hiltech.finance.cashbox.voucher-policy-key").takeIf{Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(it)}
 fun source(org:UUID,kind:String,journal:UUID):Triple<BigDecimal,String,String>? {
   if(kind !in setOf("AP","AR"))return null
   if(kind=="AP" && jdbc.queryForObject(
      """SELECT EXISTS(SELECT 1 FROM finance_ap_journal_reversal
         WHERE organization_id=? AND original_journal_id=?)""",
      Boolean::class.java,org,journal)==true)return null
   return jdbc.query(
     """SELECT gross_amount,currency,ledger_fingerprint_sha256
        FROM finance_obligation_source WHERE id=? AND organization_id=? AND source_kind=?""",
     {rs,_ ->Triple(rs.getBigDecimal("gross_amount"),
       rs.getString("currency"),rs.getString("ledger_fingerprint_sha256"))},
     journal,org,kind,
   ).singleOrNull()
 }
 fun outstanding(org:UUID,kind:String,journal:UUID,total:BigDecimal):BigDecimal {
    val bank=jdbc.queryForObject(
     """SELECT COALESCE(SUM(allocated_amount),0)
        FROM finance_cash_allocation_effective
        WHERE organization_id=? AND source_kind=? AND source_journal_id=?""",
     BigDecimal::class.java,org,kind,journal) ?: BigDecimal.ZERO
    val cash=jdbc.queryForObject(
     """SELECT COALESCE(SUM(amount),0)
        FROM finance_cash_voucher_record
        WHERE organization_id=? AND source_kind=? AND source_journal_id=?""",
     BigDecimal::class.java,org,kind,journal) ?: BigDecimal.ZERO
    return total.subtract(bank).subtract(cash)
 }
 fun cashOnHand(org:UUID,box:UUID):BigDecimal?=jdbc.query(
   """SELECT cash_on_hand FROM finance_cashbox_balance
      WHERE organization_id=? AND cashbox_id=?""",
   {rs,_ ->rs.getBigDecimal("cash_on_hand")},org,box).singleOrNull()
 fun current(org:UUID,id:UUID):FinanceCashVoucherVerified? {
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
      """SELECT p.id,p.cashbox_id,p.source_kind,p.source_journal_id,
         p.direction,p.amount,p.currency,p.voucher_reference,
         p.reason_reference,p.source_ledger_sha256,
         p.approval_subject_version,p.policy_key,p.prepared_by_user_id,
         b.currency AS box_currency,b.custodian_user_id
         FROM finance_cash_voucher_proposal p
         JOIN finance_cashbox_register b
           ON b.id=p.cashbox_id AND b.organization_id=p.organization_id
         WHERE p.id=? AND p.organization_id=? AND b.status='ACTIVE'""",
      {rs,_ ->FinanceCashVoucherProposalRow(
       rs.getObject("id",UUID::class.java),
       rs.getObject("cashbox_id",UUID::class.java),
       rs.getString("source_kind"),
       rs.getObject("source_journal_id",UUID::class.java),
       rs.getString("direction"),rs.getBigDecimal("amount"),
       rs.getString("currency"),rs.getString("voucher_reference"),
       rs.getString("reason_reference"),
       rs.getString("source_ledger_sha256"),
       rs.getLong("approval_subject_version"),
       rs.getString("policy_key"),
       rs.getObject("prepared_by_user_id",UUID::class.java),
       rs.getString("box_currency"),
       rs.getObject("custodian_user_id",UUID::class.java),
      )},id,org).singleOrNull() ?:return null
    if(p.policyKey!=key || p.boxCurrency!=p.currency ||
       p.preparer==p.custodian ||
       (p.kind=="AP" && p.direction!="PAYMENT") ||
       (p.kind=="AR" && p.direction!="RECEIPT") ||
       approvals.actorMatchesAuthority(p.preparer,reviewer,now) ||
       approvals.actorMatchesAuthority(p.custodian,reviewer,now) ||
       !approvals.isActiveOrganizationMember(org,p.preparer,now) ||
       !approvals.isActiveOrganizationMember(org,p.custodian,now))
       return null
    val src=source(org,p.kind,p.journal) ?:return null
    if(src.second!=p.currency || src.third!=p.sourceHash ||
       p.amount<=BigDecimal.ZERO || outstanding(
         org,p.kind,p.journal,src.first)<p.amount)return null
    if(p.direction=="PAYMENT" && (
       cashOnHand(org,p.box)?:return null)<p.amount)return null
    val version=FinanceCashVoucherVersion.derive(
       org,p.box,p.kind,p.journal,p.sourceHash,p.currency,p.amount,
       p.reference,p.reason,policy.versionNumber,
       reviewer.id,reviewer.version)
    if(p.version!=version || jdbc.queryForObject(
       """SELECT EXISTS(SELECT 1 FROM finance_cash_voucher_record
          WHERE organization_id=? AND proposal_id=?)""",
       Boolean::class.java,org,id)==true)return null
    return FinanceCashVoucherVerified(
       id,org,p.box,p.kind,p.journal,p.direction,p.amount,p.currency,
       p.reference,p.reason,p.sourceHash,version,key,policy.versionNumber,
       p.preparer,p.custodian,
    )
 }
}

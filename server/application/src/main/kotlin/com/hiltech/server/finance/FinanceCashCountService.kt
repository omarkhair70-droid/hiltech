package com.hiltech.server.finance

import com.hiltech.server.operationalaccess.OperationalAuthorityPort
import com.hiltech.server.operationalaccess.currentOperationalAuthority

import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalPolicyMode
import com.hiltech.server.approval.ApprovalRequestState
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

data class FinanceCashCountPrepare(
 val operationId:UUID,val cashboxId:UUID,val countedAmount:String,
 val countReference:String,
)
data class FinanceCashCountOperation(val operationId:UUID)
data class FinanceCashCountRecordRequest(
 val operationId:UUID,val signedCountEvidenceId:UUID,
)
data class FinanceCashCountReceipt(
 val proposalId:UUID,val organizationId:UUID,val cashboxId:UUID,
 val currency:String,val countedAmount:String,val ledgerAmount:String,
 val varianceAmount:String,val voucherCount:Long,val state:String,
 val approvalRequestId:UUID?=null,val countRecordId:UUID?=null,
 val replayed:Boolean=false,val ledgerAdjusted:Boolean=false,
 val bankTransferInitiated:Boolean=false,
)

/** A witnessed physical count, not a payout, GL adjustment or bank reconciliation. */
@Service
class FinanceCashCountService @org.springframework.beans.factory.annotation.Autowired constructor(
 private val jdbc:JdbcTemplate,
 private val subject:FinanceCashCountApprovalSubject,
 private val approvals:ApprovalPersistencePort,
 private val engine:ApprovalEngineService,
 private val idempotency:IdempotentCommandExecutor,
 private val audit:AuditEventWriter,
 private val environment:Environment,
 private val clock:Clock,
    private val operationalAuthority: OperationalAuthorityPort? = null,
) {
 @Transactional
 fun prepare(
   actor:UUID,org:UUID,req:FinanceCashCountPrepare,correlation:String,
 ):FinanceCashCountReceipt {
   authority(actor,org,"hiltech.finance.cashbox.count-preparer-authority-key")
   val amount=runCatching{BigDecimal(req.countedAmount)}.getOrNull()
      ?:unavailable()
   if(amount.signum()<0 || amount.stripTrailingZeros().scale()>6 ||
     amount.stripTrailingZeros().precision()>24)unavailable()
   val reference=req.countReference.trim()
   if(reference.length !in 8..180)unavailable()
   val now=clock.instant()
   val key=subject.policyKey() ?:unavailable()
   val policy=approvals.loadActivePolicy(org,key,now)?.takeIf{
     it.organizationId==org && it.policyKey==key &&
     it.approvalRequired && it.mode==ApprovalPolicyMode.SINGLE &&
     !it.authorityKey.isNullOrBlank()
   } ?:unavailable()
   val reviewer=approvals.resolveCurrentAuthority(
     org,policy.authorityKey!!,now) ?:unavailable()
   if(reviewer.organizationId!=org ||
      approvals.actorMatchesAuthority(actor,reviewer,now))unavailable()
   val box=lockBox(org,req.cashboxId)
   if(box.second!=actor)unavailable()
   val snapshot=balance(org,req.cashboxId) ?:stale()
   val date=clock.instant().atOffset(ZoneOffset.UTC).toLocalDate()
   val version=FinanceCashCountVersion.derive(
     org,req.cashboxId,box.first,date,amount,snapshot.first,snapshot.second,
     reference,policy.versionNumber,reviewer.id,reviewer.version)
   val id=UUID.nameUUIDFromBytes(
     "hiltech:cashbox-count:${req.operationId}".toByteArray(Charsets.UTF_8))
   val fingerprint=IdempotencyKeyContract.fingerprint(
     listOf(org,req.cashboxId,amount,snapshot.first,snapshot.second,
       reference,version).joinToString("|"))
   val outcome=idempotency.execute(IdempotentCommandSpec(
     req.operationId,actor,"FINANCE_CASHBOX_COUNT_PROPOSED",
     "FINANCE_CASH_COUNT_PROPOSAL",id,fingerprint,correlation,
   )){
     lockBox(org,req.cashboxId)
     if(balance(org,req.cashboxId)!=snapshot)stale()
     check(jdbc.update(
       """INSERT INTO finance_cash_count_proposal(
          id,operation_id,organization_id,cashbox_id,currency,counted_on,
          counted_amount,ledger_amount,ledger_voucher_count,count_reference,
          subject_version,policy_key,prepared_by_user_id,created_at)
          VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
       id,req.operationId,org,req.cashboxId,box.first,date,
       amount,snapshot.first,snapshot.second,reference,version,key,actor,
       clock.instant().atOffset(ZoneOffset.UTC),
     )==1)
     audit.append(AuditEventRecord(
       actorUserId=actor,action="FINANCE_CASHBOX_COUNT_PROPOSED",
       targetType="FINANCE_CASH_COUNT_PROPOSAL",targetId=id,
       occurredAt=clock.instant(),correlationId=correlation,
       reason="PHYSICAL_COUNT_NEEDS_INDEPENDENT_WITNESS",
     ))
     IdempotentCommandOutcome(
       "FINANCE_CASHBOX_COUNT_PROPOSED","""{"id":"$id"}""")
   }
   return FinanceCashCountReceipt(
     id,org,req.cashboxId,box.first,amount.toPlainString(),
     snapshot.first.toPlainString(),
     amount.subtract(snapshot.first).toPlainString(),
     snapshot.second,"PROPOSED",replayed=outcome.replayed)
 }
 fun requestReview(
   actor:UUID,org:UUID,id:UUID,operation:UUID,correlation:String,
 ):FinanceCashCountReceipt {
   authority(actor,org,"hiltech.finance.cashbox.count-preparer-authority-key")
   val s=subject.current(org,id) ?:stale()
   if(s.custodian!=actor)unavailable()
   val ref=ApprovalSubjectRef(org,FINANCE_CASHBOX_COUNT_SUBJECT,id,s.version)
   val old=engine.subjectStatus(ref,s.policyKey)
   if(old!=null){
     if(old.state!=ApprovalRequestState.PENDING)unavailable()
     return receipt(s,"PENDING",old.approvalRequestId,replayed=true)
   }
   val result=engine.requestException(
     actorUserId=actor,operationId=operation,subject=ref,
     policyKey=s.policyKey,reasonCode="FINANCE_CASHBOX_COUNT_REVIEW",
     safeReasonSummary=null,correlationId=correlation,
     allowAuthorityBypass=false,
   )
   if(!result.approvalRequired || result.approvalRequestId==null ||
     result.state!=ApprovalRequestState.PENDING ||
     result.policyVersion!=s.policyVersion)unavailable()
   return receipt(s,"PENDING",result.approvalRequestId,
     replayed=result.replayed)
 }
 @Transactional
 fun record(
   actor:UUID,org:UUID,id:UUID,req:FinanceCashCountRecordRequest,
   correlation:String,
 ):FinanceCashCountReceipt {
   authority(actor,org,"hiltech.finance.cashbox.count-operator-authority-key")
   historic(org,id)?.let {old ->
     val match=jdbc.query(
       """SELECT operation_id,recorded_by_user_id,count_evidence_id
          FROM finance_cash_count_record
          WHERE id=? AND organization_id=?""",
       {rs,_ -> Triple(
         rs.getObject("operation_id",UUID::class.java),
         rs.getObject("recorded_by_user_id",UUID::class.java),
         rs.getObject("count_evidence_id",UUID::class.java))},
       old.countRecordId,org).singleOrNull() ?:unavailable()
     if(match.first!=req.operationId || match.second!=actor ||
        match.third!=req.signedCountEvidenceId)unavailable()
     return old.copy(replayed=true)
   }
   val s=subject.current(org,id) ?:stale()
   if(s.custodian==actor)unavailable()
   val ref=ApprovalSubjectRef(org,FINANCE_CASHBOX_COUNT_SUBJECT,id,s.version)
   val approved=engine.subjectStatus(ref,s.policyKey)?.takeIf{
     it.state==ApprovalRequestState.APPROVED &&
       it.subjectVersion==s.version
   } ?:unavailable()
   val recordId=UUID.nameUUIDFromBytes(
     "hiltech:cashbox-count-recorded:${req.operationId}".toByteArray(Charsets.UTF_8))
   val fingerprint=IdempotencyKeyContract.fingerprint(
     listOf(org,id,s.version,req.signedCountEvidenceId,
       approved.approvalRequestId).joinToString("|"))
   val outcome=idempotency.execute(IdempotentCommandSpec(
     req.operationId,actor,"FINANCE_CASHBOX_COUNT_RECORDED",
     "FINANCE_CASH_COUNT_RECORD",recordId,fingerprint,correlation,
   )){
     lockBox(org,s.box)
     if(subject.current(org,id)!=s)stale()
     check(jdbc.update(
       """INSERT INTO finance_cash_count_record(
          id,operation_id,organization_id,proposal_id,approval_request_id,
          count_evidence_id,recorded_by_user_id,recorded_at,correlation_id)
          VALUES(?,?,?,?,?,?,?,?,?)""",
       recordId,req.operationId,org,id,approved.approvalRequestId,
       req.signedCountEvidenceId,actor,
       clock.instant().atOffset(ZoneOffset.UTC),correlation,
     )==1)
     audit.append(AuditEventRecord(
       actorUserId=actor,action="FINANCE_CASHBOX_COUNT_RECORDED",
       targetType="FINANCE_CASH_COUNT_RECORD",targetId=recordId,
       occurredAt=clock.instant(),correlationId=correlation,
       reason="WITNESSED_PHYSICAL_COUNT_VARIANCE_ONLY_NO_LEDGER_ADJUSTMENT",
     ))
     IdempotentCommandOutcome(
       "FINANCE_CASHBOX_COUNT_RECORDED","""{"id":"$recordId"}""")
   }
   return receipt(s,"RECORDED",approved.approvalRequestId,
     recordId,outcome.replayed)
 }
 /** Only approved, unrecorded operations available to this specific operator. */
 fun operatorQueue(actor:UUID,org:UUID):List<FinanceCashCountReceipt> {
   authority(actor,org,"hiltech.finance.cashbox.count-operator-authority-key")
   val ids=jdbc.query(
      """SELECT p.id FROM finance_cash_count_proposal p
         WHERE p.organization_id=?
         AND EXISTS (SELECT 1 FROM approval_request a
           WHERE a.organization_id=p.organization_id AND a.subject_id=p.id
           AND a.subject_type=? AND a.state='APPROVED')
         AND NOT EXISTS (SELECT 1 FROM finance_cash_count_record r
           WHERE r.organization_id=p.organization_id AND r.proposal_id=p.id)
         ORDER BY p.id LIMIT 100""",
      {rs,_->rs.getObject("id",UUID::class.java)},org,FINANCE_CASHBOX_COUNT_SUBJECT)
   return ids.mapNotNull { id ->
      val s=subject.current(org,id) ?:return@mapNotNull null
      if(s.custodian==actor)return@mapNotNull null
      val review=engine.subjectStatus(
          ApprovalSubjectRef(org,FINANCE_CASHBOX_COUNT_SUBJECT,id,s.version),s.policyKey)
          ?.takeIf {it.state==ApprovalRequestState.APPROVED && it.subjectVersion==s.version}
          ?:return@mapNotNull null
      if(historic(org,id)!=null)return@mapNotNull null
      receipt(s,"APPROVED",review.approvalRequestId)
   }
 }
 /** Operator-only detail; historical records are limited to the recording actor. */
 fun operatorStatus(actor:UUID,org:UUID,id:UUID):FinanceCashCountReceipt {
   authority(actor,org,"hiltech.finance.cashbox.count-operator-authority-key")
   historic(org,id)?.let { old ->
      val owner=jdbc.query(
        """SELECT recorded_by_user_id FROM finance_cash_count_record
           WHERE organization_id=? AND id=?""",
        {rs,_->rs.getObject("recorded_by_user_id",UUID::class.java)},org,old.countRecordId)
        .singleOrNull() ?:unavailable()
      if(owner!=actor)unavailable()
      return old
   }
   val s=subject.current(org,id) ?:stale()
   if(s.custodian==actor)unavailable()
   val review=engine.subjectStatus(
       ApprovalSubjectRef(org,FINANCE_CASHBOX_COUNT_SUBJECT,id,s.version),s.policyKey)
       ?.takeIf {it.state==ApprovalRequestState.APPROVED && it.subjectVersion==s.version}
       ?:unavailable()
   return receipt(s,"APPROVED",review.approvalRequestId)
 }
 fun status(actor:UUID,org:UUID,id:UUID):FinanceCashCountReceipt {
   authority(actor,org,"hiltech.finance.cashbox.reader-authority-key")
   historic(org,id)?.let{return it}
   val s=subject.current(org,id) ?:stale()
   val approval=engine.subjectStatus(
     ApprovalSubjectRef(org,FINANCE_CASHBOX_COUNT_SUBJECT,id,s.version),
     s.policyKey)
   return receipt(s,approval?.state?.name ?:"PROPOSED",
     approval?.approvalRequestId)
 }
 private fun historic(org:UUID,id:UUID):FinanceCashCountReceipt?=
   jdbc.query(
     """SELECT r.id,p.cashbox_id,p.currency,p.counted_amount,
        p.ledger_amount,p.ledger_voucher_count,r.approval_request_id
        FROM finance_cash_count_record r
        JOIN finance_cash_count_proposal p
          ON p.id=r.proposal_id AND p.organization_id=r.organization_id
        WHERE r.organization_id=? AND r.proposal_id=?""",
     {rs,_ ->
       val counted=rs.getBigDecimal("counted_amount")
       val ledger=rs.getBigDecimal("ledger_amount")
       FinanceCashCountReceipt(
         id,org,rs.getObject("cashbox_id",UUID::class.java),
         rs.getString("currency"),counted.toPlainString(),
         ledger.toPlainString(),counted.subtract(ledger).toPlainString(),
         rs.getLong("ledger_voucher_count"),"RECORDED",
         approvalRequestId=rs.getObject("approval_request_id",UUID::class.java),
         countRecordId=rs.getObject("id",UUID::class.java),
       )
     },org,id).singleOrNull()
 private fun receipt(
   s:FinanceCashCountVerified,state:String,approval:UUID?=null,
   recorded:UUID?=null,replayed:Boolean=false,
 ):FinanceCashCountReceipt=FinanceCashCountReceipt(
   s.id,s.org,s.box,s.currency,s.counted.toPlainString(),
   s.ledger.toPlainString(),s.counted.subtract(s.ledger).toPlainString(),
   s.vouchers,state,approval,recorded,replayed,
 )
 private fun lockBox(org:UUID,id:UUID):Pair<String,UUID> =
   jdbc.query(
     """SELECT currency,custodian_user_id FROM finance_cashbox_register
        WHERE organization_id=? AND id=? AND status='ACTIVE' FOR UPDATE""",
     {rs,_ ->rs.getString("currency") to
       rs.getObject("custodian_user_id",UUID::class.java)},org,id,
   ).singleOrNull() ?:stale()
 private fun balance(org:UUID,id:UUID):Pair<BigDecimal,Long>?=
   jdbc.query(
     """SELECT cash_on_hand,recorded_vouchers FROM finance_cashbox_balance
        WHERE organization_id=? AND cashbox_id=?""",
     {rs,_ ->rs.getBigDecimal("cash_on_hand") to
       rs.getLong("recorded_vouchers")},org,id,
   ).singleOrNull()
 private fun authority(actor: UUID, org: UUID, property: String) {
     if (!subject.enabled()) unavailable()
     currentOperationalAuthority(operationalAuthority, approvals, actor, org, property,
         environment.getProperty(property), clock.instant()) ?: unavailable()
 }
 private fun unavailable():Nothing=throw ProductApiException(
   "FINANCE_CASH_COUNT_NOT_AUTHORIZED",
   "Company custody, independent witness and finance policy required.",
   HttpStatus.NOT_FOUND)
 private fun stale():Nothing=throw ProductApiException(
   "FINANCE_CASH_COUNT_STALE",
   "Physical cash voucher, witness authority or source balance changed.",
   HttpStatus.CONFLICT)
}

@RestController
class FinanceCashCountController(private val counts:FinanceCashCountService) {
 @PostMapping("/v1/finance/organizations/{orgId}/cash-counts/prepare")
 fun prepare(
   http:HttpServletRequest,@PathVariable orgId:UUID,
   @RequestBody body:FinanceCashCountPrepare,
 ):FinanceCashCountReceipt {
   val c=HiltechRequestContext.current(http)
   IdempotencyKeyContract.requireMatches(c.idempotencyKey,body.operationId)
   return counts.prepare(actor(http),orgId,body,c.correlationId)
 }
 @PostMapping("/v1/finance/organizations/{orgId}/cash-counts/{id}/request-review")
 fun review(
   http:HttpServletRequest,@PathVariable orgId:UUID,
   @PathVariable id:UUID,@RequestBody body:FinanceCashCountOperation,
 ):FinanceCashCountReceipt {
   val c=HiltechRequestContext.current(http)
   IdempotencyKeyContract.requireMatches(c.idempotencyKey,body.operationId)
   return counts.requestReview(actor(http),orgId,id,body.operationId,c.correlationId)
 }
 @PostMapping("/v1/finance/organizations/{orgId}/cash-counts/{id}/record")
 fun record(
   http:HttpServletRequest,@PathVariable orgId:UUID,
   @PathVariable id:UUID,@RequestBody body:FinanceCashCountRecordRequest,
 ):FinanceCashCountReceipt {
   val c=HiltechRequestContext.current(http)
   IdempotencyKeyContract.requireMatches(c.idempotencyKey,body.operationId)
   return counts.record(actor(http),orgId,id,body,c.correlationId)
 }
 @GetMapping("/v1/finance/organizations/{orgId}/cash-counts/operator-queue")
 fun operatorQueue(http:HttpServletRequest,@PathVariable orgId:UUID):List<FinanceCashCountReceipt> =
   counts.operatorQueue(actor(http),orgId)
 @GetMapping("/v1/finance/organizations/{orgId}/cash-counts/{id}/operator-status")
 fun operatorStatus(http:HttpServletRequest,@PathVariable orgId:UUID,@PathVariable id:UUID):FinanceCashCountReceipt=
   counts.operatorStatus(actor(http),orgId,id)
 @GetMapping("/v1/finance/organizations/{orgId}/cash-counts/{id}")
 fun status(
   http:HttpServletRequest,@PathVariable orgId:UUID,@PathVariable id:UUID,
 ):FinanceCashCountReceipt=counts.status(actor(http),orgId,id)
 private fun actor(http:HttpServletRequest):UUID=
   HiltechRequestContext.current(http).identityId
     ?.let{runCatching{UUID.fromString(it)}.getOrNull()}
     ?:throw ProductApiException(
        "UNAUTHENTICATED","Company finance identity required.",
        HttpStatus.UNAUTHORIZED)
}

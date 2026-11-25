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

data class FinanceCashVoucherPrepareRequest(
 val operationId:UUID,val cashboxId:UUID,val amount:String,
 val voucherReference:String,val reasonReference:String,
)
data class FinanceCashVoucherOperation(val operationId:UUID)
data class FinanceCashVoucherRecordRequest(
 val operationId:UUID,val signedCustodyEvidenceId:UUID,
)
data class FinanceCashVoucherReceipt(
 val proposalId:UUID,val organizationId:UUID,
 val cashboxId:UUID,val sourceKind:String,
 val sourceJournalId:UUID,val direction:String,
 val amount:String,val currency:String,val state:String,
 val approvalRequestId:UUID?=null,val voucherRecordId:UUID?=null,
 val replayed:Boolean=false,val physicalCustodyConfirmed:Boolean=false,
 val bankTransactionInitiated:Boolean=false,
 val signedCashboxCountReconciled:Boolean=false,
)
private data class FinanceCashboxLookup(
 val currency:String,val custodian:UUID,
)

/** A signed real physical voucher, never a bank transfer or estimated receipt. */
@Service
class FinanceCashVoucherService @org.springframework.beans.factory.annotation.Autowired constructor(
 private val jdbc:JdbcTemplate,
 private val subject:FinanceCashVoucherApprovalSubject,
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
   actor:UUID,org:UUID,kind:String,journal:UUID,
   req:FinanceCashVoucherPrepareRequest,correlation:String,
 ):FinanceCashVoucherReceipt {
   authority(actor,org,"hiltech.finance.cashbox.voucher-preparer-authority-key")
   if(kind !in setOf("AP","AR"))unavailable()
   val ref=req.voucherReference.trim()
   val reason=req.reasonReference.trim()
   if(ref.length !in 8..180 || reason.length !in 8..180)unavailable()
   val amount=runCatching{BigDecimal(req.amount)}.getOrNull() ?:unavailable()
   if(amount.signum()<=0 || amount.stripTrailingZeros().scale()>6 ||
     amount.stripTrailingZeros().precision()>24)unavailable()
   val box=jdbc.query(
     """SELECT currency,custodian_user_id FROM finance_cashbox_register
        WHERE id=? AND organization_id=? AND status='ACTIVE'""",
     {rs,_ ->FinanceCashboxLookup(rs.getString("currency"),
        rs.getObject("custodian_user_id",UUID::class.java))},req.cashboxId,org,
   ).singleOrNull() ?:unavailable()
   if(box.custodian==actor)unavailable()
   val source=subject.source(org,kind,journal) ?:unavailable()
   if(box.currency!=source.second ||
       subject.outstanding(org,kind,journal,source.first)<amount ||
       (kind=="AP" && (subject.cashOnHand(org,req.cashboxId)
          ?:BigDecimal.ZERO)<amount))stale()
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
      approvals.actorMatchesAuthority(actor,reviewer,now) ||
      approvals.actorMatchesAuthority(box.custodian,reviewer,now) ||
      !approvals.isActiveOrganizationMember(org,box.custodian,now))unavailable()
   val direction=if(kind=="AR")"RECEIPT" else "PAYMENT"
   val version=FinanceCashVoucherVersion.derive(
      org,req.cashboxId,kind,journal,source.third,source.second,
      amount,ref,reason,policy.versionNumber,reviewer.id,reviewer.version)
   val id=UUID.nameUUIDFromBytes(
      "hiltech:physical-cash-voucher:${req.operationId}"
          .toByteArray(Charsets.UTF_8))
   val fingerprint=IdempotencyKeyContract.fingerprint(
       listOf(org,req.cashboxId,kind,journal,amount,source.third,
          ref,reason,version).joinToString("|"))
   val outcome=idempotency.execute(IdempotentCommandSpec(
      req.operationId,actor,"FINANCE_CASH_VOUCHER_PROPOSED",
      "FINANCE_CASH_VOUCHER_PROPOSAL",id,fingerprint,correlation,
   )){
      lockSourceAndBox(org,kind,journal,req.cashboxId)
      val checked=subject.source(org,kind,journal) ?:stale()
      if(checked!=source ||
        subject.outstanding(org,kind,journal,source.first)<amount ||
        (kind=="AP" && (subject.cashOnHand(org,req.cashboxId)
             ?:BigDecimal.ZERO)<amount))stale()
      check(jdbc.update(
       """INSERT INTO finance_cash_voucher_proposal(
          id,operation_id,organization_id,cashbox_id,source_kind,
          source_journal_id,direction,currency,amount,source_ledger_sha256,
          voucher_reference,reason_reference,approval_subject_version,
          policy_key,prepared_by_user_id,created_at)
          VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        id,req.operationId,org,req.cashboxId,kind,journal,
        direction,box.currency,amount,source.third,ref,reason,version,key,
        actor,clock.instant().atOffset(ZoneOffset.UTC),
      )==1)
      audit.append(AuditEventRecord(
        actorUserId=actor,action="FINANCE_CASH_VOUCHER_PROPOSED",
        targetType="FINANCE_CASH_VOUCHER_PROPOSAL",targetId=id,
        occurredAt=clock.instant(),correlationId=correlation,
        reason="PHYSICAL_CASH_VOUCHER_NOT_PAID_APPROVAL_REQUIRED",
      ))
      IdempotentCommandOutcome("FINANCE_CASH_VOUCHER_PROPOSED",
         """{"id":"$id"}""")
   }
   return FinanceCashVoucherReceipt(
      id,org,req.cashboxId,kind,journal,direction,amount.toPlainString(),
      box.currency,"PROPOSED",replayed=outcome.replayed)
 }
 fun requestReview(
   actor:UUID,org:UUID,id:UUID,operation:UUID,correlation:String,
 ):FinanceCashVoucherReceipt {
   authority(actor,org,"hiltech.finance.cashbox.voucher-preparer-authority-key")
   val s=subject.current(org,id) ?:stale()
   if(s.preparedBy!=actor)unavailable()
   val ref=ApprovalSubjectRef(org,FINANCE_CASH_VOUCHER_SUBJECT,id,s.version)
   val old=engine.subjectStatus(ref,s.policyKey)
   if(old!=null) {
      if(old.state!=ApprovalRequestState.PENDING)unavailable()
      return receipt(s,"PENDING",old.approvalRequestId,replayed=true)
   }
   val requested=engine.requestException(
      actorUserId=actor,operationId=operation,subject=ref,
      policyKey=s.policyKey,
      reasonCode="FINANCE_PHYSICAL_RECEIPT_OR_PAYMENT_REVIEW",
      safeReasonSummary=null,correlationId=correlation,
      allowAuthorityBypass=false,
   )
   if(!requested.approvalRequired || requested.approvalRequestId==null ||
      requested.state!=ApprovalRequestState.PENDING ||
      requested.policyVersion!=s.policyVersion)unavailable()
   return receipt(s,"PENDING",requested.approvalRequestId,
      replayed=requested.replayed)
 }
 @Transactional
 fun record(
   actor:UUID,org:UUID,id:UUID,req:FinanceCashVoucherRecordRequest,
   correlation:String,
 ):FinanceCashVoucherReceipt {
   authority(actor,org,"hiltech.finance.cashbox.voucher-operator-authority-key")
   historic(org,id)?.let{old->
      val command=jdbc.query(
        """SELECT operation_id,recorded_by_user_id
           FROM finance_cash_voucher_record
           WHERE id=? AND organization_id=?""",
        {rs,_ ->rs.getObject("operation_id",UUID::class.java) to
          rs.getObject("recorded_by_user_id",UUID::class.java)},
        old.voucherRecordId,org).singleOrNull() ?:unavailable()
      if(command.first!=req.operationId || command.second!=actor)unavailable()
      return old.copy(replayed=true)
   }
   val s=subject.current(org,id) ?:stale()
   if(s.custodian!=actor || s.preparedBy==actor)unavailable()
   val ref=ApprovalSubjectRef(org,FINANCE_CASH_VOUCHER_SUBJECT,id,s.version)
   val approved=engine.subjectStatus(ref,s.policyKey)?.takeIf{
      it.state==ApprovalRequestState.APPROVED &&
      it.subjectVersion==s.version
   } ?:unavailable()
   val recordId=UUID.nameUUIDFromBytes(
      "hiltech:physical-cash-voucher-recorded:${req.operationId}"
          .toByteArray(Charsets.UTF_8))
   val fingerprint=IdempotencyKeyContract.fingerprint(
      listOf(org,id,s.version,req.signedCustodyEvidenceId,
        approved.approvalRequestId).joinToString("|"))
   val outcome=idempotency.execute(IdempotentCommandSpec(
      req.operationId,actor,"FINANCE_CASH_VOUCHER_RECORDED",
      "FINANCE_CASH_VOUCHER_RECORD",recordId,fingerprint,correlation,
   )){
      lockSourceAndBox(org,s.kind,s.sourceJournal,s.box)
      if(subject.current(org,id)!=s)stale()
      check(jdbc.update(
       """INSERT INTO finance_cash_voucher_record(
         id,operation_id,organization_id,proposal_id,cashbox_id,
         source_kind,source_journal_id,direction,amount,currency,
         approval_request_id,custody_evidence_id,recorded_by_user_id,
         recorded_at,correlation_id)
         VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        recordId,req.operationId,org,id,s.box,s.kind,s.sourceJournal,
        s.direction,s.amount,s.currency,approved.approvalRequestId,
        req.signedCustodyEvidenceId,actor,
        clock.instant().atOffset(ZoneOffset.UTC),correlation,
      )==1)
      audit.append(AuditEventRecord(
        actorUserId=actor,action="FINANCE_CASH_VOUCHER_RECORDED",
        targetType="FINANCE_CASH_VOUCHER_RECORD",targetId=recordId,
        occurredAt=clock.instant(),correlationId=correlation,
        reason="SIGNED_PHYSICAL_CASH_CUSTODY_NO_BANK_TRANSFER",
      ))
      IdempotentCommandOutcome("FINANCE_CASH_VOUCHER_RECORDED",
       """{"id":"$recordId"}""")
   }
   return receipt(s,"RECORDED",approved.approvalRequestId,
      recordId,outcome.replayed)
 }
 /** Only approved, unrecorded operations available to this specific operator. */
 fun operatorQueue(actor:UUID,org:UUID):List<FinanceCashVoucherReceipt> {
   authority(actor,org,"hiltech.finance.cashbox.voucher-operator-authority-key")
   val ids=jdbc.query(
      """SELECT p.id FROM finance_cash_voucher_proposal p
         WHERE p.organization_id=?
         AND EXISTS (SELECT 1 FROM approval_request a
           WHERE a.organization_id=p.organization_id AND a.subject_id=p.id
           AND a.subject_type=? AND a.state='APPROVED')
         AND NOT EXISTS (SELECT 1 FROM finance_cash_voucher_record r
           WHERE r.organization_id=p.organization_id AND r.proposal_id=p.id)
         ORDER BY p.id LIMIT 100""",
      {rs,_->rs.getObject("id",UUID::class.java)},org,FINANCE_CASH_VOUCHER_SUBJECT)
   return ids.mapNotNull { id ->
      val s=subject.current(org,id) ?:return@mapNotNull null
      if(s.custodian!=actor || s.preparedBy==actor)return@mapNotNull null
      val review=engine.subjectStatus(
          ApprovalSubjectRef(org,FINANCE_CASH_VOUCHER_SUBJECT,id,s.version),s.policyKey)
          ?.takeIf {it.state==ApprovalRequestState.APPROVED && it.subjectVersion==s.version}
          ?:return@mapNotNull null
      if(historic(org,id)!=null)return@mapNotNull null
      receipt(s,"APPROVED",review.approvalRequestId)
   }
 }
 /** Operator-only detail; historical records are limited to the recording actor. */
 fun operatorStatus(actor:UUID,org:UUID,id:UUID):FinanceCashVoucherReceipt {
   authority(actor,org,"hiltech.finance.cashbox.voucher-operator-authority-key")
   historic(org,id)?.let { old ->
      val owner=jdbc.query(
        """SELECT recorded_by_user_id FROM finance_cash_voucher_record
           WHERE organization_id=? AND id=?""",
        {rs,_->rs.getObject("recorded_by_user_id",UUID::class.java)},org,old.voucherRecordId)
        .singleOrNull() ?:unavailable()
      if(owner!=actor)unavailable()
      return old
   }
   val s=subject.current(org,id) ?:stale()
   if(s.custodian!=actor || s.preparedBy==actor)unavailable()
   val review=engine.subjectStatus(
       ApprovalSubjectRef(org,FINANCE_CASH_VOUCHER_SUBJECT,id,s.version),s.policyKey)
       ?.takeIf {it.state==ApprovalRequestState.APPROVED && it.subjectVersion==s.version}
       ?:unavailable()
   return receipt(s,"APPROVED",review.approvalRequestId)
 }
 fun status(actor:UUID,org:UUID,id:UUID):FinanceCashVoucherReceipt {
   authority(actor,org,"hiltech.finance.cashbox.reader-authority-key")
   historic(org,id)?.let{return it}
   val s=subject.current(org,id) ?:stale()
   val review=engine.subjectStatus(
      ApprovalSubjectRef(org,FINANCE_CASH_VOUCHER_SUBJECT,id,s.version),
      s.policyKey)
   return receipt(s,review?.state?.name ?: "PROPOSED",
       review?.approvalRequestId)
 }
 private fun historic(org:UUID,id:UUID):FinanceCashVoucherReceipt?=
  jdbc.query(
    """SELECT id,cashbox_id,source_kind,source_journal_id,
       direction,amount,currency,approval_request_id
       FROM finance_cash_voucher_record
       WHERE organization_id=? AND proposal_id=?""",
    {rs,_ ->FinanceCashVoucherReceipt(
       id,org,rs.getObject("cashbox_id",UUID::class.java),
       rs.getString("source_kind"),
       rs.getObject("source_journal_id",UUID::class.java),
       rs.getString("direction"),
       rs.getBigDecimal("amount").toPlainString(),
       rs.getString("currency"),"RECORDED",
       approvalRequestId=rs.getObject("approval_request_id",UUID::class.java),
       voucherRecordId=rs.getObject("id",UUID::class.java),
       physicalCustodyConfirmed=true,
    )},org,id).singleOrNull()
 private fun receipt(
   s:FinanceCashVoucherVerified,state:String,
   approval:UUID?=null,recorded:UUID?=null,replayed:Boolean=false,
 )=FinanceCashVoucherReceipt(
   s.id,s.org,s.box,s.kind,s.sourceJournal,s.direction,
   s.amount.toPlainString(),s.currency,state,approval,recorded,
   replayed,physicalCustodyConfirmed=recorded!=null,
 )
 private fun lockSourceAndBox(org:UUID,kind:String,journal:UUID,box:UUID) {
   val table=when(kind) {
      "AR"->"finance_ar_posted_journal"
      "AP"->"finance_ap_posted_journal"
      else->unavailable()
   }
   if(jdbc.query(
      """SELECT id FROM $table WHERE id=? AND organization_id=? FOR UPDATE""",
      {rs,_ ->rs.getObject("id",UUID::class.java)},
      journal,org).singleOrNull()!=journal)stale()
   if(jdbc.query(
      """SELECT id FROM finance_cashbox_register
         WHERE id=? AND organization_id=? AND status='ACTIVE' FOR UPDATE""",
      {rs,_ ->rs.getObject("id",UUID::class.java)},
      box,org).singleOrNull()!=box)stale()
 }
 private fun authority(actor: UUID, org: UUID, property: String) {
     if (!subject.enabled()) unavailable()
     currentOperationalAuthority(operationalAuthority, approvals, actor, org, property,
         environment.getProperty(property), clock.instant()) ?: unavailable()
 }
 private fun unavailable():Nothing=throw ProductApiException(
   "FINANCE_PHYSICAL_CASH_UNAUTHORIZED",
   "Independent company Finance approval and custodian required.",
   HttpStatus.NOT_FOUND)
 private fun stale():Nothing=throw ProductApiException(
   "FINANCE_PHYSICAL_CASH_STALE",
   "Invoice remaining amount or physical cash custody balance changed.",
   HttpStatus.CONFLICT)
}
@RestController
class FinanceCashVoucherController(private val vouchers:FinanceCashVoucherService) {
 @PostMapping("/v1/finance/organizations/{orgId}/cash-vouchers/{kind}/{journalId}/prepare")
 fun prepare(http:HttpServletRequest,@PathVariable orgId:UUID,
     @PathVariable kind:String,@PathVariable journalId:UUID,
     @RequestBody body:FinanceCashVoucherPrepareRequest,
 ):FinanceCashVoucherReceipt{
   val ctx=HiltechRequestContext.current(http)
   IdempotencyKeyContract.requireMatches(ctx.idempotencyKey,body.operationId)
   return vouchers.prepare(actor(http),orgId,kind,journalId,body,ctx.correlationId)
 }
 @PostMapping("/v1/finance/organizations/{orgId}/cash-vouchers/{id}/request-review")
 fun review(http:HttpServletRequest,@PathVariable orgId:UUID,
     @PathVariable id:UUID,@RequestBody body:FinanceCashVoucherOperation,
 ):FinanceCashVoucherReceipt {
   val ctx=HiltechRequestContext.current(http)
   IdempotencyKeyContract.requireMatches(ctx.idempotencyKey,body.operationId)
   return vouchers.requestReview(
      actor(http),orgId,id,body.operationId,ctx.correlationId)
 }
 @PostMapping("/v1/finance/organizations/{orgId}/cash-vouchers/{id}/record")
 fun record(http:HttpServletRequest,@PathVariable orgId:UUID,
     @PathVariable id:UUID,@RequestBody body:FinanceCashVoucherRecordRequest,
 ):FinanceCashVoucherReceipt {
   val ctx=HiltechRequestContext.current(http)
   IdempotencyKeyContract.requireMatches(ctx.idempotencyKey,body.operationId)
   return vouchers.record(actor(http),orgId,id,body,ctx.correlationId)
 }
 @GetMapping("/v1/finance/organizations/{orgId}/cash-vouchers/operator-queue")
 fun operatorQueue(http:HttpServletRequest,@PathVariable orgId:UUID):List<FinanceCashVoucherReceipt> =
   vouchers.operatorQueue(actor(http),orgId)
 @GetMapping("/v1/finance/organizations/{orgId}/cash-vouchers/{id}/operator-status")
 fun operatorStatus(http:HttpServletRequest,@PathVariable orgId:UUID,@PathVariable id:UUID):FinanceCashVoucherReceipt=
   vouchers.operatorStatus(actor(http),orgId,id)
 @GetMapping("/v1/finance/organizations/{orgId}/cash-vouchers/{id}")
 fun status(http:HttpServletRequest,@PathVariable orgId:UUID,
     @PathVariable id:UUID):FinanceCashVoucherReceipt=
   vouchers.status(actor(http),orgId,id)
 private fun actor(http:HttpServletRequest):UUID=
   HiltechRequestContext.current(http).identityId
      ?.let{runCatching{UUID.fromString(it)}.getOrNull()}
      ?:throw ProductApiException(
       "UNAUTHENTICATED","Company Finance identity required.",
       HttpStatus.UNAUTHORIZED)
}

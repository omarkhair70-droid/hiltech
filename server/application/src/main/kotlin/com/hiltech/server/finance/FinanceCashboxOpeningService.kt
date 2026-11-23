package com.hiltech.server.finance

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
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

data class FinanceCashboxOpeningPrepare(
 val operationId:UUID,val cashboxCode:String,val currency:String,
 val custodianUserId:UUID,val companyCustodyReference:String,
)
data class FinanceCashboxOperation(val operationId:UUID)
data class FinanceCashboxReceipt(
 val proposalId:UUID,val organizationId:UUID,
 val cashboxCode:String,val currency:String,val custodianUserId:UUID,
 val state:String,val approvalRequestId:UUID?=null,
 val replayed:Boolean=false,val cashboxId:UUID?=null,
 val openingBalance:String="0.000000",
)
data class FinanceCashboxBalanceReceipt(
 val cashboxId:UUID,val organizationId:UUID,val code:String,
 val currency:String,val custodianUserId:UUID,
 val cashOnHand:String,val voucherCount:Long,
 val physicalCountReconciled:Boolean=false,
)

/** Approved only with zero opening: no fabricated historical cash supply. */
@Service
class FinanceCashboxOpeningService(
 private val jdbc:JdbcTemplate,
 private val subject:FinanceCashboxOpeningApprovalSubject,
 private val approvals:ApprovalPersistencePort,
 private val engine:ApprovalEngineService,
 private val idempotency:IdempotentCommandExecutor,
 private val audit:AuditEventWriter,
 private val environment:Environment,
 private val clock:Clock,
) {
 @Transactional
 fun prepare(
  actor:UUID,org:UUID,request:FinanceCashboxOpeningPrepare,
  correlation:String,
 ):FinanceCashboxReceipt {
   authority(actor,org,"hiltech.finance.cashbox.preparer-authority-key")
   val code=request.cashboxCode.trim()
   val currency=request.currency.trim()
   val reference=request.companyCustodyReference.trim()
   if(code.length !in 3..64 ||
      currency.length!=3 || !currency.all{it in 'A'..'Z'} ||
      reference.length !in 8..180 ||
      actor==request.custodianUserId)unavailable()
   val now=clock.instant()
   if(!approvals.isActiveOrganizationMember(org,request.custodianUserId,now))
      unavailable()
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
      approvals.actorMatchesAuthority(request.custodianUserId,reviewer,now))
      unavailable()
   val version=FinanceCashboxOpeningVersion.derive(
      org,code,currency,request.custodianUserId,reference,
      policy.versionNumber,reviewer.id,reviewer.version)
   val id=UUID.nameUUIDFromBytes(
      "hiltech:cashbox-opening:${request.operationId}"
       .toByteArray(Charsets.UTF_8))
   val fingerprint=IdempotencyKeyContract.fingerprint(
      listOf(org,code,currency,request.custodianUserId,
       reference,version).joinToString("|"))
   val outcome=idempotency.execute(IdempotentCommandSpec(
      request.operationId,actor,"FINANCE_CASHBOX_OPENING_PROPOSED",
      "FINANCE_CASHBOX_OPENING_PROPOSAL",id,fingerprint,correlation,
   )){
      check(jdbc.update(
       """INSERT INTO finance_cashbox_opening_proposal(
           id,operation_id,organization_id,cashbox_code,currency,
           custodian_user_id,approved_company_reference,opening_balance,
           subject_version,policy_key,prepared_by_user_id,created_at)
          VALUES(?,?,?,?,?,?,?,0,?,?,?,?)""",
        id,request.operationId,org,code,currency,request.custodianUserId,
        reference,version,key,actor,clock.instant().atOffset(ZoneOffset.UTC),
      )==1)
      audit.append(AuditEventRecord(
        actorUserId=actor,action="FINANCE_CASHBOX_OPENING_PROPOSED",
        targetType="FINANCE_CASHBOX_OPENING_PROPOSAL",targetId=id,
        occurredAt=clock.instant(),correlationId=correlation,
        reason="ZERO_INITIAL_CASH_INDEPENDENT_APPROVAL_REQUIRED",
      ))
      IdempotentCommandOutcome(
        "FINANCE_CASHBOX_OPENING_PROPOSED","""{"id":"$id"}""")
   }
   return FinanceCashboxReceipt(id,org,code,currency,
      request.custodianUserId,"PROPOSED",replayed=outcome.replayed)
 }
 fun requestReview(
  actor:UUID,org:UUID,id:UUID,operation:UUID,correlation:String,
 ):FinanceCashboxReceipt {
   authority(actor,org,"hiltech.finance.cashbox.preparer-authority-key")
   val s=subject.current(org,id) ?:stale()
   if(actor!=s.preparedBy)unavailable()
   val ref=ApprovalSubjectRef(org,FINANCE_CASHBOX_OPENING_SUBJECT,id,s.version)
   val old=engine.subjectStatus(ref,s.policyKey)
   if(old!=null) {
      if(old.state!=ApprovalRequestState.PENDING)unavailable()
      return receipt(s,"PENDING",old.approvalRequestId,true)
   }
   val accepted=engine.requestException(
      actorUserId=actor,operationId=operation,subject=ref,
      policyKey=s.policyKey,reasonCode="FINANCE_ZERO_CASHBOX_OPENING",
      safeReasonSummary=null,correlationId=correlation,
      allowAuthorityBypass=false,
   )
   if(!accepted.approvalRequired ||
      accepted.approvalRequestId==null ||
      accepted.state!=ApprovalRequestState.PENDING ||
      accepted.policyVersion!=s.policyVersion)unavailable()
   return receipt(s,"PENDING",accepted.approvalRequestId,accepted.replayed)
 }
 @Transactional
 fun activate(
  actor:UUID,org:UUID,id:UUID,operation:UUID,correlation:String,
 ):FinanceCashboxReceipt {
   authority(actor,org,"hiltech.finance.cashbox.preparer-authority-key")
   historic(org,id)?.let{past->
      val original=jdbc.query(
        """SELECT activation_operation_id,created_by_user_id
           FROM finance_cashbox_register
           WHERE id=? AND organization_id=?""",
        {rs,_ ->rs.getObject("activation_operation_id",UUID::class.java) to
           rs.getObject("created_by_user_id",UUID::class.java)},
        id,org,
      ).singleOrNull() ?:stale()
      if(original.first!=operation || original.second!=actor)unavailable()
      return past.copy(replayed=true)
   }
   val s=subject.current(org,id) ?:stale()
   if(s.preparedBy!=actor)unavailable()
   val ref=ApprovalSubjectRef(org,FINANCE_CASHBOX_OPENING_SUBJECT,id,s.version)
   val approved=engine.subjectStatus(ref,s.policyKey)?.takeIf{
      it.state==ApprovalRequestState.APPROVED &&
      it.subjectVersion==s.version
   } ?:unavailable()
   val fingerprint=IdempotencyKeyContract.fingerprint(
      listOf(org,id,s.version,approved.approvalRequestId).joinToString("|"))
   val outcome=idempotency.execute(IdempotentCommandSpec(
      operation,actor,"FINANCE_CASHBOX_OPENED",
      "FINANCE_CASHBOX_REGISTER",id,fingerprint,correlation,
   )){
      if(subject.current(org,id)!=s)stale()
      check(jdbc.update(
         """INSERT INTO finance_cashbox_register(
             id,organization_id,cashbox_code,currency,custodian_user_id,
             approved_company_reference,opening_balance,status,
             opening_approval_request_id,opening_proposal_id,
             activation_operation_id,created_by_user_id,created_at)
            VALUES(?,?,?,?,?,?,0,'ACTIVE',?,?,?,?,?)""",
         id,org,s.code,s.currency,s.custodian,s.companyReference,
         approved.approvalRequestId,id,operation,actor,
         clock.instant().atOffset(ZoneOffset.UTC))==1)
      audit.append(AuditEventRecord(
         actorUserId=actor,action="FINANCE_CASHBOX_OPENED",
         targetType="FINANCE_CASHBOX_REGISTER",targetId=id,
         occurredAt=clock.instant(),correlationId=correlation,
         reason="COMPANY_AUTHORIZED_ZERO_BALANCE_PHYSICAL_CUSTODY",
      ))
      IdempotentCommandOutcome("FINANCE_CASHBOX_OPENED","""{"id":"$id"}""")
   }
   return receipt(s,"ACTIVE",approved.approvalRequestId,
      outcome.replayed).copy(cashboxId=id)
 }
 fun status(actor:UUID,org:UUID,id:UUID):FinanceCashboxReceipt {
   authority(actor,org,"hiltech.finance.cashbox.reader-authority-key")
   historic(org,id)?.let{return it}
   val s=subject.current(org,id) ?:stale()
   val review=engine.subjectStatus(
      ApprovalSubjectRef(org,FINANCE_CASHBOX_OPENING_SUBJECT,id,s.version),
      s.policyKey)
   return receipt(s,review?.state?.name ?:"PROPOSED",
      review?.approvalRequestId)
 }
 fun balance(
  actor:UUID,org:UUID,id:UUID,
 ):FinanceCashboxBalanceReceipt {
   authority(actor,org,"hiltech.finance.cashbox.reader-authority-key")
   return jdbc.query(
     """SELECT cashbox_code,currency,custodian_user_id,cash_on_hand,
        recorded_vouchers FROM finance_cashbox_balance
        WHERE organization_id=? AND cashbox_id=?""",
     {rs,_ ->FinanceCashboxBalanceReceipt(
        id,org,rs.getString("cashbox_code"),rs.getString("currency"),
        rs.getObject("custodian_user_id",UUID::class.java),
        rs.getBigDecimal("cash_on_hand").toPlainString(),
        rs.getLong("recorded_vouchers"),
     )},org,id).singleOrNull() ?:stale()
 }
 private fun historic(org:UUID,id:UUID):FinanceCashboxReceipt?=jdbc.query(
   """SELECT b.cashbox_code,b.currency,b.custodian_user_id,
        b.opening_approval_request_id
      FROM finance_cashbox_register b
      WHERE b.id=? AND b.organization_id=?""",
   {rs,_ ->FinanceCashboxReceipt(
      id,org,rs.getString("cashbox_code"),rs.getString("currency"),
      rs.getObject("custodian_user_id",UUID::class.java),"ACTIVE",
      approvalRequestId=rs.getObject(
         "opening_approval_request_id",UUID::class.java),
      cashboxId=id,
   )},id,org).singleOrNull()
 private fun receipt(
   s:FinanceCashboxOpeningVerified,state:String,
   approval:UUID?=null,replayed:Boolean=false,
 )=FinanceCashboxReceipt(
   s.id,s.org,s.code,s.currency,s.custodian,state,approval,replayed,
 )
 private fun authority(actor:UUID,org:UUID,property:String) {
   if(!subject.enabled())unavailable()
   val key=environment.getProperty(property,"").trim()
   if(!Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(key))unavailable()
   val now=clock.instant()
   if(!approvals.isActiveOrganizationMember(org,actor,now))unavailable()
   val a=approvals.resolveCurrentAuthority(org,key,now) ?:unavailable()
   if(a.organizationId!=org ||
      !approvals.actorMatchesAuthority(actor,a,now))unavailable()
 }
 private fun unavailable():Nothing=throw ProductApiException(
   "FINANCE_CASHBOX_NOT_AUTHORIZED",
   "Company cashbox opening needs an independent signed approval.",
   HttpStatus.NOT_FOUND)
 private fun stale():Nothing=throw ProductApiException(
   "FINANCE_CASHBOX_STALE",
   "Cashbox approval, custody or exact original source changed.",
   HttpStatus.CONFLICT)
}
@RestController
class FinanceCashboxOpeningController(
 private val cashboxes:FinanceCashboxOpeningService,
) {
 @PostMapping("/v1/finance/organizations/{orgId}/cashboxes/prepare-opening")
 fun prepare(http:HttpServletRequest,@PathVariable orgId:UUID,
     @RequestBody body:FinanceCashboxOpeningPrepare):FinanceCashboxReceipt{
   val ctx=HiltechRequestContext.current(http)
   IdempotencyKeyContract.requireMatches(ctx.idempotencyKey,body.operationId)
   return cashboxes.prepare(actor(http),orgId,body,ctx.correlationId)
 }
 @PostMapping("/v1/finance/organizations/{orgId}/cashboxes/{id}/request-opening-review")
 fun request(http:HttpServletRequest,@PathVariable orgId:UUID,
     @PathVariable id:UUID,@RequestBody body:FinanceCashboxOperation,
 ):FinanceCashboxReceipt{
   val ctx=HiltechRequestContext.current(http)
   IdempotencyKeyContract.requireMatches(ctx.idempotencyKey,body.operationId)
   return cashboxes.requestReview(
      actor(http),orgId,id,body.operationId,ctx.correlationId)
 }
 @PostMapping("/v1/finance/organizations/{orgId}/cashboxes/{id}/activate")
 fun activate(http:HttpServletRequest,@PathVariable orgId:UUID,
     @PathVariable id:UUID,@RequestBody body:FinanceCashboxOperation,
 ):FinanceCashboxReceipt{
   val ctx=HiltechRequestContext.current(http)
   IdempotencyKeyContract.requireMatches(ctx.idempotencyKey,body.operationId)
   return cashboxes.activate(
     actor(http),orgId,id,body.operationId,ctx.correlationId)
 }
 @GetMapping("/v1/finance/organizations/{orgId}/cashboxes/{id}")
 fun status(http:HttpServletRequest,@PathVariable orgId:UUID,
     @PathVariable id:UUID):FinanceCashboxReceipt =
    cashboxes.status(actor(http),orgId,id)
 @GetMapping("/v1/finance/organizations/{orgId}/cashboxes/{id}/balance")
 fun balance(http:HttpServletRequest,@PathVariable orgId:UUID,
     @PathVariable id:UUID):FinanceCashboxBalanceReceipt =
    cashboxes.balance(actor(http),orgId,id)
 private fun actor(http:HttpServletRequest):UUID=
   HiltechRequestContext.current(http).identityId
    ?.let{runCatching{UUID.fromString(it)}.getOrNull()}
    ?:throw ProductApiException(
        "UNAUTHENTICATED","Company Finance identity required.",
        HttpStatus.UNAUTHORIZED)
}

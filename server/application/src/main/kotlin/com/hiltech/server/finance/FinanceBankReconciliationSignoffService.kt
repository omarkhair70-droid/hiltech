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

data class FinanceBankReconciliationPrepare(
    val operationId: UUID,
    val bankStatementIntakeId: UUID,
    val companyReconciliationReference: String,
)
data class FinanceBankReconciliationOperation(val operationId: UUID)
data class FinanceBankReconciliationSign(
    val operationId: UUID, val signedEvidenceId: UUID,
)
data class FinanceBankReconciliationReceipt(
    val proposalId: UUID, val organizationId: UUID,
    val intakeId: UUID, val accountId: UUID,
    val statementSha256: String, val matchingSha256: String,
    val currency: String, val lineCount: Long,
    val totalCredit: String, val totalDebit: String,
    val totalMatched: String, val state: String,
    val approvalRequestId: UUID?=null,
    val reconciliationRecordId: UUID?=null,
    val replayed: Boolean=false,
    val internallyReconciled: Boolean=false,
    val verifiedByBankProvider: Boolean=false,
    val bankTransferInitiated: Boolean=false,
)

/** Independent internal finance reconciliation, not a banking provider callback. */
@Service
class FinanceBankReconciliationSignoffService(
    private val jdbc: JdbcTemplate,
    private val subject: FinanceBankReconciliationApprovalSubject,
    private val approvals: ApprovalPersistencePort,
    private val engine: ApprovalEngineService,
    private val idempotency: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val environment: Environment,
    private val clock: Clock,
) {
    @Transactional
    fun prepare(
        actor: UUID, org: UUID, request: FinanceBankReconciliationPrepare,
        correlation: String,
    ): FinanceBankReconciliationReceipt {
        authority(actor,org,"hiltech.finance.bank-reconciliation.signoff-preparer-authority-key")
        val reference=request.companyReconciliationReference.trim()
        if(reference.length !in 8..180)unavailable()
        val key=subject.policyKey() ?:unavailable()
        val now=clock.instant()
        val policy=approvals.loadActivePolicy(org,key,now)?.takeIf {
            it.organizationId==org && it.policyKey==key && it.approvalRequired &&
                it.mode==ApprovalPolicyMode.SINGLE && !it.authorityKey.isNullOrBlank()
        } ?:unavailable()
        val reviewer=approvals.resolveCurrentAuthority(
            org,policy.authorityKey!!,now,
        ) ?:unavailable()
        if(reviewer.organizationId!=org ||
            approvals.actorMatchesAuthority(actor,reviewer,now))unavailable()
        lockLines(org,request.bankStatementIntakeId)
        val s=subject.snapshot(org,request.bankStatementIntakeId)
            ?.takeIf{it.fullyMatched && it.lines>0} ?:unavailable()
        val version=FinanceSignedBankReconciliationVersion.derive(
            org,s.intake,s.account,s.statementHash,s.matchHash,s.currency,
            s.lines,s.credits,s.debits,s.matched,reference,
            policy.versionNumber,reviewer.id,reviewer.version,
        )
        val id=UUID.nameUUIDFromBytes(
            "hiltech:bank-reconciliation:${request.operationId}".toByteArray(Charsets.UTF_8))
        val fingerprint=IdempotencyKeyContract.fingerprint(
            listOf(org,s.intake,s.account,s.statementHash,s.matchHash,
                s.lines,reference,version).joinToString("|"))
        val outcome=idempotency.execute(IdempotentCommandSpec(
            request.operationId,actor,"FINANCE_BANK_RECONCILIATION_PROPOSED",
            "FINANCE_BANK_RECONCILIATION_PROPOSAL",id,fingerprint,correlation,
        )){
            lockLines(org,s.intake)
            if(subject.snapshot(org,s.intake)!=s)stale()
            check(jdbc.update(
                """INSERT INTO finance_bank_reconciliation_proposal(
                 id,operation_id,organization_id,intake_id,account_id,
                 statement_sha256,match_sha256,expected_line_count,
                 total_credit,total_debit,total_matched,reconciliation_reference,
                 subject_version,policy_key,prepared_by_user_id,created_at)
                 VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                id,request.operationId,org,s.intake,s.account,
                s.statementHash,s.matchHash,s.lines,s.credits,s.debits,
                s.matched,reference,version,key,actor,
                clock.instant().atOffset(ZoneOffset.UTC),
            )==1)
            audit.append(AuditEventRecord(
                actorUserId=actor,
                action="FINANCE_BANK_RECONCILIATION_PROPOSED",
                targetType="FINANCE_BANK_RECONCILIATION_PROPOSAL",targetId=id,
                occurredAt=clock.instant(),correlationId=correlation,
                reason="COMPANY_INDEPENDENT_RECONCILIATION_REVIEW_REQUIRED",
            ))
            IdempotentCommandOutcome(
                "FINANCE_BANK_RECONCILIATION_PROPOSED","""{"id":"$id"}""")
        }
        return receipt(id,org,s,"PROPOSED",replayed=outcome.replayed)
    }

    fun requestReview(
        actor:UUID,org:UUID,id:UUID,operation:UUID,correlation:String,
    ):FinanceBankReconciliationReceipt {
        authority(actor,org,"hiltech.finance.bank-reconciliation.signoff-preparer-authority-key")
        val p=subject.current(org,id) ?:stale()
        if(p.preparedBy!=actor)unavailable()
        val ref=ApprovalSubjectRef(org,FINANCE_BANK_RECONCILIATION_SUBJECT,id,p.version)
        val old=engine.subjectStatus(ref,p.policyKey)
        if(old!=null) {
            if(old.state!=ApprovalRequestState.PENDING)unavailable()
            return receipt(id,org,p.snapshot,"PENDING",old.approvalRequestId,
                replayed=true)
        }
        val requested=engine.requestException(
            actorUserId=actor,operationId=operation,subject=ref,
            policyKey=p.policyKey,reasonCode="FINANCE_BANK_RECONCILIATION_REVIEW",
            safeReasonSummary=null,correlationId=correlation,
            allowAuthorityBypass=false,
        )
        if(!requested.approvalRequired || requested.approvalRequestId==null ||
            requested.state!=ApprovalRequestState.PENDING ||
            requested.policyVersion!=p.policyVersion)unavailable()
        return receipt(id,org,p.snapshot,"PENDING",
            requested.approvalRequestId,replayed=requested.replayed)
    }

    @Transactional
    fun sign(
        actor:UUID,org:UUID,id:UUID,request:FinanceBankReconciliationSign,
        correlation:String,
    ):FinanceBankReconciliationReceipt {
        authority(actor,org,"hiltech.finance.bank-reconciliation.signoff-operator-authority-key")
        historic(org,id)?.let { old ->
            val original=jdbc.query(
                """SELECT operation_id,recorded_by_user_id,signed_evidence_id
                   FROM finance_bank_reconciliation_record
                   WHERE organization_id=? AND id=?""",
                {rs,_ ->Triple(rs.getObject("operation_id",UUID::class.java),
                    rs.getObject("recorded_by_user_id",UUID::class.java),
                    rs.getObject("signed_evidence_id",UUID::class.java))},
                org,old.reconciliationRecordId,
            ).singleOrNull() ?:unavailable()
            if(original.first!=request.operationId || original.second!=actor ||
                original.third!=request.signedEvidenceId)unavailable()
            return old.copy(replayed=true)
        }
        val p=subject.current(org,id) ?:stale()
        if(p.preparedBy==actor)unavailable()
        val ref=ApprovalSubjectRef(org,FINANCE_BANK_RECONCILIATION_SUBJECT,id,p.version)
        val approval=engine.subjectStatus(ref,p.policyKey)?.takeIf {
            it.state==ApprovalRequestState.APPROVED && it.subjectVersion==p.version
        } ?:unavailable()
        val recordId=UUID.nameUUIDFromBytes(
            "hiltech:bank-reconciliation-record:${request.operationId}"
                .toByteArray(Charsets.UTF_8))
        val fingerprint=IdempotencyKeyContract.fingerprint(
            listOf(org,id,p.version,request.signedEvidenceId,
                approval.approvalRequestId).joinToString("|"))
        val outcome=idempotency.execute(IdempotentCommandSpec(
            request.operationId,actor,"FINANCE_BANK_RECONCILIATION_SIGNED",
            "FINANCE_BANK_RECONCILIATION_RECORD",recordId,fingerprint,correlation,
        )){
            lockLines(org,p.snapshot.intake)
            if(subject.current(org,id)!=p)stale()
            check(jdbc.update(
                """INSERT INTO finance_bank_reconciliation_record(
                  id,operation_id,organization_id,proposal_id,intake_id,
                  approval_request_id,signed_evidence_id,recorded_by_user_id,
                  recorded_at,correlation_id) VALUES(?,?,?,?,?,?,?,?,?,?)""",
                recordId,request.operationId,org,id,p.snapshot.intake,
                approval.approvalRequestId,request.signedEvidenceId,actor,
                clock.instant().atOffset(ZoneOffset.UTC),correlation,
            )==1)
            audit.append(AuditEventRecord(
                actorUserId=actor,action="FINANCE_BANK_RECONCILIATION_SIGNED",
                targetType="FINANCE_BANK_RECONCILIATION_RECORD",
                targetId=recordId,occurredAt=clock.instant(),
                correlationId=correlation,
                reason="INTERNAL_COMPANY_LEDGER_MATCH_NOT_BANK_PROVIDER_CONFIRMED",
            ))
            IdempotentCommandOutcome(
                "FINANCE_BANK_RECONCILIATION_SIGNED","""{"id":"$recordId"}""")
        }
        return receipt(id,org,p.snapshot,"SIGNED",approval.approvalRequestId,
            recordId,outcome.replayed,true)
    }

    /** Only approved, unrecorded operations available to this specific operator. */
 fun operatorQueue(actor:UUID,org:UUID):List<FinanceBankReconciliationReceipt> {
   authority(actor,org,"hiltech.finance.bank-reconciliation.signoff-operator-authority-key")
   val ids=jdbc.query(
      """SELECT p.id FROM finance_bank_reconciliation_proposal p
         WHERE p.organization_id=?
         AND EXISTS (SELECT 1 FROM approval_request a
           WHERE a.organization_id=p.organization_id AND a.subject_id=p.id
           AND a.subject_type=? AND a.state='APPROVED')
         AND NOT EXISTS (SELECT 1 FROM finance_bank_reconciliation_record r
           WHERE r.organization_id=p.organization_id AND r.proposal_id=p.id)
         ORDER BY p.id LIMIT 100""",
      {rs,_->rs.getObject("id",UUID::class.java)},org,FINANCE_BANK_RECONCILIATION_SUBJECT)
   return ids.mapNotNull { id ->
      val s=subject.current(org,id) ?:return@mapNotNull null
      if(s.preparedBy==actor)return@mapNotNull null
      val review=engine.subjectStatus(
          ApprovalSubjectRef(org,FINANCE_BANK_RECONCILIATION_SUBJECT,id,s.version),s.policyKey)
          ?.takeIf {it.state==ApprovalRequestState.APPROVED && it.subjectVersion==s.version}
          ?:return@mapNotNull null
      if(historic(org,id)!=null)return@mapNotNull null
      receipt(id,org,s.snapshot,"APPROVED",review.approvalRequestId)
   }
 }
 /** Operator-only detail; historical records are limited to the recording actor. */
 fun operatorStatus(actor:UUID,org:UUID,id:UUID):FinanceBankReconciliationReceipt {
   authority(actor,org,"hiltech.finance.bank-reconciliation.signoff-operator-authority-key")
   historic(org,id)?.let { old ->
      val owner=jdbc.query(
        """SELECT recorded_by_user_id FROM finance_bank_reconciliation_record
           WHERE organization_id=? AND id=?""",
        {rs,_->rs.getObject("recorded_by_user_id",UUID::class.java)},org,old.reconciliationRecordId)
        .singleOrNull() ?:unavailable()
      if(owner!=actor)unavailable()
      return old
   }
   val s=subject.current(org,id) ?:stale()
   if(s.preparedBy==actor)unavailable()
   val review=engine.subjectStatus(
       ApprovalSubjectRef(org,FINANCE_BANK_RECONCILIATION_SUBJECT,id,s.version),s.policyKey)
       ?.takeIf {it.state==ApprovalRequestState.APPROVED && it.subjectVersion==s.version}
       ?:unavailable()
   return receipt(id,org,s.snapshot,"APPROVED",review.approvalRequestId)
 }
 fun status(actor:UUID,org:UUID,id:UUID):FinanceBankReconciliationReceipt {
        authority(actor,org,"hiltech.finance.bank-reconciliation.reader-authority-key")
        historic(org,id)?.let{return it}
        val p=subject.current(org,id) ?:stale()
        val a=engine.subjectStatus(
            ApprovalSubjectRef(org,FINANCE_BANK_RECONCILIATION_SUBJECT,id,p.version),
            p.policyKey,
        )
        return receipt(id,org,p.snapshot,a?.state?.name?:"PROPOSED",
            a?.approvalRequestId)
    }

    private fun historic(org:UUID,id:UUID):FinanceBankReconciliationReceipt? {
        val record=jdbc.query(
            """SELECT r.id,r.approval_request_id,p.intake_id
               FROM finance_bank_reconciliation_record r
               JOIN finance_bank_reconciliation_proposal p
                ON p.id=r.proposal_id AND p.organization_id=r.organization_id
               WHERE r.organization_id=? AND r.proposal_id=?""",
            {rs,_ ->Triple(rs.getObject("id",UUID::class.java),
                rs.getObject("approval_request_id",UUID::class.java),
                rs.getObject("intake_id",UUID::class.java))},org,id,
        ).singleOrNull() ?:return null
        val s=subject.snapshot(org,record.third) ?:stale()
        return receipt(id,org,s,"SIGNED",record.second,record.first,
            signed=true)
    }

    private fun receipt(
        id:UUID,org:UUID,s:FinanceSignedBankSnapshot,state:String,
        approval:UUID?=null,record:UUID?=null,
        replayed:Boolean=false,signed:Boolean=false,
    )=FinanceBankReconciliationReceipt(
        id,org,s.intake,s.account,s.statementHash,s.matchHash,s.currency,
        s.lines,s.credits.toPlainString(),s.debits.toPlainString(),
        s.matched.toPlainString(),state,approval,record,replayed,signed,
    )

    private fun lockLines(org:UUID,intake:UUID) {
        jdbc.query("SELECT finance_bank_reconciliation_lock_lines(?,?)",
            {rs,_ ->rs.getObject(1)},org,intake)
    }
    private fun authority(actor:UUID,org:UUID,setting:String) {
        if(!subject.enabled())unavailable()
        val key=environment.getProperty(setting,"").trim()
        if(!Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(key))unavailable()
        val now=clock.instant()
        if(!approvals.isActiveOrganizationMember(org,actor,now))unavailable()
        val reviewer=approvals.resolveCurrentAuthority(org,key,now) ?:unavailable()
        if(reviewer.organizationId!=org ||
            !approvals.actorMatchesAuthority(actor,reviewer,now))unavailable()
    }
    private fun unavailable():Nothing=throw ProductApiException(
        "FINANCE_BANK_RECONCILIATION_NOT_AUTHORIZED",
        "Verified source, active company policy and independent signoff required.",
        HttpStatus.NOT_FOUND,
    )
    private fun stale():Nothing=throw ProductApiException(
        "FINANCE_BANK_RECONCILIATION_STALE",
        "Statement, bank allocations or approval configuration changed.",
        HttpStatus.CONFLICT,
    )
}

@RestController
class FinanceBankReconciliationSignoffController(
    private val service:FinanceBankReconciliationSignoffService,
) {
    @PostMapping("/v1/finance/organizations/{orgId}/bank-reconciliations/prepare")
    fun prepare(http:HttpServletRequest,@PathVariable orgId:UUID,
        @RequestBody request:FinanceBankReconciliationPrepare
    ):FinanceBankReconciliationReceipt {
        val c=HiltechRequestContext.current(http)
        IdempotencyKeyContract.requireMatches(c.idempotencyKey,request.operationId)
        return service.prepare(actor(http),orgId,request,c.correlationId)
    }
    @PostMapping("/v1/finance/organizations/{orgId}/bank-reconciliations/{id}/request-review")
    fun review(http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable id:UUID,@RequestBody request:FinanceBankReconciliationOperation
    ):FinanceBankReconciliationReceipt {
        val c=HiltechRequestContext.current(http)
        IdempotencyKeyContract.requireMatches(c.idempotencyKey,request.operationId)
        return service.requestReview(actor(http),orgId,id,request.operationId,c.correlationId)
    }
    @PostMapping("/v1/finance/organizations/{orgId}/bank-reconciliations/{id}/sign")
    fun sign(http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable id:UUID,@RequestBody request:FinanceBankReconciliationSign
    ):FinanceBankReconciliationReceipt {
        val c=HiltechRequestContext.current(http)
        IdempotencyKeyContract.requireMatches(c.idempotencyKey,request.operationId)
        return service.sign(actor(http),orgId,id,request,c.correlationId)
    }
 @GetMapping("/v1/finance/organizations/{orgId}/bank-reconciliations/operator-queue")
 fun operatorQueue(http:HttpServletRequest,@PathVariable orgId:UUID):List<FinanceBankReconciliationReceipt> =
   service.operatorQueue(actor(http),orgId)
 @GetMapping("/v1/finance/organizations/{orgId}/bank-reconciliations/{id}/operator-status")
 fun operatorStatus(http:HttpServletRequest,@PathVariable orgId:UUID,@PathVariable id:UUID):FinanceBankReconciliationReceipt=
   service.operatorStatus(actor(http),orgId,id)
    @GetMapping("/v1/finance/organizations/{orgId}/bank-reconciliations/{id}")
    fun status(http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable id:UUID
    ):FinanceBankReconciliationReceipt=service.status(actor(http),orgId,id)
    private fun actor(http:HttpServletRequest):UUID =
        HiltechRequestContext.current(http).identityId
            ?.let{runCatching{UUID.fromString(it)}.getOrNull()}
            ?:throw ProductApiException(
                "UNAUTHENTICATED","Company finance identity required.",
                HttpStatus.UNAUTHORIZED,
            )
}

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
import java.math.BigDecimal
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

data class FinanceEmployeeSettlementPrepare(
    val operationId:UUID,val action:String,
    val amount:String,val settlementEvidenceId:UUID,
    val settlementEvidenceSha256:String,val bankStatementLineId:UUID?=null,
)
data class FinanceEmployeeSettlementOperation(val operationId:UUID)
data class FinanceEmployeeSettlementReceipt(
    val proposalId:UUID,val organizationId:UUID,
    val fundingRecordId:UUID,val employeeId:UUID,val action:String,
    val amount:String,val currency:String,val state:String,
    val approvalRequestId:UUID?=null,
    val settlementRecordId:UUID?=null,val replayed:Boolean=false,
    val settlementRecorded:Boolean=false,val bankTransferInitiated:Boolean=false,
    val payrollDeductionInitiated:Boolean=false,
)

/**
 * Bank return and custody receipts are distinct immutable events; no payroll
 * deduction, external bank transfer or retrospective accounting rewrite.
 */
@Service
class FinanceEmployeeSettlementService(
    private val jdbc:JdbcTemplate,
    private val subject:FinanceEmployeeSettlementApprovalSubject,
    private val approvals:ApprovalPersistencePort,
    private val engine:ApprovalEngineService,
    private val idempotency:IdempotentCommandExecutor,
    private val audit:AuditEventWriter,
    private val environment:Environment,
    private val clock:Clock,
) {
    @Transactional
    fun prepare(
        actor:UUID,org:UUID,funding:UUID,
        request:FinanceEmployeeSettlementPrepare,correlation:String,
    ):FinanceEmployeeSettlementReceipt {
        authority(actor,org,
            "hiltech.finance.employee-settlement.preparer-authority-key")
        val source=jdbc.query(
            """SELECT employee_id,draft_kind,amount,currency
               FROM finance_employee_funding_record
               WHERE id=? AND organization_id=?""",
            {rs,_ ->SettlementFundingSource(
                rs.getObject("employee_id",UUID::class.java),
                rs.getString("draft_kind"),rs.getBigDecimal("amount"),
                rs.getString("currency"))},
            funding,org,
        ).singleOrNull() ?: stale()
        val action=request.action
        if(source.kind=="EMPLOYEE_ADVANCE" &&
            action!="ADVANCE_RECOVERY_CONFIRMED")unavailable()
        if(source.kind=="FINANCIAL_IMPREST" && action !in setOf(
            "IMPREST_CASH_RETURN_CONFIRMED","IMPREST_RECEIPTS_ACCEPTED"
        ))unavailable()
        if(action=="IMPREST_RECEIPTS_ACCEPTED") {
            if(request.bankStatementLineId!=null)unavailable()
        } else {
            if(request.bankStatementLineId==null)unavailable()
        }
        val amount=runCatching{BigDecimal(request.amount)}
            .getOrNull() ?: unavailable()
        if(amount.signum()<=0 ||
           amount.stripTrailingZeros().scale()>6 ||
           amount.stripTrailingZeros().precision()>24 ||
           amount>source.amount)unavailable()
        val remaining=subject.remaining(org,funding,source.amount)
        if(amount>remaining)stale()
        val bank=request.bankStatementLineId?.let {
            subject.bank(org,it) ?: stale()
        }
        if(bank!=null &&
            (bank.flow!="CREDIT" ||
                bank.currency!=source.currency || bank.amount<amount))
            unavailable()
        val id=UUID.nameUUIDFromBytes(
            "hiltech:employee-settlement:${request.operationId}"
                .toByteArray(Charsets.UTF_8),
        )
        if(!subject.evidenceReady(
            org,id,request.settlementEvidenceId,
            request.settlementEvidenceSha256))unavailable()
        val now=clock.instant()
        val key=subject.policyKey() ?: unavailable()
        val policy=approvals.loadActivePolicy(org,key,now)?.takeIf{
            it.organizationId==org && it.policyKey==key &&
                it.approvalRequired && it.mode==ApprovalPolicyMode.SINGLE &&
                !it.authorityKey.isNullOrBlank()
        } ?: unavailable()
        val reviewer=approvals.resolveCurrentAuthority(
            org,policy.authorityKey!!,now,
        ) ?: unavailable()
        if(reviewer.organizationId!=org ||
            approvals.actorMatchesAuthority(actor,reviewer,now))unavailable()
        val version=FinanceEmployeeSettlementReviewVersion.derive(
            org,funding,source.employeeId,action,
            request.settlementEvidenceId,request.settlementEvidenceSha256,
            request.bankStatementLineId,amount,source.currency,
            policy.versionNumber,reviewer.id,reviewer.version,
        )
        val fingerprint=IdempotencyKeyContract.fingerprint(
            listOf(org,funding,action,amount,request.settlementEvidenceId,
                request.settlementEvidenceSha256,
                request.bankStatementLineId,version).joinToString("|"),
        )
        val outcome=idempotency.execute(IdempotentCommandSpec(
            request.operationId,actor,"FINANCE_EMPLOYEE_SETTLEMENT_PROPOSED",
            "FINANCE_EMPLOYEE_SETTLEMENT_PROPOSAL",id,
            fingerprint,correlation,
        )){
            val locked=jdbc.query(
                """SELECT id FROM finance_employee_funding_record
                   WHERE id=? AND organization_id=? FOR UPDATE""",
                {rs,_ ->rs.getObject("id",UUID::class.java)},
                funding,org,
            ).singleOrNull() ?: stale()
            if(locked!=funding ||
                amount>subject.remaining(org,funding,source.amount) ||
                !subject.evidenceReady(
                    org,id,request.settlementEvidenceId,
                    request.settlementEvidenceSha256))stale()
            check(jdbc.update(
                """
                INSERT INTO finance_employee_settlement_proposal(
                  id,operation_id,organization_id,funding_record_id,
                  employee_id,action,bank_statement_line_id,
                  settlement_evidence_id,settlement_evidence_sha256,
                  amount,currency,review_version,policy_key,
                  prepared_by_user_id,created_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                id,request.operationId,org,funding,source.employeeId,
                action,request.bankStatementLineId,
                request.settlementEvidenceId,request.settlementEvidenceSha256,
                amount,source.currency,version,key,actor,
                clock.instant().atOffset(ZoneOffset.UTC),
            )==1)
            audit.append(AuditEventRecord(
                actorUserId=actor,action="FINANCE_EMPLOYEE_SETTLEMENT_PROPOSED",
                targetType="FINANCE_EMPLOYEE_SETTLEMENT_PROPOSAL",targetId=id,
                occurredAt=clock.instant(),correlationId=correlation,
                reason="AWAITING_INDEPENDENT_SETTLEMENT_REVIEW",
            ))
            IdempotentCommandOutcome(
                "FINANCE_EMPLOYEE_SETTLEMENT_PROPOSED","""{"id":"$id"}""",
            )
        }
        return FinanceEmployeeSettlementReceipt(
            id,org,funding,source.employeeId,action,
            amount.toPlainString(),source.currency,"PROPOSED",
            replayed=outcome.replayed,
        )
    }
    fun requestReview(
        actor:UUID,org:UUID,id:UUID,operation:UUID,correlation:String,
    ):FinanceEmployeeSettlementReceipt {
        authority(actor,org,
            "hiltech.finance.employee-settlement.preparer-authority-key")
        val snap=subject.current(org,id) ?: stale()
        if(snap.preparedBy!=actor)unavailable()
        val ref=ApprovalSubjectRef(
            org,FINANCE_EMPLOYEE_SETTLEMENT_SUBJECT,id,snap.reviewVersion,
        )
        val existing=engine.subjectStatus(ref,snap.policyKey)
        if(existing!=null){
            if(existing.state!=ApprovalRequestState.PENDING)unavailable()
            return receipt(snap,"PENDING",existing.approvalRequestId,
                replayed=true)
        }
        val approved=engine.requestException(
            actorUserId=actor,operationId=operation,subject=ref,
            policyKey=snap.policyKey,
            reasonCode="FINANCE_EMPLOYEE_SETTLEMENT_INDEPENDENT_REVIEW",
            safeReasonSummary=null,correlationId=correlation,
            allowAuthorityBypass=false,
        )
        if(!approved.approvalRequired || approved.approvalRequestId==null ||
            approved.state!=ApprovalRequestState.PENDING ||
            approved.policyKey!=snap.policyKey ||
            approved.policyVersion!=snap.policyVersion)unavailable()
        return receipt(snap,"PENDING",approved.approvalRequestId,
            replayed=approved.replayed)
    }
    @Transactional
    fun record(
        actor:UUID,org:UUID,id:UUID,operation:UUID,correlation:String,
    ):FinanceEmployeeSettlementReceipt {
        authority(actor,org,
            "hiltech.finance.employee-settlement.operator-authority-key")
        historical(org,id)?.let {old->
            val record=jdbc.query(
                """SELECT operation_id,recorded_by_user_id
                   FROM finance_employee_settlement_record
                   WHERE id=? AND organization_id=?""",
                {rs,_ ->rs.getObject("operation_id",UUID::class.java) to
                    rs.getObject("recorded_by_user_id",UUID::class.java)},
                old.settlementRecordId,org,
            ).singleOrNull() ?: unavailable()
            if(record.first!=operation || record.second!=actor)unavailable()
            return old.copy(replayed=true)
        }
        val snap=subject.current(org,id) ?: stale()
        if(snap.preparedBy==actor)unavailable()
        val ref=ApprovalSubjectRef(
            org,FINANCE_EMPLOYEE_SETTLEMENT_SUBJECT,id,snap.reviewVersion,
        )
        val approved=engine.subjectStatus(ref,snap.policyKey)?.takeIf {
            it.state==ApprovalRequestState.APPROVED &&
                it.subjectVersion==snap.reviewVersion
        } ?: unavailable()
        val recordId=UUID.nameUUIDFromBytes(
            "hiltech:employee-settled:$operation".toByteArray(Charsets.UTF_8),
        )
        val fingerprint=IdempotencyKeyContract.fingerprint(
            listOf(org,id,snap.reviewVersion,
                approved.approvalRequestId,snap.evidenceSha256)
                .joinToString("|"),
        )
        val outcome=idempotency.execute(IdempotentCommandSpec(
            operation,actor,"FINANCE_EMPLOYEE_SETTLEMENT_RECORDED",
            "FINANCE_EMPLOYEE_SETTLEMENT_RECORD",recordId,
            fingerprint,correlation,
        )){
            val locked=jdbc.query(
                """SELECT id FROM finance_employee_funding_record
                   WHERE id=? AND organization_id=? FOR UPDATE""",
                {rs,_ ->rs.getObject("id",UUID::class.java)},
                snap.fundingRecordId,org,
            ).singleOrNull() ?: stale()
            if(locked!=snap.fundingRecordId)stale()
            snap.bankLineId?.let {bank->
                if(jdbc.query(
                    """SELECT id FROM finance_bank_statement_intake_line
                       WHERE id=? AND organization_id=? FOR UPDATE""",
                    {rs,_ ->rs.getObject("id",UUID::class.java)},
                    bank,org,
                ).singleOrNull()!=bank)stale()
            }
            if(subject.current(org,id)!=snap)stale()
            val previous=snap.fundedAmount.subtract(snap.remainingBefore)
            val values=FinanceEmployeeSettlementPlan(
                organizationId=org,employeeId=snap.employeeId,
                financeDraftId=snap.fundingRecordId,
                kind=if(snap.fundedKind=="EMPLOYEE_ADVANCE")
                    FinanceEmployeeSettlementKind.EMPLOYEE_ADVANCE
                else FinanceEmployeeSettlementKind.FINANCIAL_IMPREST,
                action=FinanceEmployeeSettlementAction.valueOf(snap.action),
                sourceAmount=snap.fundedAmount,currency=snap.currency,
                sourceApproved=true,approvedFundingRecorded=true,
                sourceBankTransactionId=snap.fundingBankLineId,
                sourceBankEvidenceSha256=snap.fundingStatementSha,
                settlementOperationId=operation,
                settlementEvidenceId=snap.evidenceId,
                evidenceFinalized=subject.evidenceReady(
                    org,id,snap.evidenceId,snap.evidenceSha256),
                settlementAmount=snap.amount,previouslySettled=previous,
                independentReviewerApproved=true,
                recoveryMethod=if(snap.bankLineId!=null)
                    "CONFIRMED_BANK_CREDIT" else null,
                settlementBankTransactionId=snap.bankLineId,
                settlementBankEvidenceSha256=snap.bankStatementSha,
            )
            runCatching{FinanceEmployeeSettlementRules.validate(values)}
                .getOrNull() ?: unavailable()
            check(jdbc.update(
                """
                INSERT INTO finance_employee_settlement_record(
                  id,operation_id,organization_id,proposal_id,
                  funding_record_id,employee_id,action,bank_statement_line_id,
                  settlement_evidence_id,approval_request_id,amount,currency,
                  recorded_by_user_id,recorded_at,correlation_id
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                recordId,operation,org,id,snap.fundingRecordId,
                snap.employeeId,snap.action,snap.bankLineId,snap.evidenceId,
                approved.approvalRequestId,snap.amount,snap.currency,
                actor,clock.instant().atOffset(ZoneOffset.UTC),correlation,
            )==1)
            audit.append(AuditEventRecord(
                actorUserId=actor,action="FINANCE_EMPLOYEE_SETTLEMENT_RECORDED",
                targetType="FINANCE_EMPLOYEE_SETTLEMENT_RECORD",
                targetId=recordId,occurredAt=clock.instant(),
                correlationId=correlation,
                reason="AUTHORIZED_EMPLOYEE_SETTLEMENT_NO_NEW_BANK_TRANSFER",
            ))
            IdempotentCommandOutcome(
                "FINANCE_EMPLOYEE_SETTLEMENT_RECORDED","""{"id":"$recordId"}""",
            )
        }
        return receipt(snap,"RECORDED",approved.approvalRequestId,
            recordId,outcome.replayed)
    }
    fun status(
        actor:UUID,org:UUID,id:UUID,
    ):FinanceEmployeeSettlementReceipt {
        authority(actor,org,
            "hiltech.finance.employee-settlement.preparer-authority-key")
        historical(org,id)?.let{return it}
        val snap=subject.current(org,id) ?: stale()
        val prior=engine.subjectStatus(
            ApprovalSubjectRef(org,FINANCE_EMPLOYEE_SETTLEMENT_SUBJECT,
                id,snap.reviewVersion),snap.policyKey,
        )
        return receipt(snap,prior?.state?.name ?: "PROPOSED",
            prior?.approvalRequestId)
    }
    private fun historical(org:UUID,id:UUID):FinanceEmployeeSettlementReceipt? =
        jdbc.query(
            """
            SELECT r.id,r.funding_record_id,r.employee_id,r.action,r.amount,
              r.currency,r.approval_request_id
            FROM finance_employee_settlement_record r
            WHERE r.organization_id=? AND r.proposal_id=?
            """.trimIndent(),
            {rs,_ ->FinanceEmployeeSettlementReceipt(
                proposalId=id,organizationId=org,
                fundingRecordId=rs.getObject("funding_record_id",UUID::class.java),
                employeeId=rs.getObject("employee_id",UUID::class.java),
                action=rs.getString("action"),
                amount=rs.getBigDecimal("amount").toPlainString(),
                currency=rs.getString("currency"),state="RECORDED",
                approvalRequestId=rs.getObject("approval_request_id",UUID::class.java),
                settlementRecordId=rs.getObject("id",UUID::class.java),
                settlementRecorded=true,
            )},org,id,
        ).singleOrNull()
    private fun receipt(
        snap:FinanceEmployeeSettlementVerified,state:String,
        approval:UUID?=null,recorded:UUID?=null,replayed:Boolean=false,
    )=FinanceEmployeeSettlementReceipt(
        snap.proposalId,snap.org,snap.fundingRecordId,snap.employeeId,
        snap.action,snap.amount.toPlainString(),snap.currency,state,
        approvalRequestId=approval,settlementRecordId=recorded,
        replayed=replayed,settlementRecorded=recorded!=null,
    )
    private fun authority(actor:UUID,org:UUID,setting:String){
        if(!subject.enabled())unavailable()
        val key=environment.getProperty(setting,"").trim()
        if(!Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(key))unavailable()
        val now=clock.instant()
        if(!approvals.isActiveOrganizationMember(org,actor,now))unavailable()
        val authority=approvals.resolveCurrentAuthority(org,key,now)
            ?: unavailable()
        if(authority.organizationId!=org ||
            !approvals.actorMatchesAuthority(actor,authority,now))unavailable()
    }
    private fun unavailable():Nothing=throw ProductApiException(
        "FINANCE_EMPLOYEE_SETTLEMENT_UNAVAILABLE",
        "Confirmed company funding, evidence or authority unavailable.",
        HttpStatus.NOT_FOUND,
    )
    private fun stale():Nothing=throw ProductApiException(
        "FINANCE_EMPLOYEE_SETTLEMENT_STALE",
        "Company custody, bank source or approval has changed.",
        HttpStatus.CONFLICT,
    )
}
private data class SettlementFundingSource(
    val employeeId:UUID,val kind:String,
    val amount:BigDecimal,val currency:String,
)
@RestController
class FinanceEmployeeSettlementController(
    private val settlement:FinanceEmployeeSettlementService,
) {
    @PostMapping("/v1/finance/organizations/{orgId}/employee-funded/{fundingId}/settlements/prepare")
    fun prepare(
        http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable fundingId:UUID,
        @RequestBody body:FinanceEmployeeSettlementPrepare,
    ):FinanceEmployeeSettlementReceipt {
        val ctx=HiltechRequestContext.current(http)
        IdempotencyKeyContract.requireMatches(ctx.idempotencyKey,body.operationId)
        return settlement.prepare(
            actor(http),orgId,fundingId,body,ctx.correlationId)
    }
    @PostMapping("/v1/finance/organizations/{orgId}/employee-settlement-proposals/{id}/request-review")
    fun review(
        http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable id:UUID,@RequestBody body:FinanceEmployeeSettlementOperation,
    ):FinanceEmployeeSettlementReceipt {
        val ctx=HiltechRequestContext.current(http)
        IdempotencyKeyContract.requireMatches(ctx.idempotencyKey,body.operationId)
        return settlement.requestReview(
            actor(http),orgId,id,body.operationId,ctx.correlationId)
    }
    @PostMapping("/v1/finance/organizations/{orgId}/employee-settlement-proposals/{id}/record")
    fun record(
        http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable id:UUID,@RequestBody body:FinanceEmployeeSettlementOperation,
    ):FinanceEmployeeSettlementReceipt {
        val ctx=HiltechRequestContext.current(http)
        IdempotencyKeyContract.requireMatches(ctx.idempotencyKey,body.operationId)
        return settlement.record(
            actor(http),orgId,id,body.operationId,ctx.correlationId)
    }
    @GetMapping("/v1/finance/organizations/{orgId}/employee-settlement-proposals/{id}")
    fun status(
        http:HttpServletRequest,@PathVariable orgId:UUID,@PathVariable id:UUID,
    ):FinanceEmployeeSettlementReceipt =
        settlement.status(actor(http),orgId,id)
    private fun actor(http:HttpServletRequest):UUID =
        HiltechRequestContext.current(http).identityId
            ?.let{runCatching{UUID.fromString(it)}.getOrNull()}
            ?:throw ProductApiException(
                "UNAUTHENTICATED","Finance identity required.",
                HttpStatus.UNAUTHORIZED,
            )
}

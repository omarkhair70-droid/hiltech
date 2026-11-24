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

data class FinanceEmployeeFundingPrepare(
    val operationId:UUID,val originalPolicyApprovalId:UUID,
    val bankStatementLineId:UUID,
)
data class FinanceEmployeeFundingOperation(val operationId:UUID)
data class FinanceEmployeeFundingReceipt(
    val proposalId:UUID,val organizationId:UUID,
    val kind:String,val draftId:UUID,val employeeId:UUID,
    val amount:String,val currency:String,val state:String,
    val bankStatementLineId:UUID,val approvalRequestId:UUID?=null,
    val fundingRecordId:UUID?=null,val replayed:Boolean=false,
    val fundingEvidenceRecorded:Boolean=false,
    val bankTransferInitiated:Boolean=false,
    val personalLoanRecognized:Boolean=false,
    val cashCustodyRecognized:Boolean=false,
    val employeeSettled:Boolean=false,
)
private data class FinanceOriginalPolicyApproval(
    val state:String,val kind:String,val draftId:UUID,val requester:UUID,
    val subjectVersion:Long,
)
private data class FinanceSourceBankDebit(
    val amount:BigDecimal,val currency:String,val flow:String,
    val statementHash:String,
)

/**
 * Funding is an independently approved, observed BANK DEBIT. A draft approval
 * is not paid, and the record does not itself create an employee debt,
 * income, withholding, GL journal or cash transfer.
 */
@Service
class FinanceEmployeeFundingService(
    private val jdbc:JdbcTemplate,
    private val sources:FinanceMoneyDraftPolicyApprovalSubject,
    private val subject:FinanceEmployeeFundingApprovalSubject,
    private val approvals:ApprovalPersistencePort,
    private val engine:ApprovalEngineService,
    private val idempotency:IdempotentCommandExecutor,
    private val audit:AuditEventWriter,
    private val environment:Environment,
    private val clock:Clock,
) {
    @Transactional
    fun prepare(
        actor:UUID,org:UUID,kind:String,draftId:UUID,
        req:FinanceEmployeeFundingPrepare,correlation:String,
    ):FinanceEmployeeFundingReceipt {
        authority(actor,org,"hiltech.finance.employee-funding.preparer-authority-key")
        val sourceKind=when(kind) {
            "EMPLOYEE_ADVANCE"->FinanceEmployeeMoneyDraftKind.ADVANCE
            "FINANCIAL_IMPREST"->FinanceEmployeeMoneyDraftKind.IMPREST
            else->unavailable()
        }
        val draft=sources.current(sourceKind,org,draftId) ?: stale()
        if(draft.createdByUserId==actor || draft.kind.kindCode!=kind)
            unavailable()
        val original=jdbc.query(
            """
            SELECT state,subject_type,subject_id,requester_user_id,
              subject_version FROM approval_request
            WHERE id=? AND organization_id=?
            """.trimIndent(),
            {rs,_ ->FinanceOriginalPolicyApproval(
                rs.getString("state"),rs.getString("subject_type"),
                rs.getObject("subject_id",UUID::class.java),
                rs.getObject("requester_user_id",UUID::class.java),
                rs.getLong("subject_version"),
            )},req.originalPolicyApprovalId,org,
        ).singleOrNull() ?: unavailable()
        if(original.state!="APPROVED" ||
            original.kind!=sourceKind.subjectType ||
            original.draftId!=draftId ||
            original.requester!=draft.createdByUserId ||
            original.subjectVersion!=draft.sourceVersion)unavailable()
        val bank=jdbc.query(
            """
            SELECT l.amount,l.currency,l.flow,h.statement_sha256
            FROM finance_bank_statement_intake_line l
            JOIN finance_bank_statement_intake h
              ON h.id=l.intake_id AND h.organization_id=l.organization_id
            WHERE l.id=? AND l.organization_id=?
            """.trimIndent(),
            {rs,_ ->FinanceSourceBankDebit(
                rs.getBigDecimal("amount"),rs.getString("currency"),
                rs.getString("flow"),rs.getString("statement_sha256"),
            )},req.bankStatementLineId,org,
        ).singleOrNull() ?: stale()
        if(bank.flow!="DEBIT" || bank.currency!=draft.currency ||
            bank.amount<draft.amount)unavailable()
        val now=clock.instant()
        val key=subject.policyKey() ?: unavailable()
        val policy=approvals.loadActivePolicy(org,key,now)?.takeIf {
            it.organizationId==org && it.policyKey==key &&
                it.approvalRequired && it.mode==ApprovalPolicyMode.SINGLE &&
                !it.authorityKey.isNullOrBlank()
        } ?: unavailable()
        val reviewer=approvals.resolveCurrentAuthority(
            org,policy.authorityKey!!,now,
        ) ?: unavailable()
        if(reviewer.organizationId!=org ||
            approvals.actorMatchesAuthority(actor,reviewer,now))unavailable()
        val hash=FinanceEmployeeFundingOrigin.derive(
            org,kind,draftId,draft.employeeId,draft.amount,draft.currency,
            req.originalPolicyApprovalId,original.subjectVersion,
        )
        val version=FinanceEmployeeFundingOrigin.reviewVersion(
            org,kind,draftId,draft.employeeId,draft.amount,draft.currency,
            hash,req.bankStatementLineId,bank.statementHash,
            policy.versionNumber,reviewer.id,reviewer.version,
        )
        val id=UUID.nameUUIDFromBytes(
            "hiltech:employee-funding:${req.operationId}"
                .toByteArray(Charsets.UTF_8),
        )
        val fingerprint=IdempotencyKeyContract.fingerprint(
            listOf(org,kind,draftId,draft.employeeId,
                req.originalPolicyApprovalId,req.bankStatementLineId,
                hash,version).joinToString("|"),
        )
        val outcome=idempotency.execute(IdempotentCommandSpec(
            req.operationId,actor,"FINANCE_EMPLOYEE_FUNDING_PROPOSED",
            "FINANCE_EMPLOYEE_FUNDING_PROPOSAL",id,
            fingerprint,correlation,
        )){
            val locked=jdbc.query(
                """SELECT id FROM finance_bank_statement_intake_line
                   WHERE id=? AND organization_id=? FOR UPDATE""",
                {rs,_ ->rs.getObject("id",UUID::class.java)},
                req.bankStatementLineId,org,
            ).singleOrNull() ?: stale()
            if(locked!=req.bankStatementLineId)stale()
            check(jdbc.update(
                """
                INSERT INTO finance_employee_funding_proposal(
                  id,operation_id,organization_id,draft_kind,
                  employee_draft_id,employee_id,source_policy_approval_id,
                  bank_statement_line_id,amount,currency,
                  source_fingerprint_sha256,review_version,policy_key,
                  prepared_by_user_id,created_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                id,req.operationId,org,kind,draftId,draft.employeeId,
                req.originalPolicyApprovalId,req.bankStatementLineId,
                draft.amount,draft.currency,hash,version,key,actor,
                clock.instant().atOffset(ZoneOffset.UTC),
            )==1)
            audit.append(AuditEventRecord(
                actorUserId=actor,action="FINANCE_EMPLOYEE_FUNDING_PROPOSED",
                targetType="FINANCE_EMPLOYEE_FUNDING_PROPOSAL",targetId=id,
                occurredAt=clock.instant(),correlationId=correlation,
                reason="BANK_DEBIT_PRESENT_PENDING_INDEPENDENT_FUNDING_REVIEW",
            ))
            IdempotentCommandOutcome(
                "FINANCE_EMPLOYEE_FUNDING_PROPOSED","""{"id":"$id"}""",
            )
        }
        return FinanceEmployeeFundingReceipt(
            id,org,kind,draftId,draft.employeeId,
            draft.amount.toPlainString(),draft.currency,"PROPOSED",
            req.bankStatementLineId,replayed=outcome.replayed,
        )
    }
    fun requestReview(
        actor:UUID,org:UUID,id:UUID,operation:UUID,correlation:String,
    ):FinanceEmployeeFundingReceipt {
        authority(actor,org,"hiltech.finance.employee-funding.preparer-authority-key")
        val snap=subject.current(org,id) ?: stale()
        if(snap.preparedBy!=actor)unavailable()
        val ref=ApprovalSubjectRef(
            org,FINANCE_EMPLOYEE_FUNDING_SUBJECT,id,snap.reviewVersion,
        )
        val prior=engine.subjectStatus(ref,snap.policyKey)
        if(prior!=null) {
            if(prior.state!=ApprovalRequestState.PENDING)unavailable()
            return receipt(snap,"PENDING",approval=prior.approvalRequestId,
                replayed=true)
        }
        val review=engine.requestException(
            actorUserId=actor,operationId=operation,subject=ref,
            policyKey=snap.policyKey,
            reasonCode="FINANCE_EMPLOYEE_FUNDING_INDEPENDENT_REVIEW",
            safeReasonSummary=null,correlationId=correlation,
            allowAuthorityBypass=false,
        )
        if(!review.approvalRequired || review.approvalRequestId==null ||
            review.state!=ApprovalRequestState.PENDING ||
            review.policyKey!=snap.policyKey ||
            review.policyVersion!=snap.policyVersion)unavailable()
        return receipt(snap,"PENDING",approval=review.approvalRequestId,
            replayed=review.replayed)
    }
    @Transactional
    fun record(
        actor:UUID,org:UUID,id:UUID,operation:UUID,correlation:String,
    ):FinanceEmployeeFundingReceipt {
        authority(actor,org,"hiltech.finance.employee-funding.operator-authority-key")
        historical(org,id)?.let {old ->
            val original=jdbc.query(
                """SELECT operation_id,recorded_by_user_id
                   FROM finance_employee_funding_record
                   WHERE id=? AND organization_id=?""",
                {rs,_ ->rs.getObject("operation_id",UUID::class.java) to
                    rs.getObject("recorded_by_user_id",UUID::class.java)},
                old.fundingRecordId,org,
            ).singleOrNull() ?: unavailable()
            if(original.first!=operation || original.second!=actor)unavailable()
            return old.copy(replayed=true)
        }
        val snap=subject.current(org,id) ?: stale()
        if(snap.preparedBy==actor)unavailable()
        val ref=ApprovalSubjectRef(
            org,FINANCE_EMPLOYEE_FUNDING_SUBJECT,id,snap.reviewVersion,
        )
        val approved=engine.subjectStatus(ref,snap.policyKey)?.takeIf {
            it.state==ApprovalRequestState.APPROVED &&
                it.subjectVersion==snap.reviewVersion
        } ?: unavailable()
        val fundingId=UUID.nameUUIDFromBytes(
            "hiltech:employee-funded:$operation"
                .toByteArray(Charsets.UTF_8),
        )
        val fingerprint=IdempotencyKeyContract.fingerprint(
            listOf(org,id,snap.reviewVersion,snap.sourceHash,
                approved.approvalRequestId).joinToString("|"),
        )
        val outcome=idempotency.execute(IdempotentCommandSpec(
            operation,actor,"FINANCE_EMPLOYEE_FUNDING_RECORDED",
            "FINANCE_EMPLOYEE_FUNDING_RECORD",fundingId,
            fingerprint,correlation,
        )){
            val locked=jdbc.query(
                """SELECT id FROM finance_bank_statement_intake_line
                   WHERE id=? AND organization_id=? FOR UPDATE""",
                {rs,_ ->rs.getObject("id",UUID::class.java)},
                snap.bankLineId,org,
            ).singleOrNull() ?: stale()
            if(locked!=snap.bankLineId || subject.current(org,id)!=snap)
                stale()
            check(jdbc.update(
                """
                INSERT INTO finance_employee_funding_record(
                  id,operation_id,organization_id,proposal_id,
                  employee_draft_id,draft_kind,employee_id,
                  bank_statement_line_id,approval_request_id,
                  amount,currency,recorded_by_user_id,recorded_at,correlation_id
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                fundingId,operation,org,id,snap.draftId,snap.kind,
                snap.employeeId,snap.bankLineId,
                approved.approvalRequestId,snap.amount,snap.currency,
                actor,clock.instant().atOffset(ZoneOffset.UTC),correlation,
            )==1)
            audit.append(AuditEventRecord(
                actorUserId=actor,action="FINANCE_EMPLOYEE_FUNDING_RECORDED",
                targetType="FINANCE_EMPLOYEE_FUNDING_RECORD",
                targetId=fundingId,occurredAt=clock.instant(),
                correlationId=correlation,
                reason="BANK_CONFIRMED_FUNDED_EVIDENCE_NO_TRANSFER_OR_PAYROLL",
            ))
            IdempotentCommandOutcome(
                "FINANCE_EMPLOYEE_FUNDING_RECORDED","""{"id":"$fundingId"}""",
            )
        }
        return receipt(snap,"RECORDED",approved.approvalRequestId,
            fundingId,outcome.replayed)
    }
    /** Discover only currently approved, unrecorded operations for the existing operator authority. */
    fun operatorQueue(actor: UUID, org: UUID): List<FinanceEmployeeFundingReceipt> {
        authority(actor,org,"hiltech.finance.employee-funding.operator-authority-key")
        val ids = jdbc.query(
            """SELECT p.id FROM finance_employee_funding_proposal p
               WHERE p.organization_id=?
               AND EXISTS (SELECT 1 FROM approval_request a
                 WHERE a.organization_id=p.organization_id AND a.subject_id=p.id
                 AND a.subject_type=? AND a.state='APPROVED'
                 AND a.requester_user_id<>?)
               AND NOT EXISTS (SELECT 1 FROM finance_employee_funding_record r
                 WHERE r.organization_id=p.organization_id AND r.proposal_id=p.id)
               ORDER BY p.id LIMIT 100""",
            { rs,_ -> rs.getObject("id",UUID::class.java) },org,FINANCE_EMPLOYEE_FUNDING_SUBJECT,actor,
        )
        return ids.mapNotNull { id ->
            val snapshot = subject.current(org,id) ?: return@mapNotNull null
            if (snapshot.preparedBy == actor) return@mapNotNull null
            val current = status(actor,org,id)
            current.takeIf { it.state=="APPROVED" && it.approvalRequestId!=null && it.fundingRecordId==null }
        }
    }

    fun status(actor:UUID,org:UUID,id:UUID):FinanceEmployeeFundingReceipt {
        val operator = try {
            authority(actor,org,"hiltech.finance.employee-funding.operator-authority-key")
            true
        } catch (_: ProductApiException) { false }
        if (!operator) authority(actor,org,"hiltech.finance.employee-funding.preparer-authority-key")
        historical(org,id)?.let{return it}
        val snap=subject.current(org,id) ?: stale()
        val status=engine.subjectStatus(
            ApprovalSubjectRef(org,FINANCE_EMPLOYEE_FUNDING_SUBJECT,
                id,snap.reviewVersion),snap.policyKey,
        )
        return receipt(snap,status?.state?.name ?: "PROPOSED",
            approval=status?.approvalRequestId)
    }
    private fun historical(org:UUID,proposal:UUID):FinanceEmployeeFundingReceipt? =
        jdbc.query(
            """
            SELECT id,draft_kind,employee_draft_id,employee_id,
              bank_statement_line_id,amount,currency,approval_request_id
            FROM finance_employee_funding_record
            WHERE organization_id=? AND proposal_id=?
            """.trimIndent(),
            {rs,_ ->FinanceEmployeeFundingReceipt(
                proposalId=proposal,organizationId=org,
                kind=rs.getString("draft_kind"),
                draftId=rs.getObject("employee_draft_id",UUID::class.java),
                employeeId=rs.getObject("employee_id",UUID::class.java),
                amount=rs.getBigDecimal("amount").toPlainString(),
                currency=rs.getString("currency"),
                state="RECORDED",
                bankStatementLineId=rs.getObject("bank_statement_line_id",UUID::class.java),
                approvalRequestId=rs.getObject("approval_request_id",UUID::class.java),
                fundingRecordId=rs.getObject("id",UUID::class.java),
                fundingEvidenceRecorded=true,
            )},org,proposal,
        ).singleOrNull()
    private fun receipt(
        s:FinanceEmployeeFundingVerified,state:String,
        approval:UUID?=null,funding:UUID?=null,replayed:Boolean=false,
    )=FinanceEmployeeFundingReceipt(
        s.proposalId,s.org,s.kind,s.draftId,s.employeeId,
        s.amount.toPlainString(),s.currency,state,s.bankLineId,
        approvalRequestId=approval,fundingRecordId=funding,
        replayed=replayed,fundingEvidenceRecorded=funding!=null,
    )
    private fun authority(actor:UUID,org:UUID,setting:String) {
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
        "FINANCE_EMPLOYEE_FUNDING_UNAUTHORIZED",
        "Independent company funding authority or bank source unavailable.",
        HttpStatus.NOT_FOUND,
    )
    private fun stale():Nothing=throw ProductApiException(
        "FINANCE_EMPLOYEE_FUNDING_STALE",
        "Employee policy approval or actual bank source changed.",
        HttpStatus.CONFLICT,
    )
}
@RestController
class FinanceEmployeeFundingController(
    private val funding:FinanceEmployeeFundingService,
) {
    @PostMapping("/v1/finance/organizations/{orgId}/employee-funding/{kind}/{draftId}/prepare")
    fun prepare(
        http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable kind:String,@PathVariable draftId:UUID,
        @RequestBody body:FinanceEmployeeFundingPrepare,
    ):FinanceEmployeeFundingReceipt {
        val ctx=HiltechRequestContext.current(http)
        IdempotencyKeyContract.requireMatches(ctx.idempotencyKey,body.operationId)
        return funding.prepare(
            actor(http),orgId,kind,draftId,body,ctx.correlationId)
    }
    @PostMapping("/v1/finance/organizations/{orgId}/employee-funding-proposals/{id}/request-review")
    fun request(
        http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable id:UUID,@RequestBody body:FinanceEmployeeFundingOperation,
    ):FinanceEmployeeFundingReceipt {
        val ctx=HiltechRequestContext.current(http)
        IdempotencyKeyContract.requireMatches(ctx.idempotencyKey,body.operationId)
        return funding.requestReview(
            actor(http),orgId,id,body.operationId,ctx.correlationId)
    }
    @PostMapping("/v1/finance/organizations/{orgId}/employee-funding-proposals/{id}/record")
    fun record(
        http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable id:UUID,@RequestBody body:FinanceEmployeeFundingOperation,
    ):FinanceEmployeeFundingReceipt {
        val ctx=HiltechRequestContext.current(http)
        IdempotencyKeyContract.requireMatches(ctx.idempotencyKey,body.operationId)
        return funding.record(
            actor(http),orgId,id,body.operationId,ctx.correlationId)
    }
    @GetMapping("/v1/finance/organizations/{orgId}/employee-funding-proposals/operator-queue")
    fun operatorQueue(http:HttpServletRequest,@PathVariable orgId:UUID):List<FinanceEmployeeFundingReceipt> =
        funding.operatorQueue(actor(http),orgId)
    @GetMapping("/v1/finance/organizations/{orgId}/employee-funding-proposals/{id}")
    fun status(
        http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable id:UUID,
    ):FinanceEmployeeFundingReceipt =
        funding.status(actor(http),orgId,id)
    private fun actor(http:HttpServletRequest):UUID =
        HiltechRequestContext.current(http).identityId
            ?.let{runCatching{UUID.fromString(it)}.getOrNull()}
            ?:throw ProductApiException(
                "UNAUTHENTICATED","Finance identity required.",HttpStatus.UNAUTHORIZED)
}

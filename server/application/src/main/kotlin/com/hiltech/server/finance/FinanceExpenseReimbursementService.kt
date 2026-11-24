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

data class FinanceExpenseReimbursementPrepare(
    val operationId:UUID,
    val documentationApprovalRequestId:UUID,
    val bankStatementLineId:UUID,
    val reimbursementAmount:String,
)
data class FinanceExpenseReimbursementOperation(val operationId:UUID)
data class FinanceExpenseReimbursementReceipt(
    val proposalId:UUID,val organizationId:UUID,
    val expenseDraftId:UUID,val employeeId:UUID,
    val bankStatementLineId:UUID,val amount:String,val currency:String,
    val state:String,val approvalRequestId:UUID?=null,
    val reimbursementRecordId:UUID?=null,val replayed:Boolean=false,
    val bankDebitAllocatedToEmployeeExpense:Boolean=false,
    val bankTransferInitiated:Boolean=false,
    val expenseRecognizedInGl:Boolean=false,
)
private data class ExpenseDocumentApprovalRow(
    val state:String,val subjectType:String,val subjectId:UUID,
    val version:Long,val requester:UUID,
)
private data class ExpenseBankDebitRow(
    val amount:BigDecimal,val currency:String,val flow:String,
    val bankHash:String,
)

/**
 * Records that a real, independently imported company bank debit reimbursed
 * a previously documented *personally paid* employee expense.
 * Does NOT initiate a transfer or post an inferred tax/expense GL category.
 */
@Service
class FinanceExpenseReimbursementService(
    private val jdbc:JdbcTemplate,
    private val documents:FinanceExpenseDocumentationApprovalSubject,
    private val subject:FinanceExpenseReimbursementApprovalSubject,
    private val approvals:ApprovalPersistencePort,
    private val engine:ApprovalEngineService,
    private val idempotency:IdempotentCommandExecutor,
    private val audit:AuditEventWriter,
    private val environment:Environment,
    private val clock:Clock,
) {
    @Transactional
    fun prepare(
        actor:UUID,org:UUID,expense:UUID,
        req:FinanceExpenseReimbursementPrepare,correlation:String,
    ):FinanceExpenseReimbursementReceipt {
        authority(actor,org,
            "hiltech.finance.expense-reimbursement.preparer-authority-key")
        val claim=documents.current(org,expense) ?: stale()
        if(claim.createdByUserId==actor)unavailable()
        val documented=jdbc.query(
            """
            SELECT state,subject_type,subject_id,subject_version,requester_user_id
            FROM approval_request WHERE id=? AND organization_id=?
            """.trimIndent(),
            {rs,_ ->ExpenseDocumentApprovalRow(
                rs.getString("state"),rs.getString("subject_type"),
                rs.getObject("subject_id",UUID::class.java),
                rs.getLong("subject_version"),
                rs.getObject("requester_user_id",UUID::class.java),
            )},req.documentationApprovalRequestId,org,
        ).singleOrNull() ?: unavailable()
        if(documented.state!="APPROVED" ||
            documented.subjectType!=FINANCE_EXPENSE_DOCUMENTATION_REVIEW_SUBJECT ||
            documented.subjectId!=expense ||
            documented.version!=claim.sourceVersion ||
            documented.requester!=claim.createdByUserId)unavailable()
        val bank=jdbc.query(
            """
            SELECT l.amount,l.currency,l.flow,h.statement_sha256
            FROM finance_bank_statement_intake_line l
            JOIN finance_bank_statement_intake h
              ON h.id=l.intake_id AND h.organization_id=l.organization_id
            WHERE l.id=? AND l.organization_id=?
            """.trimIndent(),
            {rs,_ ->ExpenseBankDebitRow(
                rs.getBigDecimal("amount"),rs.getString("currency"),
                rs.getString("flow"),rs.getString("statement_sha256"),
            )},req.bankStatementLineId,org,
        ).singleOrNull() ?: stale()
        val amount=runCatching{BigDecimal(req.reimbursementAmount)}
            .getOrNull() ?: unavailable()
        val total=runCatching{BigDecimal(claim.amount)}.getOrNull() ?: unavailable()
        if(amount.signum()<=0 || amount.stripTrailingZeros().scale()>6 ||
            amount.stripTrailingZeros().precision()>18 ||
            amount>total || amount>bank.amount ||
            bank.flow!="DEBIT" || bank.currency!=claim.currency)unavailable()
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
        val id=UUID.nameUUIDFromBytes(
            "hiltech:employee-expense-reimbursement:${req.operationId}"
                .toByteArray(Charsets.UTF_8),
        )
        val version=FinanceExpenseReimbursementVersion.derive(
            org,expense,claim.employeeId,req.documentationApprovalRequestId,
            claim.sourceVersion,req.bankStatementLineId,
            bank.bankHash,amount,claim.currency,
            policy.versionNumber,reviewer.id,reviewer.version,
        )
        val fingerprint=IdempotencyKeyContract.fingerprint(
            listOf(org,expense,claim.employeeId,
                req.documentationApprovalRequestId,claim.sourceVersion,
                req.bankStatementLineId,bank.bankHash,amount,version)
                .joinToString("|"),
        )
        val outcome=idempotency.execute(IdempotentCommandSpec(
            req.operationId,actor,"FINANCE_EXPENSE_REIMBURSEMENT_PROPOSED",
            "FINANCE_EXPENSE_REIMBURSEMENT_PROPOSAL",id,
            fingerprint,correlation,
        )){
            val locked=jdbc.query(
                """SELECT id FROM finance_bank_statement_intake_line
                   WHERE id=? AND organization_id=? FOR UPDATE""",
                {rs,_ ->rs.getObject("id",UUID::class.java)},
                req.bankStatementLineId,org,
            ).singleOrNull() ?: stale()
            if(locked!=req.bankStatementLineId ||
                documents.current(org,expense)!=claim)stale()
            check(jdbc.update(
                """
                INSERT INTO finance_expense_reimbursement_proposal(
                  id,operation_id,organization_id,expense_draft_id,employee_id,
                  documentation_approval_request_id,documentation_version,
                  bank_statement_line_id,amount,currency,review_version,
                  policy_key,prepared_by_user_id,created_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                id,req.operationId,org,expense,claim.employeeId,
                req.documentationApprovalRequestId,claim.sourceVersion,
                req.bankStatementLineId,amount,claim.currency,version,
                key,actor,clock.instant().atOffset(ZoneOffset.UTC),
            )==1)
            audit.append(AuditEventRecord(
                actorUserId=actor,action="FINANCE_EXPENSE_REIMBURSEMENT_PROPOSED",
                targetType="FINANCE_EXPENSE_REIMBURSEMENT_PROPOSAL",targetId=id,
                occurredAt=clock.instant(),correlationId=correlation,
                reason="PERSONAL_EXPENSE_DOCUMENTED_SEPARATE_FINANCIAL_REVIEW_REQUIRED",
            ))
            IdempotentCommandOutcome(
                "FINANCE_EXPENSE_REIMBURSEMENT_PROPOSED",
                """{"id":"$id"}""",
            )
        }
        return FinanceExpenseReimbursementReceipt(
            proposalId=id,organizationId=org,
            expenseDraftId=expense,employeeId=claim.employeeId,
            bankStatementLineId=req.bankStatementLineId,
            amount=amount.toPlainString(),currency=claim.currency,
            state="PROPOSED",replayed=outcome.replayed,
        )
    }
    fun requestReview(
        actor:UUID,org:UUID,id:UUID,operation:UUID,correlation:String,
    ):FinanceExpenseReimbursementReceipt {
        authority(actor,org,
            "hiltech.finance.expense-reimbursement.preparer-authority-key")
        val snap=subject.current(org,id) ?: stale()
        if(snap.preparedBy!=actor)unavailable()
        val ref=ApprovalSubjectRef(
            org,FINANCE_EXPENSE_REIMBURSEMENT_SUBJECT,id,snap.reviewVersion,
        )
        val old=engine.subjectStatus(ref,snap.policyKey)
        if(old!=null) {
            if(old.state!=ApprovalRequestState.PENDING)unavailable()
            return receipt(snap,"PENDING",approval=old.approvalRequestId,
                replayed=true)
        }
        val submitted=engine.requestException(
            actorUserId=actor,operationId=operation,subject=ref,
            policyKey=snap.policyKey,
            reasonCode="FINANCE_PERSONAL_EXPENSE_REIMBURSEMENT_REVIEW",
            safeReasonSummary=null,correlationId=correlation,
            allowAuthorityBypass=false,
        )
        if(!submitted.approvalRequired || submitted.approvalRequestId==null ||
            submitted.state!=ApprovalRequestState.PENDING ||
            submitted.policyKey!=snap.policyKey ||
            submitted.policyVersion!=snap.policyVersion)unavailable()
        return receipt(snap,"PENDING",approval=submitted.approvalRequestId,
            replayed=submitted.replayed)
    }
    @Transactional
    fun record(
        actor:UUID,org:UUID,id:UUID,operation:UUID,correlation:String,
    ):FinanceExpenseReimbursementReceipt {
        authority(actor,org,
            "hiltech.finance.expense-reimbursement.operator-authority-key")
        historical(org,id)?.let{past->
            val recorded=jdbc.query(
                """SELECT operation_id,recorded_by_user_id
                   FROM finance_expense_reimbursement_record
                   WHERE id=? AND organization_id=?""",
                {rs,_ ->rs.getObject("operation_id",UUID::class.java) to
                    rs.getObject("recorded_by_user_id",UUID::class.java)},
                past.reimbursementRecordId,org,
            ).singleOrNull() ?: unavailable()
            if(recorded.first!=operation || recorded.second!=actor)unavailable()
            return past.copy(replayed=true)
        }
        val snap=subject.current(org,id) ?: stale()
        if(snap.preparedBy==actor)unavailable()
        val ref=ApprovalSubjectRef(
            org,FINANCE_EXPENSE_REIMBURSEMENT_SUBJECT,id,snap.reviewVersion,
        )
        val approved=engine.subjectStatus(ref,snap.policyKey)?.takeIf{
            it.state==ApprovalRequestState.APPROVED &&
                it.subjectVersion==snap.reviewVersion
        } ?: unavailable()
        val recordId=UUID.nameUUIDFromBytes(
            "hiltech:expense-reimbursement-record:$operation"
                .toByteArray(Charsets.UTF_8),
        )
        val fingerprint=IdempotencyKeyContract.fingerprint(
            listOf(org,id,snap.reviewVersion,
                approved.approvalRequestId,snap.bankStatementHash)
                .joinToString("|"),
        )
        val outcome=idempotency.execute(IdempotentCommandSpec(
            operation,actor,"FINANCE_EXPENSE_REIMBURSEMENT_RECORDED",
            "FINANCE_EXPENSE_REIMBURSEMENT_RECORD",recordId,
            fingerprint,correlation,
        )){
            val bank=jdbc.query(
                """SELECT id FROM finance_bank_statement_intake_line
                   WHERE id=? AND organization_id=? FOR UPDATE""",
                {rs,_ ->rs.getObject("id",UUID::class.java)},
                snap.bankStatementLineId,org,
            ).singleOrNull() ?: stale()
            if(bank!=snap.bankStatementLineId ||
                subject.current(org,id)!=snap)stale()
            check(jdbc.update(
                """
                INSERT INTO finance_expense_reimbursement_record(
                  id,operation_id,organization_id,proposal_id,
                  expense_draft_id,employee_id,bank_statement_line_id,
                  amount,currency,approval_request_id,recorded_by_user_id,
                  recorded_at,correlation_id
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                recordId,operation,org,id,snap.expenseDraftId,snap.employeeId,
                snap.bankStatementLineId,snap.amount,snap.currency,
                approved.approvalRequestId,actor,
                clock.instant().atOffset(ZoneOffset.UTC),correlation,
            )==1)
            audit.append(AuditEventRecord(
                actorUserId=actor,action="FINANCE_EXPENSE_REIMBURSEMENT_RECORDED",
                targetType="FINANCE_EXPENSE_REIMBURSEMENT_RECORD",
                targetId=recordId,occurredAt=clock.instant(),
                correlationId=correlation,
                reason="EXISTING_COMPANY_BANK_DEBIT_EMPLOYEE_REIMBURSEMENT_NO_TRANSFER",
            ))
            IdempotentCommandOutcome(
                "FINANCE_EXPENSE_REIMBURSEMENT_RECORDED",
                """{"id":"$recordId"}""",
            )
        }
        return receipt(snap,"RECORDED",
            approval=approved.approvalRequestId,
            recorded=recordId,replayed=outcome.replayed)
    }
    /** Discover only currently approved, unrecorded operations for the existing operator authority. */
    fun operatorQueue(actor: UUID, org: UUID): List<FinanceExpenseReimbursementReceipt> {
        authority(actor,org,"hiltech.finance.expense-reimbursement.operator-authority-key")
        val ids = jdbc.query(
            """SELECT p.id FROM finance_expense_reimbursement_proposal p
               WHERE p.organization_id=?
               AND EXISTS (SELECT 1 FROM approval_request a
                 WHERE a.organization_id=p.organization_id AND a.subject_id=p.id
                 AND a.subject_type=? AND a.state='APPROVED'
                 AND a.requester_user_id<>?)
               AND NOT EXISTS (SELECT 1 FROM finance_expense_reimbursement_record r
                 WHERE r.organization_id=p.organization_id AND r.proposal_id=p.id)
               ORDER BY p.id LIMIT 100""",
            { rs,_ -> rs.getObject("id",UUID::class.java) },org,FINANCE_EXPENSE_REIMBURSEMENT_SUBJECT,actor,
        )
        return ids.mapNotNull { id ->
            val snapshot = subject.current(org,id) ?: return@mapNotNull null
            if (snapshot.preparedBy == actor) return@mapNotNull null
            val current = status(actor,org,id)
            current.takeIf { it.state=="APPROVED" && it.approvalRequestId!=null && it.reimbursementRecordId==null }
        }
    }

    fun status(
        actor:UUID,org:UUID,id:UUID,
    ):FinanceExpenseReimbursementReceipt {
        val operator = try {
            authority(actor,org,"hiltech.finance.expense-reimbursement.operator-authority-key")
            true
        } catch (_: ProductApiException) { false }
        if (!operator) authority(actor,org,"hiltech.finance.expense-reimbursement.preparer-authority-key")
        historical(org,id)?.let{return it}
        val snap=subject.current(org,id) ?: stale()
        val review=engine.subjectStatus(
            ApprovalSubjectRef(org,FINANCE_EXPENSE_REIMBURSEMENT_SUBJECT,
                id,snap.reviewVersion),snap.policyKey,
        )
        return receipt(snap,review?.state?.name ?: "PROPOSED",
            review?.approvalRequestId)
    }
    private fun historical(org:UUID,id:UUID):FinanceExpenseReimbursementReceipt?=
        jdbc.query(
            """
            SELECT id,expense_draft_id,employee_id,bank_statement_line_id,
              amount,currency,approval_request_id
            FROM finance_expense_reimbursement_record
            WHERE organization_id=? AND proposal_id=?
            """.trimIndent(),
            {rs,_ ->FinanceExpenseReimbursementReceipt(
                proposalId=id,organizationId=org,
                expenseDraftId=rs.getObject("expense_draft_id",UUID::class.java),
                employeeId=rs.getObject("employee_id",UUID::class.java),
                bankStatementLineId=rs.getObject(
                    "bank_statement_line_id",UUID::class.java),
                amount=rs.getBigDecimal("amount").toPlainString(),
                currency=rs.getString("currency"),state="RECORDED",
                approvalRequestId=rs.getObject(
                    "approval_request_id",UUID::class.java),
                reimbursementRecordId=rs.getObject(
                    "id",UUID::class.java),
                bankDebitAllocatedToEmployeeExpense=true,
            )},org,id,
        ).singleOrNull()
    private fun receipt(
        s:FinanceExpenseReimbursementVerified,state:String,
        approval:UUID?=null,recorded:UUID?=null,replayed:Boolean=false,
    )=FinanceExpenseReimbursementReceipt(
        s.proposalId,s.organizationId,s.expenseDraftId,s.employeeId,
        s.bankStatementLineId,s.amount.toPlainString(),s.currency,
        state,approval,recorded,replayed,
        bankDebitAllocatedToEmployeeExpense=recorded!=null,
    )
    private fun authority(actor:UUID,org:UUID,setting:String) {
        if(!subject.enabled())unavailable()
        val key=environment.getProperty(setting,"").trim()
        if(!Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(key))unavailable()
        val now=clock.instant()
        if(!approvals.isActiveOrganizationMember(org,actor,now))unavailable()
        val a=approvals.resolveCurrentAuthority(org,key,now)
            ?: unavailable()
        if(a.organizationId!=org ||
            !approvals.actorMatchesAuthority(actor,a,now))unavailable()
    }
    private fun unavailable():Nothing=throw ProductApiException(
        "FINANCE_EXPENSE_REIMBURSEMENT_UNAVAILABLE",
        "Approved documentation, independent Finance signatory and bank debit required.",
        HttpStatus.NOT_FOUND,
    )
    private fun stale():Nothing=throw ProductApiException(
        "FINANCE_EXPENSE_REIMBURSEMENT_STALE",
        "Expense receipt evidence or bank statement has changed.",
        HttpStatus.CONFLICT,
    )
}
@RestController
class FinanceExpenseReimbursementController(
 private val reimbursements:FinanceExpenseReimbursementService,
) {
 @PostMapping("/v1/finance/organizations/{orgId}/expenses/{draftId}/reimbursements/prepare")
 fun prepare(
   http:HttpServletRequest,@PathVariable orgId:UUID,
   @PathVariable draftId:UUID,
   @RequestBody body:FinanceExpenseReimbursementPrepare,
 ):FinanceExpenseReimbursementReceipt{
   val ctx=HiltechRequestContext.current(http)
   IdempotencyKeyContract.requireMatches(ctx.idempotencyKey,body.operationId)
   return reimbursements.prepare(
     actor(http),orgId,draftId,body,ctx.correlationId)
 }
 @PostMapping("/v1/finance/organizations/{orgId}/expense-reimbursements/{id}/request-review")
 fun review(
   http:HttpServletRequest,@PathVariable orgId:UUID,
   @PathVariable id:UUID,@RequestBody body:FinanceExpenseReimbursementOperation,
 ):FinanceExpenseReimbursementReceipt{
   val ctx=HiltechRequestContext.current(http)
   IdempotencyKeyContract.requireMatches(ctx.idempotencyKey,body.operationId)
   return reimbursements.requestReview(
     actor(http),orgId,id,body.operationId,ctx.correlationId)
 }
 @PostMapping("/v1/finance/organizations/{orgId}/expense-reimbursements/{id}/record")
 fun record(
   http:HttpServletRequest,@PathVariable orgId:UUID,
   @PathVariable id:UUID,@RequestBody body:FinanceExpenseReimbursementOperation,
 ):FinanceExpenseReimbursementReceipt{
   val ctx=HiltechRequestContext.current(http)
   IdempotencyKeyContract.requireMatches(ctx.idempotencyKey,body.operationId)
   return reimbursements.record(
     actor(http),orgId,id,body.operationId,ctx.correlationId)
 }
 @GetMapping("/v1/finance/organizations/{orgId}/expense-reimbursements/operator-queue")
    fun operatorQueue(http:HttpServletRequest,@PathVariable orgId:UUID):List<FinanceExpenseReimbursementReceipt> =
        reimbursements.operatorQueue(actor(http),orgId)
 @GetMapping("/v1/finance/organizations/{orgId}/expense-reimbursements/{id}")
 fun status(
   http:HttpServletRequest,@PathVariable orgId:UUID,@PathVariable id:UUID,
 ):FinanceExpenseReimbursementReceipt =
   reimbursements.status(actor(http),orgId,id)
 private fun actor(http:HttpServletRequest):UUID =
   HiltechRequestContext.current(http).identityId
     ?.let{runCatching{UUID.fromString(it)}.getOrNull()}
     ?:throw ProductApiException(
       "UNAUTHENTICATED","Finance identity required.",HttpStatus.UNAUTHORIZED)
}

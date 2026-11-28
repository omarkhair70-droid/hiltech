package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.identity.IdentitySessionService
import com.hiltech.server.operationalaccess.OperationalAuthorityPort
import com.hiltech.server.operationalaccess.currentOperationalAuthority
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import org.springframework.core.env.Environment
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

internal val payrollGlTypes=setOf("PAYROLL_ACCRUAL","PAYROLL_PAYMENT","PAYROLL_RETURN","PAYROLL_VOID")

data class FinancePayrollGlAllocation(val kind:String,val domain:String,val code:String,val accountId:UUID,val reference:String)
data class FinancePayrollGlGroup(val kind:String,val domain:String,val code:String,val amount:String)
data class FinancePayrollGlPost(
    val operationId:UUID,val entryType:String,val sourceEventId:UUID?=null,val originalJournalId:UUID?=null,
    val periodId:UUID,val debitAccountId:UUID,val creditAccountId:UUID,val amount:String,val currency:String,
    val allocations:List<FinancePayrollGlAllocation> = emptyList(),
)
data class FinancePayrollGlEvent(
    val eventId:UUID,val entryType:String,val employeeName:String,val amount:String,val currency:String,
    val recordedAt:String,val originalJournalId:UUID?,val debitAccountId:UUID?,val debitAccountName:String?,
    val creditAccountId:UUID?,val creditAccountName:String?,val ready:Boolean,
)
data class FinancePayrollGlCorrection(val originalJournalId:UUID?,val clearanceReference:String,
    val reversalLines:List<FinanceDeskJournalLine>)
data class FinancePayrollGlView(
    val organizationId:UUID,val runVersionId:UUID,val period:FinanceDeskPeriod?,
    val accrualState:String,val gross:String,val currency:String,val offset:Int,val hasMore:Boolean,
    val expenseAccounts:List<FinanceSettlementAccountChoice>,val payableAccounts:List<FinanceSettlementAccountChoice>,
    val hasMoreAccounts:Boolean,val events:List<FinancePayrollGlEvent>,
    val net:String,val deductions:String,val reimbursements:String,val groups:List<FinancePayrollGlGroup>,
    val deductionAccounts:List<FinanceSettlementAccountChoice>,val reimbursementAccounts:List<FinanceSettlementAccountChoice>,
    val correction:FinancePayrollGlCorrection?=null,
)
private data class PayrollGlRun(
    val payrollPeriodId:UUID,val releaseId:UUID,val preparedAt:OffsetDateTime?,val gross:BigDecimal,
    val currency:String,val eligible:Boolean,val superseded:Boolean,val accrued:Boolean,val correctionRequired:Boolean,val replaced:Boolean,
    val net:BigDecimal,val deductions:BigDecimal,val reimbursements:BigDecimal,
)

private fun payrollGlDenied():Nothing=throw ProductApiException("FINANCE_PAYROLL_GL_UNAVAILABLE",
    "Current payroll and accounting responsibilities are required.",HttpStatus.NOT_FOUND)
private fun payrollGlChanged():Nothing=throw ProductApiException("FINANCE_PAYROLL_GL_CHANGED",
    "The accounting period, payroll source or approved account changed. Refresh before posting.",HttpStatus.CONFLICT)

@Service
class FinancePayrollLedgerService @org.springframework.beans.factory.annotation.Autowired constructor(
    private val jdbc:JdbcTemplate,private val payroll:FinancePayrollAuthorityPort,private val approvals:ApprovalPersistencePort,
    private val environment:Environment,private val clock:Clock,private val commands:IdempotentCommandExecutor,
    private val audit:AuditEventWriter,private val operationalAuthority:OperationalAuthorityPort?=null,
) {
    private val accounting=FinanceSettlementLedgerStore(jdbc)

    private fun authorize(actor:UUID,org:UUID) {
        val key="hiltech.finance.ap-posting.operator-authority-key"
        if(!payroll.canPrepare(actor,org) || !environment.getProperty("hiltech.finance.ap-posting.enabled",Boolean::class.java,false) ||
            currentOperationalAuthority(operationalAuthority,approvals,actor,org,key,environment.getProperty(key),clock.instant())==null)
            payrollGlDenied()
    }

    private fun run(org:UUID,id:UUID):PayrollGlRun? = jdbc.query("""
        SELECT r.payroll_period_id,rel.id AS release_id,b.prepared_at,r.gross,r.currency,r.net,r.deductions,r.reimbursements,
          finance_payroll_gl_components_complete(r.organization_id,r.id) AS eligible,
          EXISTS(SELECT 1 FROM payroll_run_version newer JOIN payroll_run_release pub ON pub.run_version_id=newer.id
              WHERE newer.organization_id=r.organization_id AND newer.payroll_period_id=r.payroll_period_id AND newer.generation>r.generation) AS superseded,
          EXISTS(SELECT 1 FROM finance_payroll_gl_journal j WHERE j.organization_id=r.organization_id
            AND j.run_version_id=r.id AND j.entry_type='PAYROLL_ACCRUAL') AS accrued,
          EXISTS(SELECT 1 FROM payroll_run_correction c WHERE c.organization_id=r.organization_id AND c.replacement_run_version_id=r.id) AS correction_required,
          EXISTS(SELECT 1 FROM finance_payroll_gl_journal j JOIN finance_payroll_gl_journal replacement
              ON replacement.organization_id=j.organization_id AND replacement.original_journal_id=j.id AND replacement.entry_type='PAYROLL_ACCRUAL'
              WHERE j.organization_id=r.organization_id AND j.run_version_id=r.id AND j.entry_type='PAYROLL_ACCRUAL') AS replaced
        FROM payroll_run_version r JOIN payroll_run_release rel ON rel.organization_id=r.organization_id AND rel.run_version_id=r.id
        JOIN finance_payroll_review_batch review ON review.organization_id=r.organization_id AND review.run_version_id=r.id AND review.release_id=rel.id
        LEFT JOIN finance_payroll_payment_batch b ON b.organization_id=r.organization_id AND b.run_version_id=r.id
        WHERE r.organization_id=? AND r.id=?
    """.trimIndent(),{r,_->PayrollGlRun(r.getObject("payroll_period_id",UUID::class.java),r.getObject("release_id",UUID::class.java),
        r.getObject("prepared_at",OffsetDateTime::class.java),r.getBigDecimal("gross"),r.getString("currency").trim(),
        r.getBoolean("eligible"),r.getBoolean("superseded"),r.getBoolean("accrued"),r.getBoolean("correction_required"),r.getBoolean("replaced"),
        r.getBigDecimal("net"),r.getBigDecimal("deductions"),r.getBigDecimal("reimbursements"))},org,id).singleOrNull()

    private fun correction(org:UUID,run:UUID):FinancePayrollGlCorrection? {
        val source=jdbc.query("""SELECT original_journal_id,finance_clearance_ref FROM finance_payroll_gl_correction_source
            WHERE organization_id=? AND replacement_run_version_id=?""",
            {r,_->r.getObject("original_journal_id",UUID::class.java) to r.getString("finance_clearance_ref")},org,run).singleOrNull()?:return null
        val lines=source.first?.let{id->jdbc.query("""
            SELECT l.line_number,a.account_code,a.account_name,
              CASE WHEN l.side='DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END AS side,l.amount
            FROM finance_payroll_gl_base_line l JOIN finance_gl_account a ON a.organization_id=l.organization_id AND a.id=l.gl_account_id
            WHERE l.organization_id=? AND l.journal_id=? ORDER BY l.line_number
        """.trimIndent(),{r,_->FinanceDeskJournalLine(r.getInt("line_number"),r.getString("account_code"),
            r.getString("account_name"),r.getString("side"),r.getBigDecimal("amount").toPlainString())},org,id)}?:emptyList()
        return FinancePayrollGlCorrection(source.first,source.second,lines)
    }

    private fun groups(org:UUID,run:UUID)=jdbc.query("""SELECT component_kind,source_domain,component_code,amount
        FROM finance_payroll_gl_component_group WHERE organization_id=? AND run_version_id=?
        ORDER BY component_kind,source_domain,component_code LIMIT 31""",
        {r,_->FinancePayrollGlGroup(r.getString(1),r.getString(2),r.getString(3),r.getBigDecimal(4).toPlainString())},org,run)

    private fun events(org:UUID,run:UUID,offset:Int):List<FinancePayrollGlEvent> = jdbc.query("""
        WITH source AS (
          SELECT e.id,e.action,e.amount,e.currency,e.recorded_at,p.display_name,
            original.id AS original_id,
            CASE WHEN e.action='SUCCESS' THEN accrued.credit_account_id
                 WHEN e.action='RETURNED' THEN bank.id ELSE original.credit_account_id END AS debit_id,
            CASE WHEN e.action='SUCCESS' THEN bank.id ELSE original.debit_account_id END AS credit_id,
            CASE WHEN e.action='SUCCESS' THEN NOT EXISTS(
                SELECT 1 FROM finance_payroll_gl_journal earlier
                JOIN finance_payroll_result_event older ON older.organization_id=earlier.organization_id AND older.id=earlier.source_event_id
                WHERE earlier.organization_id=e.organization_id AND earlier.entry_type='PAYROLL_PAYMENT'
                  AND older.instruction_id=e.instruction_id AND older.version<e.version
                  AND NOT EXISTS(SELECT 1 FROM finance_payroll_gl_journal reversal
                    WHERE reversal.organization_id=earlier.organization_id AND reversal.original_journal_id=earlier.id))
                ELSE original.id IS NOT NULL END AS sequence_ready
          FROM finance_payroll_result_event e JOIN finance_payroll_instruction i
            ON i.organization_id=e.organization_id AND i.id=e.instruction_id
          JOIN employee emp ON emp.organization_id=i.organization_id AND emp.id=i.employee_id
          JOIN person p ON p.id=emp.person_id
          LEFT JOIN finance_payroll_gl_journal accrued ON accrued.organization_id=i.organization_id AND accrued.run_version_id=i.run_version_id
            AND accrued.entry_type='PAYROLL_ACCRUAL'
          LEFT JOIN finance_payroll_result_event prior ON prior.organization_id=e.organization_id AND prior.instruction_id=e.instruction_id AND prior.version=e.version-1
          LEFT JOIN finance_payroll_gl_journal original ON original.organization_id=e.organization_id AND original.source_event_id=prior.id
            AND original.entry_type='PAYROLL_PAYMENT' AND e.action<>'SUCCESS'
          LEFT JOIN finance_bank_statement_intake_line b ON b.organization_id=e.organization_id AND b.id=e.bank_statement_line_id
          LEFT JOIN finance_gl_account bank ON bank.organization_id=b.organization_id AND bank.treasury_bank_account_id=b.account_id
            AND bank.state='APPROVED' AND bank.currency=e.currency
          WHERE e.organization_id=? AND i.run_version_id=?
            AND NOT EXISTS(SELECT 1 FROM finance_payroll_gl_journal j WHERE j.organization_id=e.organization_id AND j.source_event_id=e.id)
            AND (e.action<>'SUCCESS' OR NOT EXISTS(SELECT 1 FROM finance_payroll_result_event v WHERE v.organization_id=e.organization_id AND v.corrects_event_id=e.id))
            AND (e.action<>'VOID_MATCH' OR original.id IS NOT NULL)
        )
        SELECT s.*,d.account_name AS debit_name,c.account_name AS credit_name,
          (s.sequence_ready AND d.id IS NOT NULL AND c.id IS NOT NULL) AS ready
        FROM source s LEFT JOIN finance_gl_account d ON d.organization_id=? AND d.id=s.debit_id AND d.state='APPROVED'
            AND (d.currency IS NULL OR d.currency=s.currency)
        LEFT JOIN finance_gl_account c ON c.organization_id=? AND c.id=s.credit_id AND c.state='APPROVED'
            AND (c.currency IS NULL OR c.currency=s.currency)
        ORDER BY s.recorded_at,s.id LIMIT 51 OFFSET ?
    """.trimIndent(),{r,_->
        val type=when(r.getString("action")){"SUCCESS"->"PAYROLL_PAYMENT";"RETURNED"->"PAYROLL_RETURN";else->"PAYROLL_VOID"}
        val debitName=r.getString("debit_name");val creditName=r.getString("credit_name")
        FinancePayrollGlEvent(r.getObject("id",UUID::class.java),type,r.getString("display_name"),
            r.getBigDecimal("amount").toPlainString(),r.getString("currency").trim(),
            r.getObject("recorded_at",OffsetDateTime::class.java).toInstant().toString(),r.getObject("original_id",UUID::class.java),
            r.getObject("debit_id",UUID::class.java).takeIf{debitName!=null},debitName,
            r.getObject("credit_id",UUID::class.java).takeIf{creditName!=null},creditName,r.getBoolean("ready"))
    },org,run,org,org,offset)

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    fun view(actor:UUID,org:UUID,runId:UUID,offset:Int):FinancePayrollGlView {
        authorize(actor,org)
        if(offset !in 0..1_000_000) payrollGlDenied()
        val source=run(org,runId) ?: payrollGlDenied()
        val period=accounting.period(org,LocalDate.ofInstant(clock.instant(),ZoneOffset.UTC))
        val components=groups(org,runId)
        val correction=if(source.correctionRequired&&!source.superseded&&!source.accrued)correction(org,runId) else null
        val state=when {
            source.replaced -> "REPLACED"
            source.accrued -> "POSTED"
            source.superseded -> "CORRECTION_REQUIRED"
            source.preparedAt==null -> "NOT_PREPARED"
            source.correctionRequired && correction==null -> "CORRECTION_REQUIRED"
            source.gross.signum()==0 -> "NO_AMOUNT"
            !source.eligible || components.size>30 -> "TREATMENT_REQUIRED"
            else -> "READY"
        }
        val expense=if(state=="READY") accounting.debitChoices(org,source.currency,"PAYROLL_EXPENSE") else emptyList()
        val payable=if(state=="READY") accounting.debitChoices(org,source.currency,"PAYROLL_PAYABLE") else emptyList()
        val deduction=if(state=="READY" && components.any{it.kind=="DEDUCTION"})
            (accounting.debitChoices(org,source.currency,"PAYROLL_DEDUCTION_PAYABLE")+accounting.debitChoices(org,source.currency,"TAX_PAYABLE")).sortedBy{it.code}
            else emptyList()
        val reimbursement=if(state=="READY" && components.any{it.kind=="REIMBURSEMENT"}) accounting.debitChoices(org,source.currency,"EXPENSE") else emptyList()
        val rows=events(org,runId,offset)
        return FinancePayrollGlView(org,runId,period,state,source.gross.toPlainString(),source.currency,offset,rows.size>50,
            expense.take(100),payable.take(100),listOf(expense,payable,deduction,reimbursement).any{it.size>100},rows.take(50),
            source.net.toPlainString(),source.deductions.toPlainString(),source.reimbursements.toPlainString(),
            if(state=="READY") components else emptyList(),deduction.take(100),reimbursement.take(100),
            correction.takeIf{state=="READY"})
    }

    fun post(actor:UUID,org:UUID,runId:UUID,request:FinancePayrollGlPost,correlation:String):FinancePayrollCommandReceipt {
        authorize(actor,org)
        val amount=settlementAmount(request.amount) ?: payrollGlChanged()
        if(request.entryType !in payrollGlTypes || !request.currency.matches(Regex("[A-Z]{3}")) ||
            request.debitAccountId==request.creditAccountId ||
            (request.entryType=="PAYROLL_ACCRUAL")!=(request.sourceEventId==null) ||
            (request.entryType!="PAYROLL_ACCRUAL" && (request.entryType in setOf("PAYROLL_RETURN","PAYROLL_VOID"))!=(request.originalJournalId!=null))) payrollGlChanged()
        if(request.allocations.size>30 || (request.entryType!="PAYROLL_ACCRUAL" && request.allocations.isNotEmpty()) ||
            request.allocations.any{it.kind !in setOf("DEDUCTION","REIMBURSEMENT") ||
                !it.domain.matches(Regex("[A-Z][A-Z0-9_-]{0,79}")) || !it.code.matches(Regex("[A-Z][A-Z0-9_-]{0,79}")) ||
                it.reference.length !in 8..240 || it.reference!=it.reference.trim() || it.reference.any{c->c.code<32||c.code==127}} ||
            request.allocations.map{Triple(it.kind,it.domain,it.code)}.distinct().size!=request.allocations.size) payrollGlChanged()
        val allocationJson=buildJsonArray {
            request.allocations.sortedWith(compareBy({it.kind},{it.domain},{it.code})).forEach { a ->
                add(buildJsonObject{put("kind",a.kind);put("domain",a.domain);put("code",a.code);put("accountId",a.accountId.toString());put("reference",a.reference)})
            }
        }
        val id=UUID.nameUUIDFromBytes(("finance-payroll-gl:"+request.operationId).toByteArray(Charsets.UTF_8))
        val fingerprint=IdempotencyKeyContract.fingerprint(buildJsonObject {
            put("organizationId",org.toString());put("runVersionId",runId.toString());put("entryType",request.entryType)
            request.sourceEventId?.let{put("sourceEventId",it.toString())};request.originalJournalId?.let{put("originalJournalId",it.toString())}
            if(request.allocations.isNotEmpty()) put("allocations",allocationJson)
            put("periodId",request.periodId.toString());put("debitAccountId",request.debitAccountId.toString())
            put("creditAccountId",request.creditAccountId.toString());put("amount",amount.stripTrailingZeros().toPlainString());put("currency",request.currency)
        }.toString())
        val execution=try {
            commands.execute(IdempotentCommandSpec(request.operationId,actor,"FINANCE_PAYROLL_GL_POST",
                "FINANCE_PAYROLL_GL",id,fingerprint,correlation)) {
                authorize(actor,org)
                val source=run(org,runId) ?: payrollGlChanged()
                val now=clock.instant()
                val period=accounting.period(org,LocalDate.ofInstant(now,ZoneOffset.UTC)) ?: payrollGlChanged()
                if(period.id!=request.periodId || source.currency!=request.currency || source.preparedAt==null) payrollGlChanged()
                if(request.entryType=="PAYROLL_ACCRUAL") {
                    if(source.superseded||source.accrued)payrollGlChanged()
                    val correction=if(source.correctionRequired)correction(org,runId)?:payrollGlChanged() else null
                    if(request.originalJournalId!=correction?.originalJournalId)payrollGlChanged()
                }
                val sourceAt=if(request.sourceEventId==null) source.preparedAt
                    else jdbc.query("""SELECT e.recorded_at FROM finance_payroll_result_event e JOIN finance_payroll_instruction i
                        ON i.organization_id=e.organization_id AND i.id=e.instruction_id
                        WHERE e.organization_id=? AND i.run_version_id=? AND e.id=?""",
                        {r,_->r.getObject(1,OffsetDateTime::class.java)},org,runId,request.sourceEventId).singleOrNull() ?: payrollGlChanged()
                // The database trigger locks the exact payroll source and accounting period,
                // rejects duplicate/stale postings, and derives the permissible account roles.
                check(jdbc.update("""INSERT INTO finance_payroll_gl_journal(id,operation_id,organization_id,payroll_period_id,run_version_id,
                    release_id,entry_type,source_event_id,original_journal_id,period_id,debit_account_id,credit_account_id,amount,currency,
                    source_recorded_at,posted_by_user_id,posted_at,correlation_id,component_allocations) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb))""",
                    id,request.operationId,org,source.payrollPeriodId,runId,source.releaseId,request.entryType,request.sourceEventId,
                    request.originalJournalId,period.id,request.debitAccountId,request.creditAccountId,amount,request.currency,
                    sourceAt,actor,now.atOffset(ZoneOffset.UTC),correlation,allocationJson.toString())==1)
                audit.append(AuditEventRecord(actorUserId=actor,action="FINANCE_PAYROLL_GL_POSTED",targetType="FINANCE_PAYROLL_GL",
                    targetId=id,previousStateRef=null,newStateRef="POSTED",safeDiffJson="""{"entryType":"${request.entryType}"}""",
                    occurredAt=now,correlationId=correlation,reason="Accounting effect of recorded payroll source"))
                IdempotentCommandOutcome("FINANCE_PAYROLL_GL_POSTED","""{"journalId":"$id"}""")
            }
        } catch(_:DataIntegrityViolationException){payrollGlChanged()}
        authorize(actor,org)
        val exists=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM finance_payroll_gl_journal WHERE organization_id=? AND run_version_id=? AND operation_id=? AND id=?)",
            Boolean::class.java,org,runId,request.operationId,id)
        if(exists!=true) payrollGlChanged()
        return FinancePayrollCommandReceipt(request.operationId,org,id,"POSTED",execution.replayed,ledgerPosted=true)
    }
}

@RestController
@RequestMapping("/v1/finance/payroll/organizations/{orgId}/disbursements/runs/{runId}/ledger")
class FinancePayrollLedgerController(private val service:FinancePayrollLedgerService,private val sessions:IdentitySessionService) {
    @GetMapping
    fun view(http:HttpServletRequest,@PathVariable orgId:UUID,@PathVariable runId:UUID,@RequestParam(defaultValue="0") offset:Int) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.view(actor(http),orgId,runId,offset))

    @PostMapping
    fun post(http:HttpServletRequest,@AuthenticationPrincipal jwt:Jwt,@PathVariable orgId:UUID,@PathVariable runId:UUID,
        @RequestHeader("Idempotency-Key") key:String,@RequestHeader("X-Device-Installation-Id") installation:UUID,
        @RequestBody body:FinancePayrollGlPost):ResponseEntity<FinancePayrollCommandReceipt> {
        IdempotencyKeyContract.requireMatches(key,body.operationId)
        sessions.requireRecentReauthentication(sessions.requireCurrentAccess(jwt,installation))
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.post(actor(http),orgId,runId,body,
            HiltechRequestContext.current(http).correlationId))
    }
    private fun actor(http:HttpServletRequest):UUID=HiltechRequestContext.current(http).identityId
        ?.let{runCatching{UUID.fromString(it)}.getOrNull()} ?: throw ProductApiException("UNAUTHENTICATED","Finance identity required.",HttpStatus.UNAUTHORIZED)
}

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
import java.sql.ResultSet
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class FinanceSettlementSource(
    val sourceType:String,val sourceId:UUID,val sourceKind:String,val sourceJournalId:UUID?,
    val sourceApprovalId:UUID,val amount:String,val currency:String,val sourceRecordedAt:String,
    val instrumentName:String,val counterpartyName:String?,val originalSettlementId:UUID?,
    val debitAccountId:UUID?,val debitAccountName:String?,val creditAccountId:UUID?,val creditAccountName:String?,
    val employeeFundingRecordId:UUID?=null,val expenseClaimId:UUID?=null,val requiredDebitKind:String?=null,
    val debitChoices:List<FinanceSettlementAccountChoice> = emptyList(),val hasMoreDebitChoices:Boolean=false,
)
data class FinanceSettlementAccountChoice(val id:UUID,val code:String,val name:String,val accountKind:String)
data class FinanceSettlementQueue(
    val organizationId:UUID,val period:FinanceDeskPeriod?,val offset:Int,val hasMore:Boolean,
    val records:List<FinanceSettlementSource>,
)
data class FinanceSettlementPost(
    val operationId:UUID,val sourceType:String,val sourceId:UUID,val sourceKind:String,
    val periodId:UUID,val debitAccountId:UUID,val creditAccountId:UUID,val amount:String,val currency:String,
    val accountingTreatmentRef:String?=null,
)
data class FinanceSettlementReceipt(
    val organizationId:UUID,val operationId:UUID,val journalId:UUID,val sourceType:String,val sourceId:UUID,
    val sourceKind:String,val periodId:UUID,val debitAccountId:UUID,val creditAccountId:UUID,
    val amount:String,val currency:String,val replayed:Boolean,
    val moneyTransferredByHiltech:Boolean=false,val accountingTreatmentRef:String?=null,
)

internal val settlementTypes=setOf("BANK_ALLOCATION","CASH_VOUCHER","BANK_VOID")
internal val employeeSettlementTypes=setOf("EMPLOYEE_FUNDING","EMPLOYEE_RETURN","IMPREST_EXPENSE","EXPENSE_REIMBURSEMENT","PAYROLL_RECOVERY")
private val costAccountKinds=setOf("EXPENSE","INVENTORY_ASSET","FIXED_ASSET","OTHER_ASSET")
internal fun settlementSourcePair(type:String,kind:String):Boolean=when(type) {
    in settlementTypes -> kind in setOf("AP","AR")
    "EMPLOYEE_FUNDING","EMPLOYEE_RETURN" -> kind in setOf("EA","EI")
    "IMPREST_EXPENSE" -> kind=="EI"
    "EXPENSE_REIMBURSEMENT" -> kind=="ER"
    "PAYROLL_RECOVERY" -> kind=="PR"
    else -> false
}
internal fun settlementDebitKindAllowed(required:String,actual:String):Boolean=
    if(required=="COST") actual in costAccountKinds else required==actual
internal fun settlementTreatmentValid(type:String,ref:String?):Boolean=
    if(type in setOf("IMPREST_EXPENSE","EXPENSE_REIMBURSEMENT"))
        ref!=null && ref==ref.trim() && ref.length in 8..240 && ref.none {it.code<32 || it.code==127}
    else ref==null
internal fun settlementAmount(value:String):BigDecimal? =
    value.takeIf { it.matches(Regex("[0-9]{1,18}([.][0-9]{1,6})?")) }
        ?.let(::BigDecimal)?.takeIf { it.signum()>0 }

private fun settlementDenied():Nothing=throw ProductApiException("FINANCE_SETTLEMENT_LEDGER_UNAVAILABLE",
    "Current source and accounting posting authority are required.",HttpStatus.NOT_FOUND)
private fun settlementChanged():Nothing=throw ProductApiException("FINANCE_SETTLEMENT_LEDGER_CHANGED",
    "The recorded source, approved accounts, or open period changed. Refresh before posting.",HttpStatus.CONFLICT)

internal class FinanceSettlementLedgerStore(private val jdbc:JdbcTemplate) {
    fun period(org:UUID,day:LocalDate):FinanceDeskPeriod? = jdbc.query("""
        SELECT p.id,p.date_start,p.date_end FROM finance_gl_period p
        WHERE p.organization_id=? AND p.state='OPEN' AND p.date_start<=? AND p.date_end>=?
          AND NOT EXISTS(SELECT 1 FROM finance_gl_period_close c WHERE c.organization_id=p.organization_id AND c.period_id=p.id)
        ORDER BY p.id LIMIT 2
    """.trimIndent(),{r,_->FinanceDeskPeriod(r.getObject("id",UUID::class.java),r.getDate("date_start").toString(),
        r.getDate("date_end").toString(),"OPEN")},org,java.sql.Date.valueOf(day),java.sql.Date.valueOf(day)).singleOrNull()

    private val select = """
        SELECT s.*,d.account_name AS debit_name,c.account_name AS credit_name
        FROM finance_settlement_gl_source_v2 s
        LEFT JOIN finance_gl_account d ON d.organization_id=s.organization_id AND d.id=s.debit_account_id
        LEFT JOIN finance_gl_account c ON c.organization_id=s.organization_id AND c.id=s.credit_account_id
    """.trimIndent()
    private fun row(r:ResultSet)=FinanceSettlementSource(
        r.getString("source_type"),r.getObject("source_id",UUID::class.java),r.getString("source_kind"),
        r.getObject("source_journal_id",UUID::class.java),r.getObject("source_approval_id",UUID::class.java),
        r.getBigDecimal("amount").toPlainString(),r.getString("currency").trim(),
        r.getObject("source_recorded_at",OffsetDateTime::class.java).toInstant().toString(),
        r.getString("instrument_name"),r.getString("counterparty_name"),r.getObject("original_settlement_id",UUID::class.java),
        r.getObject("debit_account_id",UUID::class.java),r.getString("debit_name"),
        r.getObject("credit_account_id",UUID::class.java),r.getString("credit_name"),
        r.getObject("employee_funding_record_id",UUID::class.java),r.getObject("expense_claim_id",UUID::class.java),r.getString("required_debit_kind"))

    fun queue(org:UUID,pairs:List<Pair<String,String>>,offset:Int):List<FinanceSettlementSource> {
        if(pairs.isEmpty()) return emptyList()
        val predicate=pairs.joinToString(" OR ") { "(s.source_type=? AND s.source_kind=?)" }
        val args=listOf<Any>(org)+pairs.flatMap { listOf(it.first,it.second) }+listOf(51,offset)
        return jdbc.query(select+"""
            WHERE s.organization_id=? AND ($predicate)
              AND NOT EXISTS(SELECT 1 FROM finance_settlement_gl_journal j
                WHERE j.organization_id=s.organization_id AND j.source_type=s.source_type AND j.source_id=s.source_id)
            ORDER BY s.source_recorded_at,s.source_type,s.source_id LIMIT ? OFFSET ?
        """,{r,_->row(r)},*args.toTypedArray())
    }
    fun debitChoices(org:UUID,currency:String,required:String):List<FinanceSettlementAccountChoice> {
        val kinds=if(required=="COST") costAccountKinds else setOf(required)
        val args=listOf<Any>(org,currency)+kinds.toList()
        return jdbc.query("""SELECT id,account_code,account_name,account_kind FROM finance_gl_account
            WHERE organization_id=? AND state='APPROVED' AND (currency IS NULL OR currency=?)
              AND account_kind IN (${kinds.joinToString(","){"?"}})
            ORDER BY account_code,id LIMIT 101""",{r,_->FinanceSettlementAccountChoice(r.getObject("id",UUID::class.java),
                r.getString("account_code"),r.getString("account_name"),r.getString("account_kind"))},*args.toTypedArray())
    }

    fun debitMatches(org:UUID,source:FinanceSettlementSource,debit:UUID):Boolean {
        val required=source.requiredDebitKind ?: return source.debitAccountId==debit
        val kind=jdbc.query("""SELECT account_kind FROM finance_gl_account WHERE organization_id=? AND id=?
            AND state='APPROVED' AND (currency IS NULL OR currency=?)""",{r,_->r.getString(1)},org,debit,source.currency).singleOrNull()
        return kind!=null && settlementDebitKindAllowed(required,kind)
    }
    fun source(org:UUID,type:String,id:UUID):FinanceSettlementSource? =
        jdbc.query(select+" WHERE s.organization_id=? AND s.source_type=? AND s.source_id=?",{r,_->row(r)},org,type,id).singleOrNull()

    fun insert(org:UUID,actor:UUID,request:FinanceSettlementPost,source:FinanceSettlementSource,id:UUID,at:OffsetDateTime,correlation:String) {
        check(jdbc.update("""
            INSERT INTO finance_settlement_gl_journal(id,operation_id,organization_id,source_type,source_id,
                source_kind,source_journal_id,source_approval_id,original_settlement_id,period_id,
                debit_account_id,credit_account_id,amount,currency,source_recorded_at,posted_by_user_id,posted_at,correlation_id,
                employee_funding_record_id,expense_claim_id,accounting_treatment_ref)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """.trimIndent(),id,request.operationId,org,request.sourceType,request.sourceId,request.sourceKind,
            source.sourceJournalId,source.sourceApprovalId,source.originalSettlementId,request.periodId,
            request.debitAccountId,request.creditAccountId,BigDecimal(source.amount),source.currency,
            OffsetDateTime.parse(source.sourceRecordedAt),actor,at,correlation,source.employeeFundingRecordId,source.expenseClaimId,request.accountingTreatmentRef)==1)
    }
    fun receipt(org:UUID,operation:UUID,replayed:Boolean):FinanceSettlementReceipt? =
        jdbc.query("""SELECT * FROM finance_settlement_gl_journal WHERE organization_id=? AND operation_id=?""",
            {r,_->FinanceSettlementReceipt(org,operation,r.getObject("id",UUID::class.java),
                r.getString("source_type"),r.getObject("source_id",UUID::class.java),r.getString("source_kind"),
                r.getObject("period_id",UUID::class.java),r.getObject("debit_account_id",UUID::class.java),
                r.getObject("credit_account_id",UUID::class.java),r.getBigDecimal("amount").toPlainString(),
                r.getString("currency").trim(),replayed,accountingTreatmentRef=r.getString("accounting_treatment_ref"))},org,operation).singleOrNull()
}

@Service
class FinanceSettlementLedgerService @org.springframework.beans.factory.annotation.Autowired constructor(
    jdbc:JdbcTemplate,private val approvals:ApprovalPersistencePort,private val environment:Environment,
    private val clock:Clock,private val commands:IdempotentCommandExecutor,private val audit:AuditEventWriter,
    private val operationalAuthority:OperationalAuthorityPort?=null,
) {
    private val store=FinanceSettlementLedgerStore(jdbc)
    private fun authorized(actor:UUID,org:UUID,type:String,kind:String):Boolean {
        if(!settlementSourcePair(type,kind)) return false
        val source=when(type) {
            "BANK_ALLOCATION"->"hiltech.finance.cash-allocation" to ".operator-authority-key"
            "CASH_VOUCHER"->"hiltech.finance.cashbox" to ".voucher-operator-authority-key"
            "BANK_VOID"->"hiltech.finance.cash-allocation-void" to ".operator-authority-key"
            "EMPLOYEE_FUNDING"->"hiltech.finance.employee-funding" to ".operator-authority-key"
            "EMPLOYEE_RETURN","IMPREST_EXPENSE","PAYROLL_RECOVERY"->"hiltech.finance.employee-settlement" to ".operator-authority-key"
            "EXPENSE_REIMBURSEMENT"->"hiltech.finance.expense-reimbursement" to ".operator-authority-key"
            else->return false
        }
        val posting=when(kind) {
            "AP","EA","EI","ER","PR"->"hiltech.finance.ap-posting"
            "AR"->"hiltech.finance.receivables.ar-posting"
            else->return false
        }
        val now=clock.instant()
        return listOf(source,posting to ".operator-authority-key").all { (base,suffix) ->
            val key=base+suffix
            environment.getProperty(base+".enabled",Boolean::class.java,false) &&
                currentOperationalAuthority(operationalAuthority,approvals,actor,org,key,environment.getProperty(key),now)!=null
        }
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    fun queue(actor:UUID,org:UUID,offset:Int,includeEmployee:Boolean=false):FinanceSettlementQueue {
        if(offset !in 0..1_000_000) settlementDenied()
        val types=settlementTypes+(if(includeEmployee) employeeSettlementTypes else emptySet())
        val pairs=types.flatMap {type->listOf("AP","AR","EA","EI","ER","PR").map{type to it}}
            .filter{authorized(actor,org,it.first,it.second)}
        if(pairs.isEmpty()) settlementDenied()
        val period=store.period(org,LocalDate.ofInstant(clock.instant(),ZoneOffset.UTC))
        val records=store.queue(org,pairs,offset)
        val choices=mutableMapOf<Pair<String,String>,List<FinanceSettlementAccountChoice>>()
        val page=records.take(50).map { row ->
            val required=row.requiredDebitKind
            if(required==null) row else {
                val available=choices.getOrPut(row.currency to required){store.debitChoices(org,row.currency,required)}
                row.copy(debitChoices=available.take(100),hasMoreDebitChoices=available.size>100)
            }
        }
        return FinanceSettlementQueue(org,period,offset,records.size>50,page)
    }

    fun post(actor:UUID,org:UUID,request:FinanceSettlementPost,correlation:String):FinanceSettlementReceipt {
        if(!authorized(actor,org,request.sourceType,request.sourceKind)) settlementDenied()
        val amount=settlementAmount(request.amount) ?: settlementChanged()
        if(!request.currency.matches(Regex("[A-Z]{3}")) || request.debitAccountId==request.creditAccountId ||
            !settlementTreatmentValid(request.sourceType,request.accountingTreatmentRef)) settlementChanged()
        val id=UUID.nameUUIDFromBytes("finance-settlement-gl:${request.operationId}".toByteArray(Charsets.UTF_8))
        val fingerprint=IdempotencyKeyContract.fingerprint(buildJsonObject {
            put("organizationId",org.toString());put("sourceType",request.sourceType);put("sourceId",request.sourceId.toString())
            put("sourceKind",request.sourceKind);put("periodId",request.periodId.toString())
            put("debitAccountId",request.debitAccountId.toString());put("creditAccountId",request.creditAccountId.toString())
            put("amount",amount.stripTrailingZeros().toPlainString());put("currency",request.currency)
            request.accountingTreatmentRef?.let {put("accountingTreatmentRef",it)}
        }.toString())
        val execution=try {
            commands.execute(IdempotentCommandSpec(request.operationId,actor,"FINANCE_SETTLEMENT_GL_POST",
                "FINANCE_SETTLEMENT_GL",id,fingerprint,correlation)) {
                if(!authorized(actor,org,request.sourceType,request.sourceKind)) settlementDenied()
                val now=clock.instant()
                val period=store.period(org,LocalDate.ofInstant(now,ZoneOffset.UTC)) ?: settlementChanged()
                val source=store.source(org,request.sourceType,request.sourceId) ?: settlementChanged()
                if(period.id!=request.periodId || source.sourceKind!=request.sourceKind ||
                    !store.debitMatches(org,source,request.debitAccountId) || source.creditAccountId!=request.creditAccountId ||
                    source.currency!=request.currency || BigDecimal(source.amount).compareTo(amount)!=0) settlementChanged()
                store.insert(org,actor,request,source,id,now.atOffset(ZoneOffset.UTC),correlation)
                audit.append(AuditEventRecord(actorUserId=actor,action="FINANCE_SETTLEMENT_GL_POSTED",
                    targetType="FINANCE_SETTLEMENT_GL",targetId=id,previousStateRef=null,newStateRef="POSTED",
                    safeDiffJson="""{"sourceType":"${request.sourceType}"}""",occurredAt=now,correlationId=correlation,
                    reason="Recorded settlement accounting effect"))
                IdempotentCommandOutcome("FINANCE_SETTLEMENT_GL_POSTED","""{"journalId":"$id"}""")
            }
        } catch(_:DataIntegrityViolationException) { settlementChanged() }
        if(!authorized(actor,org,request.sourceType,request.sourceKind)) settlementDenied()
        return store.receipt(org,request.operationId,execution.replayed) ?: settlementChanged()
    }
}

@RestController
class FinanceSettlementLedgerController(private val service:FinanceSettlementLedgerService,private val sessions:IdentitySessionService) {
    @GetMapping("/v1/finance/organizations/{orgId}/settlement-ledger")
    fun queue(http:HttpServletRequest,@PathVariable orgId:UUID,@RequestParam(defaultValue="0") offset:Int,
        @RequestParam(defaultValue="false") includeEmployee:Boolean):ResponseEntity<FinanceSettlementQueue> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.queue(actor(http),orgId,offset,includeEmployee))

    @PostMapping("/v1/finance/organizations/{orgId}/settlement-ledger")
    fun post(http:HttpServletRequest,@AuthenticationPrincipal jwt:Jwt,@PathVariable orgId:UUID,
        @RequestHeader("Idempotency-Key") key:String,@RequestHeader("X-Device-Installation-Id") installationId:UUID,
        @RequestBody body:FinanceSettlementPost):ResponseEntity<FinanceSettlementReceipt> {
        IdempotencyKeyContract.requireMatches(key,body.operationId)
        sessions.requireRecentReauthentication(sessions.requireCurrentAccess(jwt,installationId))
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.post(actor(http),orgId,body,
            HiltechRequestContext.current(http).correlationId))
    }
    private fun actor(http:HttpServletRequest):UUID=HiltechRequestContext.current(http).identityId
        ?.let{runCatching{UUID.fromString(it)}.getOrNull()}
        ?:throw ProductApiException("UNAUTHENTICATED","Finance identity required.",HttpStatus.UNAUTHORIZED)
}

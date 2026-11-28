package com.hiltech.server.companysettings

import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.identity.IdentitySessionService
import com.hiltech.server.operationalaccess.OperationalAuthorityPort
import com.hiltech.server.operationalaccess.currentOperationalAuthority
import com.hiltech.server.platform.*
import jakarta.servlet.http.HttpServletRequest
import kotlinx.serialization.json.*
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
import java.time.ZoneOffset
import java.util.UUID

data class FinanceOpeningObligation(val counterpartyId:UUID,val documentReference:String,val documentDate:String,
    val amount:String,val dueDate:String?=null)
data class FinanceOpeningStockValue(val movementId:UUID,val quantity:String,val unit:String,val sourceFingerprint:String,
    val amount:String,val valuationReference:String)
data class FinanceOpeningLine(val accountId:UUID,val side:String,val amount:String,val reference:String,val bankStatementId:UUID?=null,
    val obligations:List<FinanceOpeningObligation> = emptyList(),val stockValues:List<FinanceOpeningStockValue> = emptyList())
data class FinanceOpeningInput(val operationId:UUID,val periodId:UUID,val currency:String,
    val companyReference:String,val sourceReference:String,val lines:List<FinanceOpeningLine>)
data class FinanceOpeningAccount(val id:UUID,val code:String,val name:String,val accountKind:String,
    val bankStatementId:UUID?,val bankSide:String?,val bankAmount:String?)
data class FinanceOpeningPeriod(val id:UUID,val dateStart:String,val dateEnd:String)
data class FinanceOpeningCounterparty(val id:UUID,val kind:String,val code:String,val name:String)
data class FinanceOpeningView(val organizationId:UUID,val currency:String,val period:FinanceOpeningPeriod?,
    val existingJournalId:UUID?,val canPost:Boolean,val accounts:List<FinanceOpeningAccount>,val hasMore:Boolean,
    val counterparties:List<FinanceOpeningCounterparty> = emptyList(),val hasMoreCounterparties:Boolean=false)
data class FinanceOpeningStockChoice(val movementId:UUID,val itemCode:String,val itemName:String,val locationCode:String,
    val quantity:String,val unit:String,val sourceFingerprint:String)
data class FinanceOpeningStockChoices(val organizationId:UUID,val periodId:UUID,val currency:String,val offset:Int,
    val records:List<FinanceOpeningStockChoice>,val hasMore:Boolean)
data class FinanceOpeningReceipt(val organizationId:UUID,val operationId:UUID,val journalId:UUID,val periodId:UUID,
    val currency:String,val openingDate:String,val replayed:Boolean)

private fun openingRef(value:String,max:Int)=value.length in 8..max && value==value.trim() && value.none{it.code<32||it.code==127}
private fun openingAmount(value:String):BigDecimal? =
    value.takeIf{it.matches(Regex("[0-9]{1,18}([.][0-9]{1,6})?"))}
        ?.let(::BigDecimal)?.takeIf{it.signum()>0}
internal fun validFinanceOpening(input:FinanceOpeningInput):Boolean {
    if(!input.currency.matches(Regex("[A-Z]{3}")) || !openingRef(input.companyReference,160) ||
        !openingRef(input.sourceReference,240) || input.lines.size !in 2..32 ||
        input.lines.map{it.accountId}.distinct().size!=input.lines.size)return false
    if(input.lines.any{it.side !in setOf("DEBIT","CREDIT")||openingAmount(it.amount)==null||!openingRef(it.reference,240)})return false
    if(input.lines.sumOf{it.obligations.size}>1000)return false
    for(line in input.lines){
        if(line.obligations.isEmpty())continue
        if(line.bankStatementId!=null||line.obligations.any{o->
            val date=runCatching{LocalDate.parse(o.documentDate)}.getOrNull()
            val due=o.dueDate?.let{runCatching{LocalDate.parse(it)}.getOrNull()}
            o.documentReference.length !in 2..96||o.documentReference!=o.documentReference.trim()||
            o.documentReference.any{it.code<32||it.code==127}||date==null||
            (o.dueDate!=null&&(due==null||due<date))||openingAmount(o.amount)==null
        }||line.obligations.map{it.counterpartyId to it.documentReference.lowercase(java.util.Locale.ROOT)}.distinct().size!=line.obligations.size)return false
        if(line.obligations.fold(BigDecimal.ZERO){s,o->s+BigDecimal(o.amount)}.compareTo(BigDecimal(line.amount))!=0)return false
    }
    val stock=input.lines.flatMap{it.stockValues}
    if(stock.size>1000||stock.map{it.movementId}.distinct().size!=stock.size)return false
    for(line in input.lines.filter{it.stockValues.isNotEmpty()}){
        if(line.side!="DEBIT"||line.bankStatementId!=null||line.obligations.isNotEmpty()||
            line.stockValues.any{v->!v.quantity.matches(Regex("[0-9]{1,14}([.][0-9]{1,6})?"))||
                BigDecimal(v.quantity).signum()<=0||v.unit.isBlank()||v.unit.length>32||
                !v.sourceFingerprint.matches(Regex("[0-9a-f]{64}"))||
                openingAmount(v.amount)==null||!openingRef(v.valuationReference,240)}||
            line.stockValues.fold(BigDecimal.ZERO){s,v->s+BigDecimal(v.amount)}.compareTo(BigDecimal(line.amount))!=0)return false
    }
    fun total(side:String)=input.lines.filter{it.side==side}.fold(BigDecimal.ZERO){sum,line->sum+BigDecimal(line.amount)}
    val debit=total("DEBIT")
    return debit.signum()>0 && debit.compareTo(total("CREDIT"))==0
}
private fun openingDenied():Nothing=throw ProductApiException("FINANCE_OPENING_UNAVAILABLE",
    "Current company settings and accounting posting responsibilities are required.",HttpStatus.NOT_FOUND)
private fun openingChanged():Nothing=throw ProductApiException("FINANCE_OPENING_CHANGED",
    "The opening source, period, accounts or balance is no longer eligible.",HttpStatus.CONFLICT)

@Service
class FinanceOpeningService @org.springframework.beans.factory.annotation.Autowired constructor(
    private val authority:CompanySettingsAuthorityPort,private val approvals:ApprovalPersistencePort,
    private val environment:Environment,private val jdbc:JdbcTemplate,private val commands:IdempotentCommandExecutor,
    private val audit:AuditEventWriter,private val clock:Clock,private val operationalAuthority:OperationalAuthorityPort?=null,
) {
    private fun authorize(actor:UUID,org:UUID) {
        val key="hiltech.finance.ap-posting.operator-authority-key"
        if(!authority.canManageCompanySettings(actor,org) || !environment.getProperty("hiltech.finance.ap-posting.enabled",Boolean::class.java,false) ||
            currentOperationalAuthority(operationalAuthority,approvals,actor,org,key,environment.getProperty(key),clock.instant())==null)openingDenied()
    }
    private fun period(org:UUID):FinanceOpeningPeriod?=jdbc.query("""
        SELECT p.id,p.date_start,p.date_end FROM finance_gl_period p WHERE p.organization_id=? AND p.state='OPEN'
        AND p.date_start<=?
        AND NOT EXISTS(SELECT 1 FROM finance_gl_period earlier WHERE earlier.organization_id=p.organization_id AND earlier.id<>p.id
            AND earlier.date_start<=p.date_end)
        AND NOT EXISTS(SELECT 1 FROM finance_gl_period_close c WHERE c.organization_id=p.organization_id AND c.period_id=p.id)
    """.trimIndent(),{r,_->FinanceOpeningPeriod(r.getObject("id",UUID::class.java),r.getDate("date_start").toString(),r.getDate("date_end").toString())},
        org,java.sql.Date.valueOf(LocalDate.ofInstant(clock.instant(),ZoneOffset.UTC))).singleOrNull()

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    fun view(actor:UUID,org:UUID,currency:String):FinanceOpeningView {
        authorize(actor,org)
        if(!currency.matches(Regex("[A-Z]{3}")))openingChanged()
        val existing=jdbc.query("SELECT id FROM finance_gl_opening_journal WHERE organization_id=? AND currency=?",
            {r,_->r.getObject(1,UUID::class.java)},org,currency).singleOrNull()
        val first=period(org)
        val accounts=if(first==null||existing!=null)emptyList() else jdbc.query("""
            SELECT a.id,a.account_code,a.account_name,a.account_kind,s.id AS statement_id,s.opening_balance
            FROM finance_gl_account a
            LEFT JOIN LATERAL (
                SELECT b.id,b.opening_balance FROM finance_bank_statement_intake b
                WHERE b.organization_id=a.organization_id AND b.account_id=a.treasury_bank_account_id AND b.currency=?
                  AND b.date_start=? AND b.imported_at<=? AND b.opening_balance<>0
                  AND (SELECT count(*) FROM finance_bank_statement_intake x WHERE x.organization_id=b.organization_id
                      AND x.account_id=b.account_id AND x.currency=b.currency AND x.date_start=b.date_start)=1
            ) s ON a.account_kind='BANK_ASSET'
            WHERE a.organization_id=? AND a.state='APPROVED' AND (a.currency IS NULL OR a.currency=?)
            AND a.account_kind IN('FIXED_ASSET','OTHER_ASSET','OTHER_LIABILITY','EQUITY','BANK_ASSET','CUSTOMER_RECEIVABLE','SUPPLIER_PAYABLE','INVENTORY_ASSET')
            AND (a.account_kind<>'BANK_ASSET' OR s.id IS NOT NULL)
            ORDER BY a.account_code,a.id LIMIT 101
        """.trimIndent(),{r,_->
            val bank=r.getBigDecimal("opening_balance")
            FinanceOpeningAccount(r.getObject("id",UUID::class.java),r.getString("account_code"),r.getString("account_name"),
                r.getString("account_kind"),r.getObject("statement_id",UUID::class.java),
                bank?.let{if(it.signum()>0)"DEBIT" else "CREDIT"},bank?.abs()?.toPlainString())
        },currency,java.sql.Date.valueOf(first.dateStart),clock.instant().atOffset(ZoneOffset.UTC),org,currency)
        val counterparties=if(first==null||existing!=null)emptyList()else jdbc.query("""
            SELECT c.counterparty_id,c.source_kind,o.organization_code,o.display_name
            FROM finance_opening_counterparty c JOIN organization o ON o.id=c.counterparty_id
            WHERE c.organization_id=? ORDER BY c.source_kind,o.display_name,o.id LIMIT 501
        """.trimIndent(),{r,_->FinanceOpeningCounterparty(r.getObject(1,UUID::class.java),r.getString(2),
            r.getString(3),r.getString(4))},org)
        return FinanceOpeningView(org,currency,first,existing,first!=null&&existing==null,accounts.take(100),accounts.size>100,
            counterparties.take(500),counterparties.size>500)
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    fun stockSources(actor:UUID,org:UUID,currency:String,offset:Int):FinanceOpeningStockChoices {
        authorize(actor,org)
        if(!currency.matches(Regex("[A-Z]{3}"))||offset !in 0..1_000_000)openingChanged()
        val first=period(org)?:openingChanged()
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM finance_gl_opening_journal WHERE organization_id=? AND currency=?)",
            Boolean::class.java,org,currency)==true)openingChanged()
        val rows=jdbc.query("""
            SELECT s.movement_id,s.item_code,s.item_name,s.location_code,s.quantity,s.unit_code,s.source_fingerprint
            FROM finance_stock_opening_source s WHERE s.organization_id=? AND s.opening_date=? AND s.recorded_at<=?
            AND NOT EXISTS(SELECT 1 FROM finance_gl_opening_stock_value v WHERE v.organization_id=s.organization_id AND v.movement_id=s.movement_id)
            ORDER BY s.item_code,s.location_code,s.movement_id LIMIT 51 OFFSET ?
        """.trimIndent(),{r,_->FinanceOpeningStockChoice(r.getObject(1,UUID::class.java),r.getString(2),r.getString(3),r.getString(4),
            r.getBigDecimal(5).toPlainString(),r.getString(6),r.getString(7))},org,java.sql.Date.valueOf(first.dateStart),
            clock.instant().atOffset(ZoneOffset.UTC),offset)
        return FinanceOpeningStockChoices(org,first.id,currency,offset,rows.take(50),rows.size>50)
    }

    fun post(actor:UUID,org:UUID,input:FinanceOpeningInput,correlation:String):FinanceOpeningReceipt {
        authorize(actor,org)
        if(!validFinanceOpening(input))openingChanged()
        val id=UUID.nameUUIDFromBytes(("finance-opening:"+input.operationId).toByteArray(Charsets.UTF_8))
        val lines=buildJsonArray {
            input.lines.sortedBy{it.accountId.toString()}.forEach{line->add(buildJsonObject{
                put("accountId",line.accountId.toString());put("side",line.side)
                put("amount",BigDecimal(line.amount).stripTrailingZeros().toPlainString());put("reference",line.reference)
                line.bankStatementId?.let{put("bankStatementId",it.toString())}
                if(line.stockValues.isNotEmpty())put("stockValues",buildJsonArray{
                    line.stockValues.sortedBy{it.movementId.toString()}.forEach{v->add(buildJsonObject{
                        put("movementId",v.movementId.toString());put("quantity",BigDecimal(v.quantity).stripTrailingZeros().toPlainString())
                        put("unit",v.unit);put("sourceFingerprint",v.sourceFingerprint)
                        put("amount",BigDecimal(v.amount).stripTrailingZeros().toPlainString());put("valuationReference",v.valuationReference)
                    })}
                })
                if(line.obligations.isNotEmpty())put("obligations",buildJsonArray{
                    line.obligations.sortedWith(compareBy({it.counterpartyId.toString()},{it.documentReference})).forEach{o->
                        add(buildJsonObject{
                            put("counterpartyId",o.counterpartyId.toString());put("documentReference",o.documentReference)
                            put("documentDate",o.documentDate);put("amount",BigDecimal(o.amount).stripTrailingZeros().toPlainString())
                            o.dueDate?.let{put("dueDate",it)}
                        })
                    }
                })
            })}
        }
        val fingerprint=IdempotencyKeyContract.fingerprint(buildJsonObject{
            put("organizationId",org.toString());put("periodId",input.periodId.toString());put("currency",input.currency)
            put("companyReference",input.companyReference);put("sourceReference",input.sourceReference);put("lines",lines)
        }.toString())
        val execution=try {
            commands.execute(IdempotentCommandSpec(input.operationId,actor,"FINANCE_OPENING_POST","FINANCE_OPENING",id,fingerprint,correlation)){
                authorize(actor,org)
                val first=period(org)?:openingChanged()
                if(first.id!=input.periodId)openingChanged()
                val at=clock.instant()
                check(jdbc.update("""INSERT INTO finance_gl_opening_journal(id,operation_id,organization_id,period_id,opening_date,
                    currency,company_reference,source_reference,lines,posted_by_user_id,posted_at,correlation_id)
                    VALUES(?,?,?,?,?,?,?,?,CAST(? AS jsonb),?,?,?)""",id,input.operationId,org,first.id,
                    java.sql.Date.valueOf(first.dateStart),input.currency,input.companyReference,input.sourceReference,
                    lines.toString(),actor,at.atOffset(ZoneOffset.UTC),correlation)==1)
                audit.append(AuditEventRecord(actorUserId=actor,action="FINANCE_OPENING_POSTED",targetType="FINANCE_OPENING",
                    targetId=id,previousStateRef=null,newStateRef="POSTED",safeDiffJson="""{"lineCount":${input.lines.size}}""",
                    occurredAt=at,correlationId=correlation,reason="Company-attested cutover opening accounting journal"))
                IdempotentCommandOutcome("FINANCE_OPENING_POSTED","""{"journalId":"$id"}""")
            }
        }catch(_:DataIntegrityViolationException){openingChanged()}
        authorize(actor,org)
        return jdbc.query("""SELECT id,period_id,opening_date,currency FROM finance_gl_opening_journal
            WHERE organization_id=? AND operation_id=? AND id=?""",{r,_->FinanceOpeningReceipt(org,input.operationId,
            r.getObject("id",UUID::class.java),r.getObject("period_id",UUID::class.java),r.getString("currency").trim(),
            r.getDate("opening_date").toString(),execution.replayed)},org,input.operationId,id).singleOrNull()?:openingChanged()
    }
}

@RestController
@RequestMapping("/v1/organizations/{organizationId}/administration/financial-configuration/opening")
class FinanceOpeningController(private val service:FinanceOpeningService,private val sessions:IdentitySessionService) {
    @GetMapping
    fun view(request:HttpServletRequest,@PathVariable organizationId:UUID,@RequestParam currency:String) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.view(actor(request),organizationId,currency))
    @GetMapping("/stock-sources")
    fun stockSources(request:HttpServletRequest,@PathVariable organizationId:UUID,@RequestParam currency:String,
        @RequestParam(defaultValue="0") offset:Int)=ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(service.stockSources(actor(request),organizationId,currency,offset))
    @PostMapping
    fun post(request:HttpServletRequest,@PathVariable organizationId:UUID,@AuthenticationPrincipal jwt:Jwt,
        @RequestHeader("Idempotency-Key") key:String,@RequestHeader("X-Device-Installation-Id") installation:UUID,
        @RequestBody body:FinanceOpeningInput):ResponseEntity<FinanceOpeningReceipt> {
        IdempotencyKeyContract.requireMatches(key,body.operationId)
        sessions.requireRecentReauthentication(sessions.requireCurrentAccess(jwt,installation))
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.post(actor(request),organizationId,body,
            HiltechRequestContext.current(request).correlationId))
    }
    private fun actor(request:HttpServletRequest):UUID=HiltechRequestContext.current(request).identityId
        ?.let{runCatching{UUID.fromString(it)}.getOrNull()} ?: throw ProductApiException("UNAUTHENTICATED","Authentication required.",HttpStatus.UNAUTHORIZED)
}

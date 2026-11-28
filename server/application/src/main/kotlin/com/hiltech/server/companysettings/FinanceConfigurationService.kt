package com.hiltech.server.companysettings

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Configuration writes use the existing immutable company chart, periods and bank register. */
enum class FinanceConfigurationKind { ACCOUNT, PERIOD, BANK, FINANCING }
data class FinanceConfigurationInput(
    val operationId: UUID,
    val kind: FinanceConfigurationKind,
    val authorizationReference: String,
    val code: String? = null,
    val name: String? = null,
    val accountKind: String? = null,
    val currency: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val treasuryInstrumentId: UUID? = null,
    val financingKind: String? = null,
    val financingAccountId: UUID? = null,
    val authorizedAmount: String? = null,
)
data class FinanceConfigurationRecord(
    val id: UUID,
    val kind: String,
    val state: String,
    val authorizationReference: String,
    val code: String? = null,
    val name: String? = null,
    val accountKind: String? = null,
    val currency: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val treasuryInstrumentId: UUID? = null,
    val financingKind: String? = null,
    val financingAccountId: UUID? = null,
    val authorizedAmount: String? = null,
)
data class FinanceTreasuryInstrument(val id: UUID,val name: String,val currency: String)
data class FinanceTreasuryInstrumentPage(val organizationId: UUID,val accountKind: String,
    val records: List<FinanceTreasuryInstrument>,val hasMore: Boolean)
data class FinanceFinancingAccount(val id:UUID,val code:String,val name:String,val accountKind:String)
data class FinanceFinancingAccountPage(val organizationId:UUID,val financingKind:String,val currency:String,
    val records:List<FinanceFinancingAccount>,val hasMore:Boolean)
data class FinanceConfigurationPage(val organizationId: UUID, val kind: String,
    val offset: Int, val hasMore: Boolean, val records: List<FinanceConfigurationRecord>)
data class FinanceConfigurationReceipt(val operationId: UUID, val organizationId: UUID,
    val record: FinanceConfigurationRecord, val replayed: Boolean)

private val financeAccountKinds = setOf("SUPPLIER_PAYABLE", "EXPENSE", "INVENTORY_ASSET", "FIXED_ASSET",
    "TAX_RECOVERABLE", "OTHER_ASSET", "CUSTOMER_RECEIVABLE", "SALES_REVENUE", "TAX_PAYABLE","BANK_ASSET","CASH_ASSET","EMPLOYEE_ADVANCE_ASSET","EMPLOYEE_IMPREST_ASSET","PAYROLL_EXPENSE","PAYROLL_PAYABLE","PAYROLL_DEDUCTION_PAYABLE","EQUITY","OTHER_LIABILITY")
private val treasuryKinds=setOf("BANK_ASSET","CASH_ASSET")
internal fun financingAccountKind(kind:String?):String?=when(kind){
    "OWNER_CAPITAL"->"EQUITY"
    "OWNER_LOAN","EXTERNAL_LOAN"->"OTHER_LIABILITY"
    else->null
}
private fun financingAmount(value:String?):java.math.BigDecimal? =
    value?.takeIf{it.matches(Regex("[0-9]{1,18}([.][0-9]{1,6})?"))}
        ?.let{java.math.BigDecimal(it)}?.takeIf{it.signum()>0}?.stripTrailingZeros()
private val currencyPattern = Regex("[A-Z]{3}")
private val maskedBankPattern = Regex("(?:[A-Za-z][A-Za-z0-9_-]{0,23}:)?[*]{2,12}[0-9]{2,4}")
private fun plain(value: String?, min: Int, max: Int): Boolean =
    value != null && value.length in min..max && value == value.trim() && value.none { it.code < 32 || it.code == 127 }

private fun date(value: String?): LocalDate? =
    value?.takeIf { it.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) && !it.startsWith("0000") }
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

internal fun validFinanceConfiguration(input: FinanceConfigurationInput): Boolean {
    if (!plain(input.authorizationReference, 8, if (input.kind == FinanceConfigurationKind.BANK) 180 else 160)) return false
    if (input.currency != null && !currencyPattern.matches(input.currency)) return false
    if(input.kind!=FinanceConfigurationKind.FINANCING &&
       (input.financingKind!=null||input.financingAccountId!=null||input.authorizedAmount!=null))return false
    return when (input.kind) {
        FinanceConfigurationKind.ACCOUNT ->
            plain(input.code, 1, 64) && plain(input.name, 1, 180) && input.accountKind in financeAccountKinds &&
                input.startDate == null && input.endDate == null &&
                if(input.accountKind in treasuryKinds) input.treasuryInstrumentId!=null && input.currency!=null
                else input.treasuryInstrumentId==null
        FinanceConfigurationKind.PERIOD -> {
            val start = date(input.startDate)
            val end = date(input.endDate)
            input.code == null && input.name == null && input.accountKind == null && input.currency == null &&
                input.treasuryInstrumentId==null && start != null && end != null && !end.isBefore(start)
        }
        FinanceConfigurationKind.FINANCING ->
            plain(input.code,2,64) && plain(input.name,2,180) && input.currency!=null &&
                financingAccountKind(input.financingKind)!=null && input.financingAccountId!=null &&
                financingAmount(input.authorizedAmount)!=null && input.accountKind==null &&
                input.startDate==null && input.endDate==null && input.treasuryInstrumentId==null
        FinanceConfigurationKind.BANK ->
            input.code?.matches(maskedBankPattern) == true && plain(input.name, 3, 160) && input.currency != null &&
                input.accountKind == null && input.startDate == null && input.endDate == null && input.treasuryInstrumentId==null
    }
}

private fun configurationProblem(code: String, message: String, status: HttpStatus): Nothing =
    throw ProductApiException(code, message, status)

/**
 * One audited, idempotent append per genuine company-approved setup record.
 * No accounts, dates, rates, opening balances or approval rights are invented.
 * This records approved source configuration; it does not approve a payment or post a journal.
 */
@Service
class FinanceConfigurationService(
    private val authority: CompanySettingsAuthorityPort,
    private val jdbc: JdbcTemplate,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun page(actor: UUID, org: UUID, kind: FinanceConfigurationKind, offset: Int): FinanceConfigurationPage {
        requireAuthority(actor, org)
        if (offset !in 0..1_000_000) invalid()
        val rows = records(org, kind, null, offset)
        return FinanceConfigurationPage(org, kind.name, offset, rows.size > 50, rows.take(50))
    }

    @Transactional(readOnly=true)
    fun treasuryInstruments(actor: UUID,org: UUID,kind: String): FinanceTreasuryInstrumentPage {
        requireAuthority(actor,org)
        if(kind !in treasuryKinds) invalid()
        val sql=if(kind=="BANK_ASSET") """
            SELECT b.id,b.bank_name || ' · ' || b.masked_account_reference AS name,b.currency
            FROM finance_bank_account_register b WHERE b.organization_id=? AND b.state='APPROVED'
              AND NOT EXISTS(SELECT 1 FROM finance_gl_account a WHERE a.organization_id=b.organization_id
                AND a.treasury_bank_account_id=b.id)
            ORDER BY b.bank_name,b.id LIMIT 101
        """ else """
            SELECT b.id,b.cashbox_code AS name,b.currency FROM finance_cashbox_register b
            WHERE b.organization_id=? AND b.status='ACTIVE'
              AND NOT EXISTS(SELECT 1 FROM finance_gl_account a WHERE a.organization_id=b.organization_id
                AND a.treasury_cashbox_id=b.id)
            ORDER BY b.cashbox_code,b.id LIMIT 101
        """
        val rows=jdbc.query(sql,{r,_->FinanceTreasuryInstrument(r.getObject("id",UUID::class.java),r.getString("name"),
            r.getString("currency").trim())},org)
        return FinanceTreasuryInstrumentPage(org,kind,rows.take(100),rows.size>100)
    }

    @Transactional(readOnly=true)
    fun financingAccounts(actor:UUID,org:UUID,kind:String,currency:String):FinanceFinancingAccountPage {
        requireAuthority(actor,org)
        val expected=financingAccountKind(kind)?:invalid()
        if(!currencyPattern.matches(currency))invalid()
        val rows=jdbc.query("""SELECT id,account_code,account_name,account_kind FROM finance_gl_account
            WHERE organization_id=? AND state='APPROVED' AND account_kind=?
              AND (currency IS NULL OR currency=?)
            ORDER BY account_code,id LIMIT 101""",{r,_->FinanceFinancingAccount(
            r.getObject("id",UUID::class.java),r.getString("account_code"),r.getString("account_name"),r.getString("account_kind"))},
            org,expected,currency)
        return FinanceFinancingAccountPage(org,kind,currency,rows.take(100),rows.size>100)
    }

    fun create(actor: UUID, org: UUID, input: FinanceConfigurationInput, correlation: String): FinanceConfigurationReceipt {
        requireAuthority(actor, org)
        if (!validFinanceConfiguration(input)) invalid()
        val id = UUID.nameUUIDFromBytes("finance-configuration:${input.operationId}".toByteArray(Charsets.UTF_8))
        val fingerprint = IdempotencyKeyContract.fingerprint(buildJsonObject {
            put("organizationId", org.toString()); put("kind", input.kind.name)
            put("authorizationReference", input.authorizationReference)
            put("code", input.code); put("name", input.name); put("accountKind", input.accountKind)
            put("currency", input.currency); put("startDate", input.startDate); put("endDate", input.endDate)
            // Retain the old semantic fingerprint for ordinary account/period/bank requests.
            input.treasuryInstrumentId?.let { put("treasuryInstrumentId",it.toString()) }
            if(input.kind==FinanceConfigurationKind.FINANCING) {
                put("financingKind",input.financingKind);put("financingAccountId",input.financingAccountId.toString())
                put("authorizedAmount",requireNotNull(financingAmount(input.authorizedAmount)).toPlainString())
            }
        }.toString())
        val execution = commands.execute(IdempotentCommandSpec(input.operationId, actor,
            "FINANCE_CONFIGURATION_CREATE", "FINANCE_CONFIGURATION", id, fingerprint, correlation)) {
            if (jdbc.query("""SELECT id FROM organization WHERE id=? AND organization_type='HILTECH'
                AND status='ACTIVE' FOR UPDATE""", { rs, _ -> rs.getObject(1, UUID::class.java) }, org).isEmpty())
                configurationProblem("FINANCE_CONFIGURATION_UNAVAILABLE", "Company is unavailable.", HttpStatus.NOT_FOUND)
            requireAuthority(actor, org)
            val at = clock.instant()
            val now = at.atOffset(ZoneOffset.UTC)
            val inserted = when (input.kind) {
                FinanceConfigurationKind.ACCOUNT -> {
                    if (exists("SELECT EXISTS(SELECT 1 FROM finance_gl_account WHERE organization_id=? AND account_code=?)", org, input.code))
                        conflict("This chart account code already exists.")
                    val bank=input.treasuryInstrumentId?.takeIf { input.accountKind=="BANK_ASSET" }
                    val cash=input.treasuryInstrumentId?.takeIf { input.accountKind=="CASH_ASSET" }
                    if(bank!=null && !exists("""SELECT EXISTS(SELECT 1 FROM finance_bank_account_register b
                        WHERE b.organization_id=? AND b.id=? AND b.currency=? AND b.state='APPROVED'
                        AND NOT EXISTS(SELECT 1 FROM finance_gl_account a WHERE a.organization_id=b.organization_id
                          AND a.treasury_bank_account_id=b.id))""",org,bank,input.currency))
                        conflict("The approved bank account is unavailable or already linked.")
                    if(cash!=null && !exists("""SELECT EXISTS(SELECT 1 FROM finance_cashbox_register b
                        WHERE b.organization_id=? AND b.id=? AND b.currency=? AND b.status='ACTIVE'
                        AND NOT EXISTS(SELECT 1 FROM finance_gl_account a WHERE a.organization_id=b.organization_id
                          AND a.treasury_cashbox_id=b.id))""",org,cash,input.currency))
                        conflict("The approved cashbox is unavailable or already linked.")
                    jdbc.update("""INSERT INTO finance_gl_account
                        (id,organization_id,account_code,account_name,account_kind,currency,state,approved_company_chart_reference,
                         created_at,treasury_bank_account_id,treasury_cashbox_id)
                        VALUES(?,?,?,?,?,?,'APPROVED',?,?,?,?)""",
                        id,org,input.code,input.name,input.accountKind,input.currency,input.authorizationReference,now,bank,cash)
                }
                FinanceConfigurationKind.PERIOD -> {
                    val start = java.sql.Date.valueOf(requireNotNull(date(input.startDate)))
                    val end = java.sql.Date.valueOf(requireNotNull(date(input.endDate)))
                    if (exists("""SELECT EXISTS(SELECT 1 FROM finance_gl_period
                        WHERE organization_id=? AND date_start<=? AND date_end>=?)""", org,end,start))
                        conflict("An accounting period already overlaps these dates.")
                    jdbc.update("""INSERT INTO finance_gl_period
                        (id,organization_id,date_start,date_end,state,approved_company_period_reference,created_at)
                        VALUES(?,?,?,?,'OPEN',?,?)""", id,org,start,end,input.authorizationReference,now)
                }
                FinanceConfigurationKind.FINANCING -> {
                    if(exists("SELECT EXISTS(SELECT 1 FROM finance_financing_source WHERE organization_id=? AND lower(agreement_reference)=lower(?))",org,input.code))
                        conflict("This financing agreement reference already exists.")
                    val expected=requireNotNull(financingAccountKind(input.financingKind))
                    if(!exists("""SELECT EXISTS(SELECT 1 FROM finance_gl_account WHERE organization_id=? AND id=?
                        AND state='APPROVED' AND account_kind=? AND (currency IS NULL OR currency=?))""",
                        org,input.financingAccountId,expected,input.currency))
                        conflict("The approved equity or liability account is unavailable for this financing source.")
                    jdbc.update("""INSERT INTO finance_financing_source(id,operation_id,organization_id,source_kind,
                        agreement_reference,funder_reference,currency,authorized_amount,control_account_id,authorization_reference,
                        registered_by_user_id,registered_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",
                        id,input.operationId,org,input.financingKind,input.code,input.name,input.currency,
                        requireNotNull(financingAmount(input.authorizedAmount)),input.financingAccountId,input.authorizationReference,actor,now)
                }
                FinanceConfigurationKind.BANK -> {
                    if (exists("""SELECT EXISTS(SELECT 1 FROM finance_bank_account_register
                        WHERE organization_id=? AND masked_account_reference=?)""",org,input.code))
                        conflict("This masked bank account reference already exists.")
                    jdbc.update("""INSERT INTO finance_bank_account_register
                        (id,organization_id,bank_name,masked_account_reference,currency,company_approval_reference,state,created_at)
                        VALUES(?,?,?,?,?,?,'APPROVED',?)""",
                        id,org,input.name,input.code,input.currency,input.authorizationReference,now)
                }
            }
            check(inserted == 1)
            audit.append(AuditEventRecord(actorUserId=actor, action="FINANCE_CONFIGURATION_CREATED",
                targetType="FINANCE_CONFIGURATION", targetId=id, previousStateRef=null,
                newStateRef=input.kind.name, safeDiffJson="""{"kind":"${input.kind.name}"}""",
                occurredAt=at, correlationId=correlation, reason="Company-approved financial configuration"))
            IdempotentCommandOutcome("FINANCE_CONFIGURATION_CREATED", """{"recordId":"$id"}""")
        }
        requireAuthority(actor, org)
        val record = records(org, input.kind, id, 0).singleOrNull()
            ?: configurationProblem("FINANCE_CONFIGURATION_RECEIPT_UNAVAILABLE", "Saved source could not be confirmed.", HttpStatus.CONFLICT)
        return FinanceConfigurationReceipt(input.operationId,org,record,execution.replayed)
    }

    private fun exists(sql: String,vararg args: Any?): Boolean =
        jdbc.queryForObject(sql,Boolean::class.java,*args) == true

    private fun records(org: UUID, kind: FinanceConfigurationKind, id: UUID?, offset: Int): List<FinanceConfigurationRecord> {
        val predicate = if (id == null) "" else " AND r.id=?"
        val args = if (id == null) arrayOf<Any>(org,51,offset) else arrayOf<Any>(org,id,51,offset)
        return when (kind) {
            FinanceConfigurationKind.ACCOUNT -> jdbc.query("""SELECT r.* FROM finance_gl_account r
                WHERE r.organization_id=?$predicate ORDER BY r.account_code,r.id LIMIT ? OFFSET ?""", { r,_ ->
                FinanceConfigurationRecord(r.getObject("id",UUID::class.java),kind.name,r.getString("state"),
                    r.getString("approved_company_chart_reference"),r.getString("account_code"),r.getString("account_name"),
                    r.getString("account_kind"),r.getString("currency")?.trim(),treasuryInstrumentId=
                    r.getObject("treasury_bank_account_id",UUID::class.java) ?: r.getObject("treasury_cashbox_id",UUID::class.java))
            }, *args)
            FinanceConfigurationKind.PERIOD -> jdbc.query("""SELECT r.*,
                CASE WHEN EXISTS(SELECT 1 FROM finance_gl_period_close c WHERE c.organization_id=r.organization_id
                    AND c.period_id=r.id) THEN 'CLOSED' ELSE r.state END AS current_state
                FROM finance_gl_period r WHERE r.organization_id=?$predicate
                ORDER BY r.date_start DESC,r.id LIMIT ? OFFSET ?""", { r,_ ->
                FinanceConfigurationRecord(r.getObject("id",UUID::class.java),kind.name,r.getString("current_state"),
                    r.getString("approved_company_period_reference"),startDate=r.getDate("date_start").toString(),
                    endDate=r.getDate("date_end").toString())
            }, *args)
            FinanceConfigurationKind.FINANCING -> jdbc.query("""SELECT r.* FROM finance_financing_source r
                WHERE r.organization_id=?$predicate ORDER BY r.registered_at DESC,r.id LIMIT ? OFFSET ?""",{r,_ ->
                FinanceConfigurationRecord(r.getObject("id",UUID::class.java),kind.name,"APPROVED",
                    r.getString("authorization_reference"),r.getString("agreement_reference"),r.getString("funder_reference"),
                    currency=r.getString("currency").trim(),financingKind=r.getString("source_kind"),
                    financingAccountId=r.getObject("control_account_id",UUID::class.java),
                    authorizedAmount=r.getBigDecimal("authorized_amount").stripTrailingZeros().toPlainString())
            },*args)
            FinanceConfigurationKind.BANK -> jdbc.query("""SELECT r.* FROM finance_bank_account_register r
                WHERE r.organization_id=?$predicate ORDER BY r.bank_name,r.masked_account_reference,r.id LIMIT ? OFFSET ?""", { r,_ ->
                FinanceConfigurationRecord(r.getObject("id",UUID::class.java),kind.name,r.getString("state"),
                    r.getString("company_approval_reference"),r.getString("masked_account_reference"),r.getString("bank_name"),
                    currency=r.getString("currency").trim())
            }, *args)
        }
    }
    private fun requireAuthority(actor: UUID, org: UUID) {
        if (!authority.canManageCompanySettings(actor,org))
            configurationProblem("COMPANY_SETTINGS_DENIED","Current company settings authority is required.",HttpStatus.FORBIDDEN)
    }
    private fun invalid(): Nothing = configurationProblem("FINANCE_CONFIGURATION_INVALID",
        "Enter an approved account, period, masked bank reference or financing agreement with its company authorization.",HttpStatus.BAD_REQUEST)
    private fun conflict(message: String): Nothing = configurationProblem("FINANCE_CONFIGURATION_CONFLICT",message,HttpStatus.CONFLICT)
}

package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.operationalaccess.OperationalAuthorityPort
import com.hiltech.server.operationalaccess.currentOperationalAuthority
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class FinanceDeskPeriod(val id: UUID, val dateStart: String, val dateEnd: String, val state: String)
data class FinanceDeskPeriods(val organizationId: UUID, val records: List<FinanceDeskPeriod>, val hasMore: Boolean)
data class FinanceDeskMovement(
    val accountId: UUID, val accountCode: String, val accountName: String, val accountKind: String,
    val currency: String, val debit: String, val credit: String,
)
data class FinanceDeskCurrencyTotal(val currency: String,val debit: String,val credit: String,val balanced: Boolean)
data class FinanceDeskJournalLine(val number: Int, val accountCode: String, val accountName: String,
    val side: String, val amount: String)
data class FinanceDeskJournal(
    val id: UUID, val kind: String, val postedAt: String, val currency: String, val lines: List<FinanceDeskJournalLine>,
)
data class FinanceDeskLedger(
    val organizationId: UUID, val periodId: UUID, val offset: Int, val hasMore: Boolean,
    val movements: List<FinanceDeskMovement>, val journals: List<FinanceDeskJournal>,
    // This is a movement report, not opening/closing balances, cash or a complete trial balance.
    val includedSources: List<String> = listOf("AP", "AR", "AP_REVERSAL"),
    val isCompanyWideTrialBalance: Boolean = false,
    val currencyTotals: List<FinanceDeskCurrencyTotal>,
)
data class FinanceDeskAccountBalance(
    val accountId:UUID,val accountCode:String,val accountName:String,val accountKind:String,val currency:String,
    val openingDebit:String,val openingCredit:String,val periodDebit:String,val periodCredit:String,
    val closingDebit:String,val closingCredit:String,
)
data class FinanceDeskBalanceTotal(
    val currency:String,val openingDebit:String,val openingCredit:String,val periodDebit:String,val periodCredit:String,
    val closingDebit:String,val closingCredit:String,val balanced:Boolean,
    val recognizedRevenue:String,val recognizedExpense:String,val recognizedResult:String,
)
data class FinanceDeskPosition(
    val currency:String,val assets:String,val liabilities:String,val equity:String,
    val cumulativeRecognizedResult:String,val unclassifiedDebitNet:String,val equationBalances:Boolean,
)
data class FinanceDeskTreasury(
    val currency:String,val accountKind:String,val carriedNet:String,val debits:String,val credits:String,
    val netMovement:String,val closingNet:String,val movementBalances:Boolean,
)
data class FinanceDeskTreasurySource(
    val currency:String,val accountKind:String,val sourceKind:String,val debits:String,val credits:String,
    val category:String="UNCLASSIFIED",
)
data class FinanceDeskCashFlowCategory(
    val category:String,val debit:String,val credit:String,val net:String,
)
data class FinanceDeskCashFlow(
    val currency:String,val carriedNet:String,val closingNet:String,
    val netClassifiedMovement:String,val accountingAdjustmentNet:String,val unclassifiedNet:String,
    val totalMovement:String,val reconcilesToLedger:Boolean,val categories:List<FinanceDeskCashFlowCategory>,
)
data class FinanceDeskBalances(
    val organizationId:UUID,val periodId:UUID,val dateStart:String,val dateEnd:String,
    val includedSources:List<String>,val priorPeriodCount:Int,val firstIncludedDate:String,
    val priorCoverageHasGaps:Boolean,val openingBasis:String="PRIOR_POSTED_MOVEMENTS_ONLY",
    val isCompanyWideTrialBalance:Boolean=false,val isCompleteIncomeStatement:Boolean=false,
    val recordedOpeningCurrencies:List<String> = emptyList(),
    val rows:List<FinanceDeskAccountBalance>,val totals:List<FinanceDeskBalanceTotal>,
    val positions:List<FinanceDeskPosition> = emptyList(),
    val treasury:List<FinanceDeskTreasury> = emptyList(),
    val treasurySources:List<FinanceDeskTreasurySource> = emptyList(),
    val isCompleteBalanceSheet:Boolean=false,val isCompleteCashFlowStatement:Boolean=false,
    val cashFlow:List<FinanceDeskCashFlow> = emptyList(),
)
data class FinanceDeskExposureTotal(
    val currency: String, val knownOutstanding: String, val knownOverdue: String,
    val openWithoutDueDate: Long, val exceptionCount: Long, val recordCount: Long,
)
data class FinanceDeskExposureRow(
    val journalId: UUID, val counterpartyName: String, val currency: String,
    val gross: String, val allocated: String, val outstanding: String?,
    val dueDate: String?, val state: String, val reversed: Boolean,
    val openingDocumentReference:String?=null,val openingDocumentDate:String?=null,
)
data class FinanceDeskExposure(
    val organizationId: UUID, val kind: String, val asOfDate: String, val offset: Int, val hasMore: Boolean,
    val totals: List<FinanceDeskExposureTotal>, val records: List<FinanceDeskExposureRow>,
    val isBankBalance: Boolean = false,
)

private const val PAGE_SIZE = 50
private val assetKinds=setOf("INVENTORY_ASSET","FIXED_ASSET","TAX_RECOVERABLE","OTHER_ASSET",
    "CUSTOMER_RECEIVABLE","BANK_ASSET","CASH_ASSET","EMPLOYEE_ADVANCE_ASSET","EMPLOYEE_IMPREST_ASSET")
private val liabilityKinds=setOf("SUPPLIER_PAYABLE","TAX_PAYABLE","PAYROLL_PAYABLE","PAYROLL_DEDUCTION_PAYABLE","OTHER_LIABILITY")
private val expenseKinds=setOf("EXPENSE","PAYROLL_EXPENSE")
private val resultKinds=expenseKinds+"SALES_REVENUE"
private val classifiedKinds=assetKinds+liabilityKinds+resultKinds+"EQUITY"
private val treasuryKinds=setOf("BANK_ASSET","CASH_ASSET")
private val cashFlowCategories=listOf("OPERATING","INVESTING","UNCLASSIFIED","ACCOUNTING_ADJUSTMENT")

/** Aggregate only recorded cash account lines. An unclassified line or void
 * remains explicit; none is silently assigned to a financing or cash receipt. */
internal fun financeDeskCashFlow(currency:String,treasury:List<FinanceDeskTreasury>,sources:List<FinanceDeskTreasurySource>):FinanceDeskCashFlow {
    fun List<FinanceDeskTreasury>.sum(amount:(FinanceDeskTreasury)->String)=fold(BigDecimal.ZERO){s,r->s+BigDecimal(amount(r))}
    val categories=cashFlowCategories.mapNotNull{category->
        val rows=sources.filter{it.category==category}
        if(rows.isEmpty())null else {
            val debit=rows.fold(BigDecimal.ZERO){sum,row->sum+BigDecimal(row.debits)}
            val credit=rows.fold(BigDecimal.ZERO){sum,row->sum+BigDecimal(row.credits)}
            FinanceDeskCashFlowCategory(category,debit.toPlainString(),credit.toPlainString(),(debit-credit).toPlainString())
        }
    }
    fun net(category:String)=categories.singleOrNull{it.category==category}?.net?.let(::BigDecimal)?:BigDecimal.ZERO
    val classified=net("OPERATING")+net("INVESTING")
    val adjustment=net("ACCOUNTING_ADJUSTMENT")
    val unresolved=net("UNCLASSIFIED")
    val total=classified+adjustment+unresolved
    val opening=treasury.sum{it.carriedNet};val closing=treasury.sum{it.closingNet}
    val sourceDebit=categories.fold(BigDecimal.ZERO){sum,row->sum+BigDecimal(row.debit)}
    val sourceCredit=categories.fold(BigDecimal.ZERO){sum,row->sum+BigDecimal(row.credit)}
    return FinanceDeskCashFlow(currency,opening.toPlainString(),closing.toPlainString(),classified.toPlainString(),
        adjustment.toPlainString(),unresolved.toPlainString(),total.toPlainString(),
        treasury.all{it.movementBalances} && total.compareTo(closing-opening)==0 &&
            sourceDebit.compareTo(treasury.sum{it.debits})==0 && sourceCredit.compareTo(treasury.sum{it.credits})==0,categories)
}

private fun FinanceDeskAccountBalance.closingNet()=BigDecimal(closingDebit)-BigDecimal(closingCredit)
private fun FinanceDeskAccountBalance.carriedNet()=BigDecimal(openingDebit)-BigDecimal(openingCredit)
private fun List<FinanceDeskAccountBalance>.sumMoney(amount:(FinanceDeskAccountBalance)->BigDecimal)=
    fold(BigDecimal.ZERO){sum,row->sum+amount(row)}

internal fun financeDeskPosition(currency:String,accounts:List<FinanceDeskAccountBalance>):FinanceDeskPosition {
    val assets=accounts.filter{it.accountKind in assetKinds}.sumMoney{it.closingNet()}
    val liabilities=accounts.filter{it.accountKind in liabilityKinds}.sumMoney{-it.closingNet()}
    val equity=accounts.filter{it.accountKind=="EQUITY"}.sumMoney{-it.closingNet()}
    // Includes cumulative nominal-account balances; not an invented retained-earnings closing.
    val result=accounts.filter{it.accountKind in resultKinds}.sumMoney{-it.closingNet()}
    val other=accounts.filter{it.accountKind !in classifiedKinds}.sumMoney{it.closingNet()}
    return FinanceDeskPosition(currency,assets.toPlainString(),liabilities.toPlainString(),equity.toPlainString(),
        result.toPlainString(),other.toPlainString(),
        other.signum()==0 && accounts.none{it.accountKind !in classifiedKinds} && assets.compareTo(liabilities+equity+result)==0)
}

internal fun financeDeskTreasury(currency:String,kind:String,accounts:List<FinanceDeskAccountBalance>):FinanceDeskTreasury {
    val opening=accounts.sumMoney{it.carriedNet()}
    val debit=accounts.sumMoney{BigDecimal(it.periodDebit)}
    val credit=accounts.sumMoney{BigDecimal(it.periodCredit)}
    val closing=accounts.sumMoney{it.closingNet()}
    return FinanceDeskTreasury(currency,kind,opening.toPlainString(),debit.toPlainString(),credit.toPlainString(),
        (debit-credit).toPlainString(),closing.toPlainString(),closing.compareTo(opening+debit-credit)==0)
}

/**
 * Read projections over the existing immutable ledger and recognized source balance.
 * No mirror ledger, inferred opening balance, exchange rate, posting or payment.
 * All sums use PostgreSQL numeric and are partitioned by currency. A reversal is
 * included in its target period, never subtracted again from the original period.
 */
internal class FinanceAccountingDeskReadStore(private val jdbc: JdbcTemplate) {
    fun periods(org: UUID): FinanceDeskPeriods {
        val rows = jdbc.query(
            """SELECT p.id,p.date_start,p.date_end,
                CASE WHEN EXISTS(SELECT 1 FROM finance_gl_period_close c
                    WHERE c.organization_id=p.organization_id AND c.period_id=p.id)
                    THEN 'CLOSED' ELSE p.state END AS state
               FROM finance_gl_period p WHERE p.organization_id=?
               ORDER BY p.date_end DESC,p.id LIMIT 101""",
            { r, _ -> FinanceDeskPeriod(r.getObject("id", UUID::class.java),r.getDate("date_start").toString(),
                r.getDate("date_end").toString(),r.getString("state")) }, org)
        return FinanceDeskPeriods(org,rows.take(100),rows.size>100)
    }

    private fun lines(includeSettlements:Boolean,includeEmployee:Boolean,includePayroll:Boolean,cumulative:Boolean=false,includeOpening:Boolean=false) = """
        WITH scope AS (SELECT CAST(? AS uuid) AS org,CAST(? AS uuid) AS period),
        period_scope AS (
            SELECT p.organization_id AS org,p.id AS period,(p.id=s.period) AS current_period
            FROM scope s JOIN finance_gl_period selected ON selected.organization_id=s.org AND selected.id=s.period
            JOIN finance_gl_period p ON p.organization_id=s.org
            WHERE p.id=s.period ${if(cumulative) "OR p.date_end<selected.date_start" else ""}
        ),
        posted_lines AS (
            SELECT 'AP' AS kind,j.id AS journal_id,j.posted_at,j.currency,
                l.line_number,l.gl_account_id,l.side,l.amount,s.current_period
            FROM period_scope s JOIN finance_ap_posted_journal j
                ON j.organization_id=s.org AND j.fiscal_period_id=s.period
            JOIN finance_ap_posted_journal_line l ON l.journal_id=j.id AND l.organization_id=s.org
            UNION ALL
            SELECT 'AR',j.id,j.posted_at,j.currency,l.line_number,l.gl_account_id,l.side,l.amount,s.current_period
            FROM period_scope s JOIN finance_ar_posted_journal j
                ON j.organization_id=s.org AND j.fiscal_period_id=s.period
            JOIN finance_ar_posted_journal_line l ON l.journal_id=j.id AND l.organization_id=s.org
            UNION ALL
            SELECT 'AP_REVERSAL',j.id,j.posted_at,j.currency,l.line_number,l.gl_account_id,l.side,l.amount,s.current_period
            FROM period_scope s JOIN finance_ap_journal_reversal j
                ON j.organization_id=s.org AND j.target_period_id=s.period
            JOIN finance_ap_journal_reversal_line l ON l.reversal_id=j.id AND l.organization_id=s.org
            ${if(includeSettlements) """
            UNION ALL
            SELECT j.source_type,j.id,j.posted_at,j.currency,l.line_number,l.gl_account_id,l.side,l.amount,s.current_period
            FROM period_scope s JOIN finance_settlement_gl_journal j ON j.organization_id=s.org AND j.period_id=s.period
            JOIN finance_settlement_gl_journal_line l ON l.organization_id=s.org AND l.journal_id=j.id
            ${if(includeEmployee) "" else "WHERE j.source_type IN('BANK_ALLOCATION','CASH_VOUCHER','BANK_VOID')"}
            """ else ""}
            ${if(includePayroll) """
            UNION ALL
            SELECT j.entry_type,j.id,j.posted_at,j.currency,l.line_number,l.gl_account_id,l.side,l.amount,s.current_period
            FROM period_scope s JOIN finance_payroll_gl_journal j ON j.organization_id=s.org AND j.period_id=s.period
            JOIN finance_payroll_gl_journal_line l ON l.organization_id=s.org AND l.journal_id=j.id
            """ else ""}
            ${if(includeOpening) """
            UNION ALL
            SELECT 'OPENING_BALANCE',j.id,j.posted_at,j.currency,l.line_number,l.gl_account_id,l.side,l.amount,false
            FROM period_scope s JOIN finance_gl_opening_journal j ON j.organization_id=s.org AND j.period_id=s.period
            JOIN finance_gl_opening_journal_line l ON l.organization_id=s.org AND l.journal_id=j.id
            """ else ""}
        )
    """.trimIndent()

    fun ledger(org: UUID, period: UUID, offset: Int,includeSettlements:Boolean=false,includeEmployee:Boolean=false,includePayroll:Boolean=false,includeOpening:Boolean=false): FinanceDeskLedger {
        val exists = jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM finance_gl_period WHERE organization_id=? AND id=?)",
            Boolean::class.java,org,period)
        if (exists != true) financeDeskUnavailable()
        val movements = jdbc.query(lines(includeSettlements,includeEmployee,includePayroll,includeOpening=includeOpening) + """
            SELECT a.id,a.account_code,a.account_name,a.account_kind,l.currency,
                SUM(CASE WHEN l.side='DEBIT' THEN l.amount ELSE 0 END) AS debit,
                SUM(CASE WHEN l.side='CREDIT' THEN l.amount ELSE 0 END) AS credit
            FROM posted_lines l JOIN finance_gl_account a ON a.id=l.gl_account_id
            JOIN scope s ON a.organization_id=s.org
            GROUP BY a.id,a.account_code,a.account_name,a.account_kind,l.currency
            ORDER BY l.currency,a.account_code,a.id
        """, { r,_ -> FinanceDeskMovement(r.getObject("id",UUID::class.java),
            r.getString("account_code"),r.getString("account_name"),r.getString("account_kind"),
            r.getString("currency").trim(),r.getBigDecimal("debit").toPlainString(),
            r.getBigDecimal("credit").toPlainString()) },org,period)
        val page = jdbc.query(lines(includeSettlements,includeEmployee,includePayroll,includeOpening=includeOpening) + """,
            ranked AS (
                SELECT l.*,DENSE_RANK() OVER(ORDER BY posted_at DESC,kind,journal_id) AS journal_rank
                FROM posted_lines l
            )
            SELECT l.kind,l.journal_id,l.posted_at,l.currency,l.line_number,
                a.account_code,a.account_name,l.side,l.amount
            FROM ranked l JOIN finance_gl_account a ON a.id=l.gl_account_id
            JOIN scope s ON a.organization_id=s.org
            WHERE l.journal_rank>? AND l.journal_rank<=?
            ORDER BY l.journal_rank,l.line_number
        """, { r,_ ->
            FinanceDeskJournal(r.getObject("journal_id",UUID::class.java),r.getString("kind"),
                r.getObject("posted_at",OffsetDateTime::class.java).toInstant().toString(),
                r.getString("currency").trim(),listOf(FinanceDeskJournalLine(r.getInt("line_number"),
                    r.getString("account_code"),r.getString("account_name"),r.getString("side"),
                    r.getBigDecimal("amount").toPlainString())))
        },org,period,offset,offset+PAGE_SIZE+1)
            .groupBy { it.kind to it.id }.values.map { parts ->
                parts.first().copy(lines=parts.flatMap { it.lines })
            }
        val currencyTotals=movements.groupBy { it.currency }.map { (currency, rows) ->
            val debit=rows.fold(BigDecimal.ZERO) { total,row -> total+BigDecimal(row.debit) }
            val credit=rows.fold(BigDecimal.ZERO) { total,row -> total+BigDecimal(row.credit) }
            FinanceDeskCurrencyTotal(currency,debit.toPlainString(),credit.toPlainString(),debit.compareTo(credit)==0)
        }
        return FinanceDeskLedger(org,period,offset,page.size>PAGE_SIZE,movements,page.take(PAGE_SIZE),
            includedSources=listOf("AP","AR","AP_REVERSAL")+
                (if(includeSettlements) listOf("BANK_ALLOCATION","CASH_VOUCHER","BANK_VOID") else emptyList())+
                (if(includeSettlements && includeEmployee) listOf("EMPLOYEE_FUNDING","EMPLOYEE_RETURN","IMPREST_EXPENSE","EXPENSE_REIMBURSEMENT","PAYROLL_RECOVERY","PAYROLL_OPENING_PAYMENT") else emptyList())+
                (if(includePayroll) listOf("PAYROLL_ACCRUAL","PAYROLL_PAYMENT","PAYROLL_RETURN","PAYROLL_VOID") else emptyList())+
                (if(includeOpening) listOf("OPENING_BALANCE") else emptyList()),
            currencyTotals=currencyTotals)
    }

    /**
     * Net carried movements are not a verified migration opening. Never infer
     * a missing opening, combine currencies, or label a partial source set a
     * company-wide trial balance. Current-period P&L is a posted-source view.
     */
    fun balances(org:UUID,period:UUID,includeSettlements:Boolean,includeEmployee:Boolean,includePayroll:Boolean):FinanceDeskBalances {
        val coverage=jdbc.query("""
            WITH selected AS (SELECT id,date_start,date_end FROM finance_gl_period WHERE organization_id=? AND id=?),
            earlier AS (
                SELECT p.date_start,p.date_end,lag(p.date_end) OVER(ORDER BY p.date_start,p.date_end,p.id) AS previous_end
                FROM finance_gl_period p CROSS JOIN selected s
                WHERE p.organization_id=? AND p.date_end<s.date_start
            )
            SELECT s.date_start,s.date_end,
                (SELECT count(*) FROM earlier)::int AS prior_count,
                coalesce((SELECT min(date_start) FROM earlier),s.date_start) AS first_date,
                (EXISTS(SELECT 1 FROM earlier WHERE previous_end IS NOT NULL AND date_start<>previous_end+1)
                    OR coalesce((SELECT max(date_end)+1<>s.date_start FROM earlier),false)
                    OR EXISTS(SELECT 1 FROM finance_gl_period p WHERE p.organization_id=? AND p.id<>s.id
                        AND p.date_start<=s.date_end AND p.date_end>=s.date_start)) AS gaps
            FROM selected s
        """.trimIndent(),{r,_->Coverage(r.getDate("date_start").toString(),r.getDate("date_end").toString(),
            r.getInt("prior_count"),r.getDate("first_date").toString(),r.getBoolean("gaps"))},org,period,org,org).singleOrNull()
            ?:financeDeskUnavailable()
        val rows=jdbc.query(lines(includeSettlements,includeEmployee,includePayroll,cumulative=true,includeOpening=true)+"""
            , account_sums AS (
                SELECT gl_account_id,currency,
                    sum(CASE WHEN NOT current_period AND side='DEBIT' THEN amount
                             WHEN NOT current_period AND side='CREDIT' THEN -amount ELSE 0 END) AS opening,
                    sum(CASE WHEN current_period AND side='DEBIT' THEN amount ELSE 0 END) AS period_debit,
                    sum(CASE WHEN current_period AND side='CREDIT' THEN amount ELSE 0 END) AS period_credit
                FROM posted_lines GROUP BY gl_account_id,currency
            )
            SELECT a.id,a.account_code,a.account_name,a.account_kind,m.currency,
                greatest(m.opening,0) AS opening_debit,greatest(-m.opening,0) AS opening_credit,
                m.period_debit,m.period_credit,
                greatest(m.opening+m.period_debit-m.period_credit,0) AS closing_debit,
                greatest(-m.opening-m.period_debit+m.period_credit,0) AS closing_credit
            FROM account_sums m JOIN scope s ON true
            JOIN finance_gl_account a ON a.organization_id=s.org AND a.id=m.gl_account_id
            ORDER BY m.currency,a.account_code,a.id
        """,{r,_->FinanceDeskAccountBalance(r.getObject("id",UUID::class.java),r.getString("account_code"),
            r.getString("account_name"),r.getString("account_kind"),r.getString("currency").trim(),
            r.getBigDecimal("opening_debit").toPlainString(),r.getBigDecimal("opening_credit").toPlainString(),
            r.getBigDecimal("period_debit").toPlainString(),r.getBigDecimal("period_credit").toPlainString(),
            r.getBigDecimal("closing_debit").toPlainString(),r.getBigDecimal("closing_credit").toPlainString())},org,period)
        val totals=rows.groupBy{it.currency}.map{(currency,accounts)->
            fun sum(amount:(FinanceDeskAccountBalance)->String)=accounts.fold(BigDecimal.ZERO){total,a->total+BigDecimal(amount(a))}
            val od=sum{it.openingDebit};val oc=sum{it.openingCredit}
            val pd=sum{it.periodDebit};val pc=sum{it.periodCredit}
            val cd=sum{it.closingDebit};val cc=sum{it.closingCredit}
            val revenue=accounts.filter{it.accountKind=="SALES_REVENUE"}.fold(BigDecimal.ZERO){total,a->
                total+BigDecimal(a.periodCredit)-BigDecimal(a.periodDebit)}
            val expense=accounts.filter{it.accountKind in setOf("EXPENSE","PAYROLL_EXPENSE")}.fold(BigDecimal.ZERO){total,a->
                total+BigDecimal(a.periodDebit)-BigDecimal(a.periodCredit)}
            FinanceDeskBalanceTotal(currency,od.toPlainString(),oc.toPlainString(),pd.toPlainString(),pc.toPlainString(),
                cd.toPlainString(),cc.toPlainString(),od.compareTo(oc)==0&&pd.compareTo(pc)==0&&cd.compareTo(cc)==0,
                revenue.toPlainString(),expense.toPlainString(),(revenue-expense).toPlainString())
        }
        val positions=rows.groupBy{it.currency}.map{(currency,accounts)->financeDeskPosition(currency,accounts)}
        val treasury=rows.filter{it.accountKind in treasuryKinds}.groupBy{it.currency to it.accountKind}.map{(key,accounts)->
            financeDeskTreasury(key.first,key.second,accounts)
        }
        // Only posted treasury lines from the selected period; opening journals
        // and non-cash accruals are excluded. Voids remain visible as accounting
        // adjustments, not implied physical receipts or payments.
        val treasurySources=jdbc.query(lines(includeSettlements,includeEmployee,includePayroll)+"""
            , categorized AS (
                SELECT l.currency,a.account_kind,l.kind,l.side,l.amount,
                  CASE
                    WHEN l.kind IN('BANK_VOID','PAYROLL_VOID') THEN 'ACCOUNTING_ADJUSTMENT'
                    WHEN l.kind IN('PAYROLL_PAYMENT','PAYROLL_RETURN','PAYROLL_RECOVERY','PAYROLL_OPENING_PAYMENT') THEN 'OPERATING'
                    WHEN l.kind IN('BANK_ALLOCATION','CASH_VOUCHER') AND settlement.source_kind='AR' THEN 'OPERATING'
                    WHEN l.kind IN('BANK_ALLOCATION','CASH_VOUCHER') AND settlement.source_kind='AP' THEN
                      COALESCE(ap.category,'UNCLASSIFIED')
                    WHEN l.kind='EXPENSE_REIMBURSEMENT' AND cost.account_kind IN('EXPENSE','INVENTORY_ASSET') THEN 'OPERATING'
                    WHEN l.kind='EXPENSE_REIMBURSEMENT' AND cost.account_kind='FIXED_ASSET' THEN 'INVESTING'
                    ELSE 'UNCLASSIFIED'
                  END AS category
                FROM posted_lines l JOIN scope s ON true
                JOIN finance_gl_account a ON a.organization_id=s.org AND a.id=l.gl_account_id
                LEFT JOIN finance_settlement_gl_journal settlement ON settlement.organization_id=s.org
                    AND settlement.id=l.journal_id AND settlement.source_type=l.kind
                LEFT JOIN finance_gl_account cost ON cost.organization_id=s.org AND cost.id=settlement.debit_account_id
                LEFT JOIN LATERAL (
                    SELECT CASE
                      WHEN count(*)>0 AND bool_and(original_account.account_kind IN('EXPENSE','INVENTORY_ASSET'))
                        THEN 'OPERATING'
                      WHEN count(*)>0 AND bool_and(original_account.account_kind='FIXED_ASSET')
                        THEN 'INVESTING'
                      ELSE 'UNCLASSIFIED' END AS category
                    FROM finance_ap_posted_journal_line original
                    JOIN finance_gl_account original_account ON original_account.organization_id=original.organization_id
                        AND original_account.id=original.gl_account_id
                    WHERE original.organization_id=s.org AND original.journal_id=settlement.source_journal_id AND original.side='DEBIT'
                ) ap ON settlement.source_kind='AP' AND l.kind IN('BANK_ALLOCATION','CASH_VOUCHER')
                WHERE a.account_kind IN('BANK_ASSET','CASH_ASSET')
            )
            SELECT currency,account_kind,kind,category,
                sum(CASE WHEN side='DEBIT' THEN amount ELSE 0 END) AS debit,
                sum(CASE WHEN side='CREDIT' THEN amount ELSE 0 END) AS credit
            FROM categorized GROUP BY currency,account_kind,kind,category
            ORDER BY currency,account_kind,kind,category
        """,{r,_->FinanceDeskTreasurySource(r.getString("currency").trim(),r.getString("account_kind"),
            r.getString("kind"),r.getBigDecimal("debit").toPlainString(),r.getBigDecimal("credit").toPlainString(),
            r.getString("category"))},org,period)
        val cashFlow=treasury.groupBy{it.currency}.map{(currency,accounts)->
            financeDeskCashFlow(currency,accounts,treasurySources.filter{it.currency==currency})
        }
        val recordedOpenings=jdbc.query("""SELECT j.currency FROM finance_gl_opening_journal j
            JOIN finance_gl_period p ON p.organization_id=j.organization_id AND p.id=j.period_id
            WHERE j.organization_id=? AND p.date_start<=? ORDER BY j.currency""",
            {r,_->r.getString(1).trim()},org,java.sql.Date.valueOf(coverage.start))
        return FinanceDeskBalances(org,period,coverage.start,coverage.end,
            listOf("AP","AR","AP_REVERSAL")+
                (if(includeSettlements)listOf("BANK_ALLOCATION","CASH_VOUCHER","BANK_VOID")else emptyList())+
                (if(includeSettlements&&includeEmployee)listOf("EMPLOYEE_FUNDING","EMPLOYEE_RETURN","IMPREST_EXPENSE","EXPENSE_REIMBURSEMENT","PAYROLL_RECOVERY","PAYROLL_OPENING_PAYMENT")else emptyList())+
                (if(includePayroll)listOf("PAYROLL_ACCRUAL","PAYROLL_PAYMENT","PAYROLL_RETURN","PAYROLL_VOID")else emptyList())+listOf("OPENING_BALANCE"),
            coverage.count,coverage.first,coverage.gaps,openingBasis=if(recordedOpenings.isEmpty())"PRIOR_POSTED_MOVEMENTS_ONLY" else "RECORDED_OPENING_PLUS_PRIOR_MOVEMENTS",
            recordedOpeningCurrencies=recordedOpenings,rows=rows,totals=totals,
            positions=positions,treasury=treasury,treasurySources=treasurySources,cashFlow=cashFlow)
    }
    private data class Coverage(val start:String,val end:String,val count:Int,val first:String,val gaps:Boolean)

    fun exposure(org: UUID, kind: String, day: LocalDate, offset: Int): FinanceDeskExposure {
        require(kind in setOf("AP","AR"))
        val totals = jdbc.query("""
            SELECT currency,COUNT(*) AS record_count,
                COALESCE(SUM(outstanding_amount) FILTER (WHERE NOT reversed AND outstanding_amount>=0),0) AS known_outstanding,
                COALESCE(SUM(outstanding_amount) FILTER (WHERE NOT reversed AND outstanding_amount>0
                    AND contractual_due_date<?),0) AS known_overdue,
                COUNT(*) FILTER(WHERE NOT reversed AND outstanding_amount>0
                    AND contractual_due_date IS NULL) AS missing_due,
                COUNT(*) FILTER(WHERE (reversed AND allocated_amount>0)
                    OR (NOT reversed AND (outstanding_amount IS NULL OR outstanding_amount<0))) AS exceptions
            FROM finance_ap_ar_source_balance
            WHERE organization_id=? AND source_kind=?
            GROUP BY currency ORDER BY currency
        """.trimIndent(), { r,_ -> FinanceDeskExposureTotal(r.getString("currency").trim(),
            r.getBigDecimal("known_outstanding").toPlainString(),r.getBigDecimal("known_overdue").toPlainString(),
            r.getLong("missing_due"),r.getLong("exceptions"),r.getLong("record_count")) },java.sql.Date.valueOf(day),org,kind)
        val rows = jdbc.query("""
            SELECT b.journal_id,o.display_name,b.currency,b.gross_amount,b.allocated_amount,
                b.outstanding_amount,b.contractual_due_date,b.accounting_state,b.reversed,
                legacy.document_reference,legacy.document_date
            FROM finance_ap_ar_source_balance b
            JOIN organization o ON o.id=b.counterparty_id
            LEFT JOIN finance_gl_opening_obligation legacy ON legacy.organization_id=b.organization_id
                AND legacy.source_kind=b.source_kind AND legacy.id=b.journal_id
            WHERE b.organization_id=? AND b.source_kind=?
            ORDER BY b.recognized_at DESC,b.journal_id LIMIT ? OFFSET ?
        """.trimIndent(), { r,_ -> FinanceDeskExposureRow(r.getObject("journal_id",UUID::class.java),
            r.getString("display_name"),r.getString("currency").trim(),r.getBigDecimal("gross_amount").toPlainString(),
            r.getBigDecimal("allocated_amount").toPlainString(),r.getBigDecimal("outstanding_amount")?.toPlainString(),
            r.getDate("contractual_due_date")?.toString(),r.getString("accounting_state"),r.getBoolean("reversed"),
            r.getString("document_reference"),r.getDate("document_date")?.toString())
        },org,kind,PAGE_SIZE+1,offset)
        return FinanceDeskExposure(org,kind,day.toString(),offset,rows.size>PAGE_SIZE,totals,rows.take(PAGE_SIZE))
    }
}

private fun financeDeskUnavailable(): Nothing = throw ProductApiException(
    "FINANCE_ACCOUNTING_DESK_UNAVAILABLE","Current company financial reporting authority and source required.",HttpStatus.NOT_FOUND)

@Service
class FinanceAccountingDeskService @org.springframework.beans.factory.annotation.Autowired constructor(
    jdbc: JdbcTemplate,
    private val approvals: ApprovalPersistencePort,
    private val environment: Environment,
    private val clock: Clock,
    private val operationalAuthority: OperationalAuthorityPort? = null,
    private val payrollAuthority: FinancePayrollAuthorityPort? = null,
) {
    private val store = FinanceAccountingDeskReadStore(jdbc)

    private fun authorize(actor: UUID, org: UUID, ledger: Boolean) {
        val now=clock.instant()
        val policies = buildList {
            add("hiltech.finance.ap-ar-balances.enabled" to "hiltech.finance.ap-ar-balances.reader-authority-key")
            if (ledger) add("hiltech.finance.ap-reporting.enabled" to "hiltech.finance.ap-reporting.viewer-authority-key")
        }
        for ((enabled,key) in policies) {
            if (!environment.getProperty(enabled,Boolean::class.java,false)) financeDeskUnavailable()
            currentOperationalAuthority(operationalAuthority,approvals,actor,org,key,
                environment.getProperty(key),now) ?: financeDeskUnavailable()
        }
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    fun periods(actor: UUID,org: UUID): FinanceDeskPeriods {
        authorize(actor,org,true)
        return store.periods(org)
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    fun ledger(actor: UUID,org: UUID,period: UUID,offset: Int,includeSettlements:Boolean=false,includeEmployee:Boolean=false,includePayroll:Boolean=false,includeOpening:Boolean=false): FinanceDeskLedger {
        if (offset !in 0..1_000_000) financeDeskUnavailable()
        authorize(actor,org,true)
        val payrollAllowed=includePayroll && (payrollAuthority?.canPrepare(actor,org)==true || payrollAuthority?.canReview(actor,org)==true)
        return store.ledger(org,period,offset,includeSettlements,includeEmployee,payrollAllowed,includeOpening)
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    fun balances(actor:UUID,org:UUID,period:UUID,includePayroll:Boolean):FinanceDeskBalances {
        authorize(actor,org,true)
        val payrollAllowed=includePayroll && (payrollAuthority?.canPrepare(actor,org)==true || payrollAuthority?.canReview(actor,org)==true)
        return store.balances(org,period,true,true,payrollAllowed)
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    fun exposure(actor: UUID,org: UUID,kind: String,offset: Int): FinanceDeskExposure {
        if (offset !in 0..1_000_000 || kind !in setOf("AP","AR")) financeDeskUnavailable()
        authorize(actor,org,false)
        return store.exposure(org,kind,LocalDate.ofInstant(clock.instant(),ZoneOffset.UTC),offset)
    }
}

@RestController
class FinanceAccountingDeskController(private val desk: FinanceAccountingDeskService) {
    @GetMapping("/v1/finance/organizations/{orgId}/accounting-desk/periods")
    fun periods(http: HttpServletRequest,@PathVariable orgId: UUID) = desk.periods(actor(http),orgId)

    @GetMapping("/v1/finance/organizations/{orgId}/accounting-desk/ledger")
    fun ledger(http: HttpServletRequest,@PathVariable orgId: UUID,@RequestParam periodId: UUID,
        @RequestParam(defaultValue="0") offset: Int,@RequestParam(defaultValue="false") includeSettlements:Boolean,
        @RequestParam(defaultValue="false") includeEmployee:Boolean,@RequestParam(defaultValue="false") includePayroll:Boolean,
        @RequestParam(defaultValue="false") includeOpening:Boolean) =
        desk.ledger(actor(http),orgId,periodId,offset,includeSettlements,includeEmployee,includePayroll,includeOpening)

    @GetMapping("/v1/finance/organizations/{orgId}/accounting-desk/balances")
    fun balances(http:HttpServletRequest,@PathVariable orgId:UUID,@RequestParam periodId:UUID,
        @RequestParam(defaultValue="false") includePayroll:Boolean) =
        org.springframework.http.ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
            .body(desk.balances(actor(http),orgId,periodId,includePayroll))

    @GetMapping("/v1/finance/organizations/{orgId}/accounting-desk/exposure/{kind}")
    fun exposure(http: HttpServletRequest,@PathVariable orgId: UUID,@PathVariable kind: String,
        @RequestParam(defaultValue="0") offset: Int) = desk.exposure(actor(http),orgId,kind,offset)

    private fun actor(http: HttpServletRequest): UUID = HiltechRequestContext.current(http).identityId
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw ProductApiException("UNAUTHENTICATED","Finance identity required.",HttpStatus.UNAUTHORIZED)
}

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
data class FinanceDeskExposureTotal(
    val currency: String, val knownOutstanding: String, val knownOverdue: String,
    val openWithoutDueDate: Long, val exceptionCount: Long, val recordCount: Long,
)
data class FinanceDeskExposureRow(
    val journalId: UUID, val counterpartyName: String, val currency: String,
    val gross: String, val allocated: String, val outstanding: String?,
    val dueDate: String?, val state: String, val reversed: Boolean,
)
data class FinanceDeskExposure(
    val organizationId: UUID, val kind: String, val asOfDate: String, val offset: Int, val hasMore: Boolean,
    val totals: List<FinanceDeskExposureTotal>, val records: List<FinanceDeskExposureRow>,
    val isBankBalance: Boolean = false,
)

private const val PAGE_SIZE = 50

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

    private val lines = """
        WITH scope AS (SELECT CAST(? AS uuid) AS org,CAST(? AS uuid) AS period),
        posted_lines AS (
            SELECT 'AP' AS kind,j.id AS journal_id,j.posted_at,j.currency,
                l.line_number,l.gl_account_id,l.side,l.amount
            FROM scope s JOIN finance_ap_posted_journal j
                ON j.organization_id=s.org AND j.fiscal_period_id=s.period
            JOIN finance_ap_posted_journal_line l ON l.journal_id=j.id AND l.organization_id=s.org
            UNION ALL
            SELECT 'AR',j.id,j.posted_at,j.currency,l.line_number,l.gl_account_id,l.side,l.amount
            FROM scope s JOIN finance_ar_posted_journal j
                ON j.organization_id=s.org AND j.fiscal_period_id=s.period
            JOIN finance_ar_posted_journal_line l ON l.journal_id=j.id AND l.organization_id=s.org
            UNION ALL
            SELECT 'AP_REVERSAL',j.id,j.posted_at,j.currency,l.line_number,l.gl_account_id,l.side,l.amount
            FROM scope s JOIN finance_ap_journal_reversal j
                ON j.organization_id=s.org AND j.target_period_id=s.period
            JOIN finance_ap_journal_reversal_line l ON l.reversal_id=j.id AND l.organization_id=s.org
        )
    """.trimIndent()

    fun ledger(org: UUID, period: UUID, offset: Int): FinanceDeskLedger {
        val exists = jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM finance_gl_period WHERE organization_id=? AND id=?)",
            Boolean::class.java,org,period)
        if (exists != true) financeDeskUnavailable()
        val movements = jdbc.query(lines + """
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
        val page = jdbc.query(lines + """,
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
            currencyTotals=currencyTotals)
    }

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
                b.outstanding_amount,b.contractual_due_date,b.accounting_state,b.reversed
            FROM finance_ap_ar_source_balance b
            JOIN organization o ON o.id=b.counterparty_id
            WHERE b.organization_id=? AND b.source_kind=?
            ORDER BY b.recognized_at DESC,b.journal_id LIMIT ? OFFSET ?
        """.trimIndent(), { r,_ -> FinanceDeskExposureRow(r.getObject("journal_id",UUID::class.java),
            r.getString("display_name"),r.getString("currency").trim(),r.getBigDecimal("gross_amount").toPlainString(),
            r.getBigDecimal("allocated_amount").toPlainString(),r.getBigDecimal("outstanding_amount")?.toPlainString(),
            r.getDate("contractual_due_date")?.toString(),r.getString("accounting_state"),r.getBoolean("reversed"))
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
    fun ledger(actor: UUID,org: UUID,period: UUID,offset: Int): FinanceDeskLedger {
        if (offset !in 0..1_000_000) financeDeskUnavailable()
        authorize(actor,org,true)
        return store.ledger(org,period,offset)
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
        @RequestParam(defaultValue="0") offset: Int) = desk.ledger(actor(http),orgId,periodId,offset)

    @GetMapping("/v1/finance/organizations/{orgId}/accounting-desk/exposure/{kind}")
    fun exposure(http: HttpServletRequest,@PathVariable orgId: UUID,@PathVariable kind: String,
        @RequestParam(defaultValue="0") offset: Int) = desk.exposure(actor(http),orgId,kind,offset)

    private fun actor(http: HttpServletRequest): UUID = HiltechRequestContext.current(http).identityId
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw ProductApiException("UNAUTHENTICATED","Finance identity required.",HttpStatus.UNAUTHORIZED)
}

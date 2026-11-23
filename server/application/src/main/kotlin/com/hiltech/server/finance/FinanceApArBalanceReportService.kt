package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

data class FinanceApArBalanceRow(
    val sourceKind:String,val sourceJournalId:UUID,
    val counterpartyOrganizationId:UUID,val currency:String,
    val grossAmount:String,val allocatedAmount:String,
    val outstandingAmount:String?,
    val recognizedAt:String,val contractualDueDate:LocalDate?,
    val reversed:Boolean,val accountingState:String,
    val bankReconciled:Boolean=false,
)
data class FinanceApArBalanceReport(
    val organizationId:UUID,val sourceKind:String,
    val records:List<FinanceApArBalanceRow>,
    val truncated:Boolean,
    val totalsAreBankBalances:Boolean=false,
    val allocationDoesNotProveBankReconciliation:Boolean=true,
)

/**
 * Read-only, org-authorized, no currency aggregation. Reversal + cash is
 * an exception, NOT a negative liability or evidence of refunded funds.
 */
@Service
class FinanceApArBalanceReportService(
    private val jdbc:JdbcTemplate,
    private val approvals:ApprovalPersistencePort,
    private val environment:Environment,
    private val clock:Clock,
) {
    @Transactional(readOnly=true)
    fun read(
        actor:UUID,org:UUID,kind:String,limit:Int=100,
    ):FinanceApArBalanceReport {
        require(kind in setOf("AP","AR")){"Source kind must be AP or AR."}
        if(limit !in 1..500)unavailable()
        val enabled=environment.getProperty(
            "hiltech.finance.ap-ar-balances.enabled",
            Boolean::class.java,false,
        )
        val key=environment.getProperty(
            "hiltech.finance.ap-ar-balances.reader-authority-key","",
        ).trim()
        if(!enabled || !Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(key))
            unavailable()
        val now=clock.instant()
        if(!approvals.isActiveOrganizationMember(org,actor,now))unavailable()
        val reviewer=approvals.resolveCurrentAuthority(org,key,now)
            ?: unavailable()
        if(reviewer.organizationId!=org ||
            !approvals.actorMatchesAuthority(actor,reviewer,now))
            unavailable()
        val rows=jdbc.query(
            """
            SELECT source_kind,journal_id,counterparty_id,currency,
               recognized_at,contractual_due_date,gross_amount,
               allocated_amount,outstanding_amount,reversed,accounting_state
            FROM finance_ap_ar_source_balance
            WHERE organization_id=? AND source_kind=?
            ORDER BY recognized_at DESC,journal_id
            LIMIT ?
            """.trimIndent(),
            {rs,_ ->FinanceApArBalanceRow(
                sourceKind=rs.getString("source_kind"),
                sourceJournalId=rs.getObject("journal_id",UUID::class.java),
                counterpartyOrganizationId=rs.getObject(
                    "counterparty_id",UUID::class.java),
                currency=rs.getString("currency"),
                grossAmount=rs.getBigDecimal("gross_amount").toPlainString(),
                allocatedAmount=rs.getBigDecimal("allocated_amount").toPlainString(),
                outstandingAmount=rs.getBigDecimal("outstanding_amount")
                    ?.toPlainString(),
                recognizedAt=rs.getObject("recognized_at").toString(),
                contractualDueDate=rs.getDate("contractual_due_date")
                    ?.toLocalDate(),
                reversed=rs.getBoolean("reversed"),
                accountingState=rs.getString("accounting_state"),
            )},org,kind,limit+1,
        )
        return FinanceApArBalanceReport(
            organizationId=org,sourceKind=kind,
            records=rows.take(limit),truncated=rows.size>limit,
        )
    }
    private fun unavailable():Nothing=throw ProductApiException(
        "FINANCE_AP_AR_REPORT_UNAVAILABLE",
        "Company finance reporting authority is required.",
        HttpStatus.NOT_FOUND,
    )
}

@RestController
class FinanceApArBalanceReportController(
    private val report:FinanceApArBalanceReportService,
) {
    @GetMapping("/v1/finance/organizations/{orgId}/account-balances/{kind}")
    fun read(
        http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable kind:String,
        @RequestParam(required=false,defaultValue="100") limit:Int,
    ):FinanceApArBalanceReport =
        report.read(actor(http),orgId,kind,limit)
    private fun actor(http:HttpServletRequest):UUID =
        HiltechRequestContext.current(http).identityId
            ?.let{runCatching{UUID.fromString(it)}.getOrNull()}
            ?:throw ProductApiException(
                "UNAUTHENTICATED","Finance identity required.",
                HttpStatus.UNAUTHORIZED,
            )
}

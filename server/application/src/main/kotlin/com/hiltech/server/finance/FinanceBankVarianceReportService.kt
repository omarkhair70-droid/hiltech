package com.hiltech.server.finance

import com.hiltech.server.operationalaccess.OperationalAuthorityPort
import com.hiltech.server.operationalaccess.currentOperationalAuthority

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

data class FinanceBankVarianceRow(
    val bankLineId:UUID,val accountId:UUID,
    val externalTransactionId:String,val bankValueDate:LocalDate,
    val cashFlow:FinanceBankFlow,val currency:String,
    val statementAmount:String,val allocatedAmount:String,
    val unmatchedAmount:String,val exceptionState:String,
    val reconciled:Boolean=false,
)
data class FinanceBankVarianceReport(
    val organizationId:UUID,val bankIntakeId:UUID,
    val statementLineCount:Int,val items:List<FinanceBankVarianceRow>,
    val hasMore:Boolean,val statementReconciled:Boolean=false,
)

/** Exact bank line allocations, not a signed bank reconciliation event. */
@Service
class FinanceBankVarianceReportService @org.springframework.beans.factory.annotation.Autowired constructor(
    private val jdbc:JdbcTemplate,
    private val approvals:ApprovalPersistencePort,
    private val environment:Environment,
    private val clock:Clock,
    private val operationalAuthority: OperationalAuthorityPort? = null,
) {
    @Transactional(readOnly=true)
    fun read(
        actor:UUID,org:UUID,intake:UUID,limit:Int=100,
    ):FinanceBankVarianceReport {
        if(!environment.getProperty(
            "hiltech.finance.bank-reconciliation.report-enabled",
            Boolean::class.java,false,
        ) || limit !in 1..500)unavailable()
        val property = "hiltech.finance.bank-reconciliation.reader-authority-key"
        currentOperationalAuthority(operationalAuthority, approvals, actor, org, property,
            environment.getProperty(property), clock.instant()) ?: unavailable()
        val exists=jdbc.queryForObject(
            """SELECT EXISTS(SELECT 1 FROM finance_bank_statement_intake
               WHERE id=? AND organization_id=?)""",
            Boolean::class.java,intake,org,
        )==true
        if(!exists)unavailable()
        val rows=jdbc.query(
            """
            SELECT bank_line_id,account_id,external_transaction_id,booked_on,
                   flow,currency,statement_amount,allocated_amount,
                   unallocated_amount,exception_state
            FROM finance_bank_allocation_exception
            WHERE organization_id=? AND intake_id=?
            ORDER BY booked_on,external_transaction_id,bank_line_id
            LIMIT ?
            """.trimIndent(),
            {rs,_ ->FinanceBankVarianceRow(
                rs.getObject("bank_line_id",UUID::class.java),
                rs.getObject("account_id",UUID::class.java),
                rs.getString("external_transaction_id"),
                rs.getDate("booked_on").toLocalDate(),
                FinanceBankFlow.valueOf(rs.getString("flow")),
                rs.getString("currency"),
                rs.getBigDecimal("statement_amount").toPlainString(),
                rs.getBigDecimal("allocated_amount").toPlainString(),
                rs.getBigDecimal("unallocated_amount").toPlainString(),
                rs.getString("exception_state"),
            )},org,intake,limit+1,
        )
        val total=jdbc.queryForObject(
            """SELECT count(*) FROM finance_bank_statement_intake_line
               WHERE organization_id=? AND intake_id=?""",
            Int::class.java,org,intake,
        ) ?: 0
        return FinanceBankVarianceReport(
            org,intake,total,rows.take(limit),rows.size>limit,
        )
    }
    private fun unavailable():Nothing=throw ProductApiException(
        "FINANCE_BANK_VARIANCE_UNAVAILABLE",
        "Independent company finance reporting permission required.",
        HttpStatus.NOT_FOUND,
    )
}
@RestController
class FinanceBankVarianceReportController(
    private val report:FinanceBankVarianceReportService,
) {
    @GetMapping("/v1/finance/organizations/{orgId}/bank-statement-intakes/{intakeId}/allocation-variances")
    fun read(
        http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable intakeId:UUID,
        @RequestParam(defaultValue="100") limit:Int,
    ):FinanceBankVarianceReport =
        report.read(actor(http),orgId,intakeId,limit)
    private fun actor(http:HttpServletRequest):UUID =
        HiltechRequestContext.current(http).identityId
            ?.let{runCatching{UUID.fromString(it)}.getOrNull()}
            ?:throw ProductApiException(
                "UNAUTHENTICATED","Finance identity required.",
                HttpStatus.UNAUTHORIZED)
}

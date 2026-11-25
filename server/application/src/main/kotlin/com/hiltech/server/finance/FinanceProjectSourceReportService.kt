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
import java.util.UUID

data class FinanceProjectSourceEvent(
    val sourceKind:String,
    val sourceId:UUID,
    val currency:String,
    val originalAmount:String,
    val sourceRecordedAt:String,
    val glRecognized:Boolean,
    val observedBankAllocation:Boolean,
    val reversed:Boolean,
    val status:String,
    val bankReconciled:Boolean=false,
)
data class FinanceProjectSourceReport(
    val organizationId:UUID,
    val projectId:UUID,
    val records:List<FinanceProjectSourceEvent>,
    val hasMore:Boolean,
    val isAProfitAndLossStatement:Boolean=false,
    val isBankReconciliation:Boolean=false,
    val mixesDifferentCurrencies:Boolean=false,
)

/** Source facts only, no guessed procurement/warehouse costs or gross margin. */
@Service
class FinanceProjectSourceReportService @org.springframework.beans.factory.annotation.Autowired constructor(
    private val jdbc:JdbcTemplate,
    private val approvals:ApprovalPersistencePort,
    private val environment:Environment,
    private val clock:Clock,
    private val operationalAuthority: OperationalAuthorityPort? = null,
) {
    @Transactional(readOnly=true)
    fun read(
        actor:UUID,org:UUID,project:UUID,limit:Int=100,
    ):FinanceProjectSourceReport {
        if(!environment.getProperty(
            "hiltech.finance.project-sources.enabled",
            Boolean::class.java,false,
        ) || limit !in 1..500)
            denied()
        val property = "hiltech.finance.project-sources.reader-authority-key"
        currentOperationalAuthority(operationalAuthority, approvals, actor, org, property,
            environment.getProperty(property), clock.instant()) ?: denied()
        if(jdbc.queryForObject(
            """SELECT EXISTS(SELECT 1 FROM project
               WHERE id=? AND organization_id=?)""",
            Boolean::class.java,project,org,
        )!=true)denied()
        val data=jdbc.query(
            """
            SELECT source_kind,source_event_id,currency,original_amount,
               event_at,gl_recognized,confirmed_bank_allocation,reversed,
               source_status
            FROM finance_project_source_event
            WHERE organization_id=? AND project_id=?
            ORDER BY event_at DESC,source_event_id DESC
            LIMIT ?
            """.trimIndent(),
            {rs,_ ->FinanceProjectSourceEvent(
                sourceKind=rs.getString("source_kind"),
                sourceId=rs.getObject("source_event_id",UUID::class.java),
                currency=rs.getString("currency"),
                originalAmount=rs.getBigDecimal("original_amount").toPlainString(),
                sourceRecordedAt=rs.getObject("event_at").toString(),
                glRecognized=rs.getBoolean("gl_recognized"),
                observedBankAllocation=rs.getBoolean("confirmed_bank_allocation"),
                reversed=rs.getBoolean("reversed"),
                status=rs.getString("source_status"),
            )},org,project,limit+1,
        )
        return FinanceProjectSourceReport(org,project,
            data.take(limit),data.size>limit)
    }
    private fun denied():Nothing=throw ProductApiException(
        "FINANCE_PROJECT_SOURCE_UNAVAILABLE",
        "Company Finance source reporting permission required.",
        HttpStatus.NOT_FOUND,
    )
}
@RestController
class FinanceProjectSourceReportController(
    private val reports:FinanceProjectSourceReportService,
) {
    @GetMapping("/v1/finance/organizations/{orgId}/projects/{projectId}/source-events")
    fun read(
        http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable projectId:UUID,
        @RequestParam(defaultValue="100") limit:Int,
    ):FinanceProjectSourceReport =
        reports.read(actor(http),orgId,projectId,limit)
    private fun actor(http:HttpServletRequest):UUID =
        HiltechRequestContext.current(http).identityId
            ?.let{runCatching{UUID.fromString(it)}.getOrNull()}
            ?:throw ProductApiException(
                "UNAUTHENTICATED","Finance identity required.",
                HttpStatus.UNAUTHORIZED)
}

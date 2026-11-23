package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.util.UUID

/**
 * Posted AP is gross historically recognized liability only.
 * No customer receivable, paid amount, outstanding balance, due date,
 * bank confirmation or total company cash is inferred without actual source.
 */
data class FinanceApLedgerEvidenceGroup(
    val supplierOrganizationId: UUID,
    val currency: String,
    val postedJournalCount: Long,
    val historicallyRecognizedGross: String,
    val mostRecentPostingAt: String,
    val sourceOnly: Boolean = true,
    val settledAmount: String? = null,
    val outstandingBalance: String? = null,
    val overdueBalance: String? = null,
    val bankConfirmedAmount: String? = null,
)

data class FinanceApLedgerEvidenceReport(
    val organizationId: UUID,
    val fiscalPeriodId: UUID?,
    val observedAt: String,
    val sourceAvailable: Boolean,
    val sourceStatus: String,
    val groups: List<FinanceApLedgerEvidenceGroup>,
    val queryLimit: Int = 100,
    val hasMoreGroups: Boolean = false,
    val isCompanyWideTrialBalance: Boolean = false,
    val companyOutstandingPayables: String? = null,
    val companySupplierPayments: String? = null,
    val companyBankReconciledTotal: String? = null,
)

@Service
class FinanceApLedgerEvidenceReportService(
    private val jdbc: JdbcTemplate,
    private val approvals: ApprovalPersistencePort,
    private val environment: Environment,
    private val clock: Clock,
) {
    fun get(
        actor: UUID, org: UUID, fiscalPeriodId: UUID?,
    ): FinanceApLedgerEvidenceReport {
        requireAuthority(actor, org)
        val now = clock.instant().toString()
        if (!sourceReady()) return FinanceApLedgerEvidenceReport(
            organizationId=org,fiscalPeriodId=fiscalPeriodId,
            observedAt=now,sourceAvailable=false,
            sourceStatus="POSTED_AP_LEDGER_SOURCE_NOT_INTEGRATED",
            groups=emptyList(),
        )
        if (fiscalPeriodId != null) {
            val valid = jdbc.queryForObject(
                """
                SELECT EXISTS (
                  SELECT 1 FROM finance_gl_period
                  WHERE id=? AND organization_id=?
                )
                """.trimIndent(),Boolean::class.java,fiscalPeriodId,org,
            ) == true
            if (!valid) unavailable()
        }
        val sql = if (fiscalPeriodId == null) {
            """
            SELECT p.supplier_organization_id,j.currency,
                COUNT(j.id)::bigint AS journal_count,
                SUM(j.gross_amount) AS recognized_gross,
                MAX(j.posted_at) AS latest_posted_at
            FROM finance_ap_posted_journal j
            JOIN finance_ap_posting_proposal p
                ON p.id=j.proposal_id AND p.organization_id=j.organization_id
            WHERE j.organization_id=?
            GROUP BY p.supplier_organization_id,j.currency
            ORDER BY p.supplier_organization_id,j.currency
            LIMIT 101
            """.trimIndent()
        } else {
            """
            SELECT p.supplier_organization_id,j.currency,
                COUNT(j.id)::bigint AS journal_count,
                SUM(j.gross_amount) AS recognized_gross,
                MAX(j.posted_at) AS latest_posted_at
            FROM finance_ap_posted_journal j
            JOIN finance_ap_posting_proposal p
                ON p.id=j.proposal_id AND p.organization_id=j.organization_id
            WHERE j.organization_id=? AND j.fiscal_period_id=?
            GROUP BY p.supplier_organization_id,j.currency
            ORDER BY p.supplier_organization_id,j.currency
            LIMIT 101
            """.trimIndent()
        }
        val mapper=RowMapper<FinanceApLedgerEvidenceGroup> { rs,_ ->
            val amount=rs.getBigDecimal("recognized_gross")
            if (amount==null || amount.signum()<0) unavailable()
            FinanceApLedgerEvidenceGroup(
                supplierOrganizationId=rs.getObject(
                    "supplier_organization_id",UUID::class.java,
                ),
                currency=rs.getString("currency"),
                postedJournalCount=rs.getLong("journal_count"),
                historicallyRecognizedGross=amount.toPlainString(),
                mostRecentPostingAt=rs.getObject(
                    "latest_posted_at",java.time.OffsetDateTime::class.java,
                ).toInstant().toString(),
            )
        }
        val rows=if(fiscalPeriodId==null) {
            jdbc.query(sql,mapper,org)
        } else {
            jdbc.query(sql,mapper,org,fiscalPeriodId)
        }
        return FinanceApLedgerEvidenceReport(
            organizationId=org,fiscalPeriodId=fiscalPeriodId,
            observedAt=now,sourceAvailable=true,
            sourceStatus="POSTED_SUPPLIER_AP_HISTORICAL_GROSS_NOT_OUTSTANDING",
            groups=rows.take(100),hasMoreGroups=rows.size>100,
        )
    }

    private fun sourceReady(): Boolean =
        jdbc.queryForObject(
            """
            SELECT
                to_regclass('public.finance_ap_posted_journal') IS NOT NULL
                AND to_regclass('public.finance_ap_posting_proposal') IS NOT NULL
            """.trimIndent(),Boolean::class.java,
        ) == true

    private fun requireAuthority(actor:UUID,org:UUID) {
        val key=environment.getProperty(
            "hiltech.finance.ap-reporting.viewer-authority-key","",
        ).trim()
        if(!environment.getProperty(
                "hiltech.finance.ap-reporting.enabled",Boolean::class.java,false,
            ) || !Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(key)) unavailable()
        val now=clock.instant()
        if(!approvals.isActiveOrganizationMember(org,actor,now)) unavailable()
        val binding=approvals.resolveCurrentAuthority(org,key,now) ?: unavailable()
        if(binding.organizationId!=org || binding.authorityKey!=key ||
            !approvals.actorMatchesAuthority(actor,binding,now)) unavailable()
    }
    private fun unavailable():Nothing=throw ProductApiException(
        "FINANCE_AP_LEDGER_REPORT_UNAVAILABLE",
        "Posted supplier ledger source or current Finance authority unavailable.",
        HttpStatus.NOT_FOUND,
    )
}

@RestController
class FinanceApLedgerEvidenceReportController(
    private val report:FinanceApLedgerEvidenceReportService,
) {
    @GetMapping("/v1/finance/organizations/{orgId}/supplier-ap-evidence-report")
    fun get(
        http:HttpServletRequest,
        @PathVariable orgId:UUID,
        @RequestParam(required=false) periodId:UUID?,
    ):FinanceApLedgerEvidenceReport {
        val actor=HiltechRequestContext.current(http).identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?:throw ProductApiException(
                "UNAUTHENTICATED","Finance login required.",HttpStatus.UNAUTHORIZED,
            )
        return report.get(actor,orgId,periodId)
    }
}

package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

/**
 * Finance reads Phase10's exact released-award source, not a Sales price
 * estimate or a made-up billing milestone.
 *
 * This is the boundary between Commercial and Finance. A signed award
 * does NOT by itself establish an issued tax invoice, accounts receivable,
 * earned revenue, legal maturity or a bank receipt.
 */
data class FinanceClientAwardSourceRow(
    val organizationId: UUID,
    val opportunityId: UUID,
    val projectId: UUID,
    val clientOrganizationId: UUID,
    val quoteVersionId: UUID,
    val quoteRevision: Int,
    val quoteRecordVersion: Long,
    val contractId: UUID,
    val clientPoId: UUID,
    val sellAmount: String,
    val currency: String,
    val paymentTerms: String,
    val sourceFingerprintSha256: String,
    val financeReviewRequired: Boolean = true,
    val customerInvoiceIssued: Boolean = false,
    val receivableRecognized: Boolean = false,
    val billingMilestonesVerified: Boolean = false,
    val taxTreatmentVerified: Boolean = false,
    val amountDueOrOverdueKnown: Boolean = false,
    val cashCollected: Boolean = false,
    val cashReconciled: Boolean = false,
)

data class FinanceClientAwardSourcePage(
    val organizationId: UUID,
    val observedAt: String,
    val sourceAvailable: Boolean,
    val sourceStatus: String,
    val rows: List<FinanceClientAwardSourceRow>,
    val queryLimit: Int = 100,
    val hasMoreSources: Boolean = false,
    val reportedCompanyReceivableTotal: String? = null,
    val reportedCompanyRevenueTotal: String? = null,
    val reportedCustomerCollectionsTotal: String? = null,
)

@Service
class FinanceClientAwardSourceService(
    private val jdbc: JdbcTemplate,
    private val approvals: ApprovalPersistencePort,
    private val environment: Environment,
    private val clock: Clock,
) {
    fun list(actor: UUID, organizationId: UUID): FinanceClientAwardSourcePage {
        requireFinance(actor, organizationId)
        val observedAt = clock.instant().toString()
        if (!commercialSourceInstalled()) {
            return FinanceClientAwardSourcePage(
                organizationId = organizationId,
                observedAt = observedAt,
                sourceAvailable = false,
                sourceStatus = "COMMERCIAL_FINANCE_AWARD_SOURCE_NOT_INTEGRATED",
                rows = emptyList(),
            )
        }
        val rows = query(organizationId, null)
        return FinanceClientAwardSourcePage(
            organizationId = organizationId,
            observedAt = observedAt,
            sourceAvailable = true,
            sourceStatus = "RELEASED_COMMERCIAL_AWARDS_UNBILLED_SOURCE_REVIEW_ONLY",
            rows = rows.take(100),
            hasMoreSources = rows.size > 100,
        )
    }

    fun detail(
        actor: UUID, organizationId: UUID, opportunityId: UUID,
    ): FinanceClientAwardSourceRow {
        requireFinance(actor, organizationId)
        if (!commercialSourceInstalled()) unavailable()
        return query(organizationId, opportunityId).singleOrNull() ?: unavailable()
    }

    private fun query(
        org: UUID, opportunityId: UUID?,
    ): List<FinanceClientAwardSourceRow> {
        // P10 is a separate draft lane: this named source VIEW is created
        // only by DevOps after canonical Phase10 migration and integration.
        // Never silently return quote leads as live customer receivables.
        val where = if (opportunityId == null) {
            "s.organization_id=?"
        } else {
            "s.organization_id=? AND s.opportunity_id=?"
        }
        val sql = """
            SELECT s.organization_id,s.opportunity_id,s.project_id,
                s.client_organization_id,s.quote_version_id,
                s.quote_revision,s.quote_record_version,
                s.contract_id,s.client_po_id,s.total_price,
                s.currency,s.payment_terms
            FROM commercial_finance_award_source s
            WHERE $where
            ORDER BY s.opportunity_id
            LIMIT 101
        """.trimIndent()
        val mapper = org.springframework.jdbc.core.RowMapper<FinanceClientAwardSourceRow> { rs, _ ->
            val organizationId = rs.getObject("organization_id", UUID::class.java)
            val opportunity = rs.getObject("opportunity_id", UUID::class.java)
            val project = rs.getObject("project_id", UUID::class.java)
            val client = rs.getObject("client_organization_id", UUID::class.java)
            val quote = rs.getObject("quote_version_id", UUID::class.java)
            val contract = rs.getObject("contract_id", UUID::class.java)
            val clientPo = rs.getObject("client_po_id", UUID::class.java)
            val amount = rs.getBigDecimal("total_price")
            val currency = rs.getString("currency")
            val paymentTerms = rs.getString("payment_terms")
            val quoteRevision = rs.getInt("quote_revision")
            val quoteVersion = rs.getLong("quote_record_version")
            // Never present malformed/stale source as an invoice candidate.
            if (organizationId != org || amount == null ||
                amount <= BigDecimal.ZERO || quoteRevision <= 0 ||
                quoteVersion <= 0 || currency.isNullOrBlank() ||
                !Regex("^[A-Z]{3}$").matches(currency) ||
                paymentTerms.isNullOrBlank() || client == organizationId
            ) unavailable()
            val canonical = listOf(
                "P8_SOURCE_FROM_P10_RELEASED_AWARD_V1", organizationId, opportunity,
                project, client, quote, quoteRevision, quoteVersion,
                contract, clientPo, amount.stripTrailingZeros().toPlainString(),
                currency, paymentTerms,
            ).joinToString("|")
            val sha = MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            FinanceClientAwardSourceRow(
                organizationId = organizationId,
                opportunityId = opportunity,
                projectId = project,
                clientOrganizationId = client,
                quoteVersionId = quote,
                quoteRevision = quoteRevision,
                quoteRecordVersion = quoteVersion,
                contractId = contract,
                clientPoId = clientPo,
                sellAmount = amount.toPlainString(),
                currency = currency,
                paymentTerms = paymentTerms,
                sourceFingerprintSha256 = sha,
            )
        }
        return if (opportunityId == null) {
            jdbc.query(sql, mapper, org)
        } else {
            jdbc.query(sql, mapper, org, opportunityId)
        }
    }

    private fun commercialSourceInstalled(): Boolean =
        jdbc.queryForObject(
            "SELECT to_regclass('public.commercial_finance_award_source') IS NOT NULL",
            Boolean::class.java,
        ) == true

    private fun requireFinance(actor: UUID, org: UUID) {
        val key = environment.getProperty(
            "hiltech.finance.receivables.sales-award-source-review-authority-key", "",
        ).trim()
        if (!environment.getProperty(
                "hiltech.finance.receivables.sales-award-source-readiness.enabled",
                Boolean::class.java, false,
            ) || !Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(key)) unavailable()
        val now = clock.instant()
        if (!approvals.isActiveOrganizationMember(org, actor, now)) unavailable()
        val authority = approvals.resolveCurrentAuthority(org, key, now)
            ?: unavailable()
        if (authority.organizationId != org || authority.authorityKey != key ||
            !approvals.actorMatchesAuthority(actor, authority, now)) unavailable()
    }

    private fun unavailable(): Nothing = throw ProductApiException(
        "FINANCE_CLIENT_AWARD_SOURCE_UNAVAILABLE",
        "Client award Finance source is not installed or not authorized.",
        HttpStatus.NOT_FOUND,
    )
}

@RestController
class FinanceClientAwardSourceController(
    private val service: FinanceClientAwardSourceService,
) {
    @GetMapping("/v1/finance/organizations/{orgId}/client-award-sources")
    fun list(
        http: HttpServletRequest,
        @PathVariable orgId: UUID,
    ): FinanceClientAwardSourcePage = service.list(actor(http), orgId)

    @GetMapping(
        "/v1/finance/organizations/{orgId}/client-award-sources/{opportunityId}",
    )
    fun detail(
        http: HttpServletRequest,
        @PathVariable orgId: UUID,
        @PathVariable opportunityId: UUID,
    ): FinanceClientAwardSourceRow =
        service.detail(actor(http), orgId, opportunityId)

    private fun actor(http: HttpServletRequest): UUID =
        HiltechRequestContext.current(http).identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ProductApiException(
                "UNAUTHENTICATED", "Authentication required.", HttpStatus.UNAUTHORIZED,
            )
}

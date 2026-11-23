package com.hiltech.server.finance

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Clock
import java.util.UUID

/**
 * Source-derived preview only. A real payment instruction requires an
 * accountant-recognized liability, verified beneficiary, company-approved
 * exact amount/source and versioned bank signing mandate. None exist yet.
 *
 * No payment instruction command, payment batch, bank account, provider
 * endpoint, retry or automatic transfer can be reached through these views.
 */
data class FinancePaymentPreflightRow(
    val sourcePayableCandidateId: UUID,
    val supplierOrganizationId: UUID,
    val supplierInvoiceId: UUID,
    val invoiceNumber: String,
    val amount: String,
    val currency: String,
    val sourceSha256: String,
    val accountingSourceState: String,
    val sourceCurrent: Boolean,
    val blockingReasons: List<String>,
    val actionablePaymentInstruction: Boolean = false,
    val paymentExecutionAvailable: Boolean = false,
)

data class FinancePaymentPreflightReport(
    val organizationId: UUID,
    val observedAt: String,
    val sourceType: String = "P7_MATCHED_SUPPLIER_INVOICE_TO_P8A_REVIEW_CANDIDATE",
    val rows: List<FinancePaymentPreflightRow>,
    val sourceQueryLimit: Int = 100,
    val possibleUnreturnedSources: Boolean = false,
    val paymentInstructionEnabled: Boolean = false,
    val approvalEnabled: Boolean = false,
    val bankExecutionEnabled: Boolean = false,
    val matchedBankStatements: Int? = null,
    val paymentResultsObserved: Int? = null,
    val paymentResultEvidenceSource: String = "NOT_IMPLEMENTED_NO_PROVIDER_OR_RESULT_CONTRACT",
)

data class FinanceCandidatePulseByCurrency(
    val currency: String,
    val reviewCandidateCount: Int,
    val reviewCandidateTotal: String,
    val sourceChangedCount: Int,
    val postedPayables: Int? = null,
    val approvedPayments: Int? = null,
    val executedPayments: Int? = null,
)

data class FinanceCandidatePulse(
    val organizationId: UUID,
    val observedAt: String,
    val dataSemantics: String = "UNPOSTED_REVIEW_CANDIDATES_ONLY_NO_ACTUAL_FINANCIAL_POSITION",
    val sourceItems: Int,
    val sourceQueryLimit: Int = 100,
    val possibleUnreturnedSources: Boolean = false,
    val groupsByCurrency: List<FinanceCandidatePulseByCurrency>,
    val actualBankBalanceKnown: Boolean = false,
    val reconciliationAvailable: Boolean = false,
)

/** Only Finance source reviewers can read, via existing Phase 8A authority. */
@Service
class FinancePaymentPreflightService(
    private val sources: FinancePayableCandidateService,
    private val clock: Clock,
    private val environment: Environment,
) {
    fun report(actor: UUID, org: UUID): FinancePaymentPreflightReport {
        configured()
        // Delegates auth to exactly the same current-authority verifier as P8A.
        // This must happen BEFORE any source data is returned.
        val candidates = sources.candidates(actor, org)
        return FinancePaymentPreflightReport(
            organizationId = org,
            observedAt = clock.instant().toString(),
            rows = candidates.map(::preflight),
            possibleUnreturnedSources = candidates.size >= 100,
        )
    }

    fun pulse(actor: UUID, org: UUID): FinanceCandidatePulse {
        configured()
        val candidates = sources.candidates(actor, org)
        // Never sum unlike currencies or report unbooked reviews as cash,
        // receivables, payables or recognized financial totals.
        val groups = candidates
            .groupBy { it.currency }
            .toSortedMap()
            .map { (currency, items) ->
                FinanceCandidatePulseByCurrency(
                    currency = currency,
                    reviewCandidateCount = items.size,
                    reviewCandidateTotal = items.fold(BigDecimal.ZERO) { acc, item ->
                        acc.add(BigDecimal(item.amount))
                    }.stripTrailingZeros().toPlainString(),
                    sourceChangedCount = items.count { !it.sourceCurrent },
                )
            }
        return FinanceCandidatePulse(
            organizationId = org,
            observedAt = clock.instant().toString(),
            sourceItems = candidates.size,
            possibleUnreturnedSources = candidates.size >= 100,
            groupsByCurrency = groups,
        )
    }

    private fun configured() {
        if (!environment.getProperty(
                "hiltech.finance.payment-preflight.enabled",
                Boolean::class.java, false,
            )) throw ProductApiException(
                "FINANCE_PAYMENT_PREFLIGHT_UNAVAILABLE",
                "Finance source review not configured.", HttpStatus.NOT_FOUND,
            )
    }

    internal fun preflight(source: SupplierPayableCandidateView): FinancePaymentPreflightRow =
        FinancePaymentPreflightRow(
            sourcePayableCandidateId = source.id,
            supplierOrganizationId = source.supplierOrganizationId,
            supplierInvoiceId = source.invoiceId,
            invoiceNumber = source.invoiceNumber,
            amount = source.amount,
            currency = source.currency,
            sourceSha256 = source.sourceSha256,
            accountingSourceState = source.state,
            sourceCurrent = source.sourceCurrent,
            blockingReasons = buildList {
                if (!source.sourceCurrent) add("SUPPLIER_SOURCE_CHANGED_NEEDS_NEW_MATCH")
                if (source.state != "ACCOUNTING_REVIEW_REQUIRED") {
                    add("SOURCE_ACCOUNTING_STATE_UNSUPPORTED")
                }
                add("ACCOUNTING_RECOGNITION_NOT_AUTHORIZED_OR_BOOKED")
                add("COMPANY_PAYMENT_APPROVAL_POLICY_UNAVAILABLE")
                add("EXACT_BENEFICIARY_BANK_AND_SIGNATORY_UNVERIFIED")
                add("PAYMENT_EXECUTION_PROVIDER_NOT_VALIDATED")
                add("EXTERNAL_RESULT_AND_RECONCILIATION_NOT_IMPLEMENTED")
            },
        )
}

@RestController
class FinancePaymentPreflightController(
    private val finance: FinancePaymentPreflightService,
) {
    @GetMapping("/v1/finance/organizations/{orgId}/payment-preflight")
    fun preflight(req: HttpServletRequest, @PathVariable orgId: UUID) =
        finance.report(actor(req), orgId)

    @GetMapping("/v1/finance/organizations/{orgId}/review-candidate-pulse")
    fun pulse(req: HttpServletRequest, @PathVariable orgId: UUID) =
        finance.pulse(actor(req), orgId)

    private fun actor(req: HttpServletRequest): UUID =
        HiltechRequestContext.current(req).identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ProductApiException(
                "UNAUTHENTICATED", "Authentication required.", HttpStatus.UNAUTHORIZED,
            )
}

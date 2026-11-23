package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalRequestState
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.util.UUID

data class RequestSupplierAccountingReview(val operationId: UUID)

data class FinanceSupplierAccountingReviewStatus(
    val candidateId: UUID,
    val supplierInvoiceId: UUID,
    val supplierOrganizationId: UUID,
    val invoiceNumber: String,
    val amount: String,
    val currency: String,
    val sourceSha256: String,
    val sourceVersion: Long,
    val policyKey: String,
    val policyVersion: Int,
    val approvalRequestId: UUID?,
    val reviewState: String,
    val existingRequest: Boolean = false,
    val replayed: Boolean = false,
    val bookedPayable: Boolean = false,
    val taxCredited: Boolean = false,
    val paymentApproved: Boolean = false,
    val bankPaymentSent: Boolean = false,
    val paymentReconciled: Boolean = false,
)

/**
 * Company finance reviewer requests ACCOUNTING review of a matched invoice.
 * A document/match review isn't an accounting entry, payment instruction,
 * tax credit or valid beneficiary mandate.
 */
@Service
class FinanceSupplierAccountingReviewService(
    private val payableCandidates: FinancePayableCandidateService,
    private val source: FinanceSupplierAccountingApprovalSubject,
    private val approvals: ApprovalEngineService,
    private val authority: ApprovalPersistencePort,
    private val clock: java.time.Clock,
) {
    fun request(
        actor: UUID, candidateId: UUID, operationId: UUID, correlationId: String,
    ): FinanceSupplierAccountingReviewStatus {
        val verified = authorized(actor, candidateId)
        val (snapshot, subject) = verified
        val existing = approvals.subjectStatus(subject, snapshot.policyKey)
        if (existing != null) {
            if (existing.state != ApprovalRequestState.PENDING ||
                existing.subjectVersion != snapshot.sourceVersion ||
                existing.policyKey != snapshot.policyKey) unavailable()
            return status(snapshot, existing.approvalRequestId, existing.state.name,
                existingRequest = true)
        }
        val result = approvals.requestException(
            actorUserId = actor,
            operationId = operationId,
            subject = subject,
            policyKey = snapshot.policyKey,
            reasonCode = "FINANCE_SUPPLIER_ACCOUNTING_REVIEW",
            safeReasonSummary = null,
            correlationId = correlationId,
            allowAuthorityBypass = false,
        )
        if (!result.approvalRequired || result.approvalRequestId == null ||
            result.state != ApprovalRequestState.PENDING ||
            result.policyKey != snapshot.policyKey ||
            result.policyVersion != snapshot.policyVersion) unavailable()
        return status(
            snapshot, result.approvalRequestId, result.state.name,
            replayed = result.replayed,
        )
    }

    fun mine(
        actor: UUID, candidateId: UUID,
    ): FinanceSupplierAccountingReviewStatus {
        val (snapshot, subject) = authorized(actor, candidateId)
        val existing = approvals.subjectStatus(subject, snapshot.policyKey)
        return status(
            snapshot, existing?.approvalRequestId,
            existing?.state?.name ?: "NOT_REQUESTED",
            existingRequest = existing != null,
        )
    }

    private fun authorized(
        actor: UUID, candidateId: UUID,
    ): Pair<FinanceSupplierAccountingSnapshot, ApprovalSubjectRef> {
        // Reuses exact current company Finance authority and the canonical
        // 3-way match preview from P8A; never queries another org directly.
        val candidate = payableCandidates.detail(actor, candidateId)
        if (candidate.state != "ACCOUNTING_REVIEW_REQUIRED" ||
            !candidate.sourceCurrent) unavailable()
        // Handoff and review-candidate ID are immutable once recorded. Scope
        // comes from the original P8A source; the subject guard rechecks P7.
        val org = payableCandidates.organizationOfAuthorizedCandidate(actor, candidateId)
        val snapshot = source.current(org, candidateId) ?: unavailable()
        if (snapshot.candidateId != candidate.id ||
            snapshot.supplierInvoiceId != candidate.invoiceId ||
            snapshot.supplierOrganizationId != candidate.supplierId ||
            snapshot.invoiceNumber != candidate.invoiceNumber ||
            snapshot.currency != candidate.currency ||
            snapshot.sourceSha256 != candidate.sourceSha256 ||
            snapshot.amount.compareTo(BigDecimal(candidate.amount)) != 0) unavailable()

        // Company must actually appoint a separate accounting reviewer.
        val now = clock.instant()
        val assignedKey = authority.loadActivePolicy(org, snapshot.policyKey, now)
            ?.authorityKey ?: unavailable()
        val current = authority.resolveCurrentAuthority(org, assignedKey, now)
            ?: unavailable()
        if (!authority.isActiveOrganizationMember(org, actor, now) ||
            authority.actorMatchesAuthority(actor, current, now)) unavailable()
        return snapshot to ApprovalSubjectRef(
            organizationId = org,
            subjectType = FINANCE_SUPPLIER_ACCOUNTING_REVIEW_SUBJECT,
            subjectId = candidateId,
            subjectVersion = snapshot.sourceVersion,
        )
    }

    private fun status(
        snapshot: FinanceSupplierAccountingSnapshot,
        requestId: UUID?,
        state: String,
        existingRequest: Boolean = false,
        replayed: Boolean = false,
    ) = FinanceSupplierAccountingReviewStatus(
        candidateId = snapshot.candidateId,
        supplierInvoiceId = snapshot.supplierInvoiceId,
        supplierOrganizationId = snapshot.supplierOrganizationId,
        invoiceNumber = snapshot.invoiceNumber,
        amount = snapshot.amount.toPlainString(),
        currency = snapshot.currency,
        sourceSha256 = snapshot.sourceSha256,
        sourceVersion = snapshot.sourceVersion,
        policyKey = snapshot.policyKey,
        policyVersion = snapshot.policyVersion,
        approvalRequestId = requestId,
        reviewState = state,
        existingRequest = existingRequest,
        replayed = replayed,
    )

    private fun unavailable(): Nothing = throw ProductApiException(
        "FINANCE_ACCOUNTING_REVIEW_UNAVAILABLE",
        "Supplier invoice accounting review not available or source changed.",
        HttpStatus.NOT_FOUND,
    )
}

@RestController
class FinanceSupplierAccountingReviewController(
    private val service: FinanceSupplierAccountingReviewService,
) {
    @PostMapping("/v1/finance/payable-candidates/{id}/request-accounting-review")
    fun request(
        http: HttpServletRequest,
        @PathVariable id: UUID,
        @RequestBody body: RequestSupplierAccountingReview,
    ): FinanceSupplierAccountingReviewStatus {
        val ctx = HiltechRequestContext.current(http)
        IdempotencyKeyContract.requireMatches(ctx.idempotencyKey, body.operationId)
        return service.request(
            actor(http), id, body.operationId, ctx.correlationId,
        )
    }

    @GetMapping("/v1/finance/payable-candidates/{id}/accounting-review")
    fun status(
        http: HttpServletRequest, @PathVariable id: UUID,
    ): FinanceSupplierAccountingReviewStatus =
        service.mine(actor(http), id)

    private fun actor(http: HttpServletRequest): UUID =
        HiltechRequestContext.current(http).identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ProductApiException(
                "UNAUTHENTICATED", "Authentication required.", HttpStatus.UNAUTHORIZED,
            )
}

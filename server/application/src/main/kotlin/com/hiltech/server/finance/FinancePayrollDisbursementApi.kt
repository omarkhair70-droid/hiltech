package com.hiltech.server.finance

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/v1/finance/payroll/organizations/{orgId}/disbursements")
class FinancePayrollDisbursementApi(private val service: FinancePayrollDisbursementService, private val evidence: FinancePayrollEvidenceService) {
    @GetMapping("/capabilities") fun capabilities(request: HttpServletRequest, @PathVariable orgId: UUID) = service.capabilities(actor(request), orgId)
    @GetMapping("/batches") fun batches(request: HttpServletRequest, @PathVariable orgId: UUID) = service.batches(actor(request), orgId)
    @GetMapping("/runs/{runId}") fun run(request: HttpServletRequest, @PathVariable orgId: UUID, @PathVariable runId: UUID) = service.run(actor(request), orgId, runId)
    @GetMapping("/instructions/{id}/bank-lines") fun bankLines(request: HttpServletRequest, @PathVariable orgId: UUID, @PathVariable id: UUID,
        @RequestParam action: String) = service.bankChoices(actor(request), orgId, id, action)
    @PostMapping("/batches/{id}/prepare") fun prepare(request: HttpServletRequest, @PathVariable orgId: UUID, @PathVariable id: UUID,
        @RequestBody body: FinancePayrollOperation): FinancePayrollCommandReceipt {
        checkOperation(request, body.operationId)
        return service.prepareBatch(actor(request), orgId, id, body.operationId, context(request).correlationId)
    }
    @PostMapping("/instructions/{id}/result-proposals") fun propose(request: HttpServletRequest, @PathVariable orgId: UUID, @PathVariable id: UUID,
        @RequestBody body: FinancePayrollPrepareResult): FinancePayrollCommandReceipt {
        checkOperation(request, body.operationId)
        return service.propose(actor(request), orgId, id, body, context(request).correlationId)
    }
    @PostMapping("/result-proposals/{id}/review") fun review(request: HttpServletRequest, @PathVariable orgId: UUID, @PathVariable id: UUID,
        @RequestBody body: FinancePayrollReviewResult): FinancePayrollCommandReceipt {
        checkOperation(request, body.operationId)
        return service.review(actor(request), orgId, id, body, context(request).correlationId)
    }
    @PostMapping("/result-proposals/{id}/evidence-content") fun originalEvidence(request: HttpServletRequest,
        @PathVariable orgId: UUID, @PathVariable id: UUID): FinancePayrollEvidenceDocument =
        evidence.read(actor(request), orgId, id, context(request).correlationId)
    private fun context(request: HttpServletRequest) = HiltechRequestContext.current(request)
    private fun actor(request: HttpServletRequest): UUID = context(request).identityId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw ProductApiException("UNAUTHENTICATED", "Authentication required.", HttpStatus.UNAUTHORIZED)
    private fun checkOperation(request: HttpServletRequest, operation: UUID) = IdempotencyKeyContract.requireMatches(context(request).idempotencyKey, operation)
}

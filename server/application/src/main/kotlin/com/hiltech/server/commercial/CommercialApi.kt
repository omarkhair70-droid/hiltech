package com.hiltech.server.commercial

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Internal commercial surface. All HTTP results are permission-scoped in the service;
 * only released quotes have a separate sell-only projection.
 */
@RestController
@RequestMapping("/v1/commercial")
class CommercialController(private val service: CommercialService) {
    private fun operation(key: String, id: String): UUID {
        val parsed = id.commercialUuid()
        IdempotencyKeyContract.requireMatches(key, parsed)
        return parsed
    }

    @PostMapping("/opportunities")
    fun create(
        http: HttpServletRequest,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: CreateCommercialOpportunityRequest,
    ) = service.createOpportunity(
        body, operation(key, body.operationId),
        HiltechRequestContext.current(http).requireIdentityId(),
        HiltechRequestContext.current(http).correlationId,
    )

    @GetMapping("/opportunities")
    fun list(http: HttpServletRequest, @RequestParam organizationId: String,
             @RequestParam(defaultValue = "100") limit: Int) =
        service.listOpportunities(
            organizationId.commercialUuid(),
            HiltechRequestContext.current(http).requireIdentityId(), limit,
        )

    @GetMapping("/opportunities/{opportunityId}")
    fun detail(http: HttpServletRequest, @PathVariable opportunityId: String) =
        service.opportunityDetail(
            opportunityId.commercialUuid(),
            HiltechRequestContext.current(http).requireIdentityId(),
        )

    @PostMapping("/opportunities/{opportunityId}/tenders")
    fun tender(http: HttpServletRequest, @PathVariable opportunityId: String,
               @RequestHeader("Idempotency-Key") key: String,
               @RequestBody body: CommercialTenderRequest) =
        service.addTender(
            opportunityId.commercialUuid(), body, operation(key, body.operationId),
            HiltechRequestContext.current(http).requireIdentityId(),
            HiltechRequestContext.current(http).correlationId,
        )

    @PostMapping("/opportunities/{opportunityId}/visits")
    fun visit(http: HttpServletRequest, @PathVariable opportunityId: String,
              @RequestHeader("Idempotency-Key") key: String,
              @RequestBody body: CommercialVisitRequest) =
        service.addVisit(
            opportunityId.commercialUuid(), body, operation(key, body.operationId),
            HiltechRequestContext.current(http).requireIdentityId(),
            HiltechRequestContext.current(http).correlationId,
        )

    @PostMapping("/visits/{visitId}/complete")
    fun completeVisit(http: HttpServletRequest, @PathVariable visitId: String,
                      @RequestHeader("Idempotency-Key") key: String,
                      @RequestBody body: CommercialVisitCompletionRequest) =
        service.completeVisit(
            visitId.commercialUuid(), body, operation(key, body.operationId),
            HiltechRequestContext.current(http).requireIdentityId(),
            HiltechRequestContext.current(http).correlationId,
        )

    @PostMapping("/opportunities/{opportunityId}/close")
    fun close(http: HttpServletRequest, @PathVariable opportunityId: String,
              @RequestHeader("Idempotency-Key") key: String,
              @RequestBody body: CommercialCloseRequest) =
        service.closeOpportunity(
            opportunityId.commercialUuid(), body, operation(key, body.operationId),
            HiltechRequestContext.current(http).requireIdentityId(),
            HiltechRequestContext.current(http).correlationId,
        )

    @PostMapping("/opportunities/{opportunityId}/scopes")
    fun scope(http: HttpServletRequest, @PathVariable opportunityId: String,
              @RequestHeader("Idempotency-Key") key: String,
              @RequestBody body: CommercialScopeRequest) =
        service.addScope(
            opportunityId.commercialUuid(), body, operation(key, body.operationId),
            HiltechRequestContext.current(http).requireIdentityId(),
            HiltechRequestContext.current(http).correlationId,
        )

    @PostMapping("/opportunities/{opportunityId}/boqs")
    fun boq(http: HttpServletRequest, @PathVariable opportunityId: String,
            @RequestHeader("Idempotency-Key") key: String,
            @RequestBody body: CommercialBoqRequest) =
        service.addBoq(
            opportunityId.commercialUuid(), body, operation(key, body.operationId),
            HiltechRequestContext.current(http).requireIdentityId(),
            HiltechRequestContext.current(http).correlationId,
        )

    @PostMapping("/opportunities/{opportunityId}/quotes")
    fun quote(http: HttpServletRequest, @PathVariable opportunityId: String,
              @RequestHeader("Idempotency-Key") key: String,
              @RequestBody body: CommercialQuoteRequest) =
        service.draftQuote(
            opportunityId.commercialUuid(), body, operation(key, body.operationId),
            HiltechRequestContext.current(http).requireIdentityId(),
            HiltechRequestContext.current(http).correlationId,
        )

    @PostMapping("/quotes/{quoteId}/request-approval")
    fun requestApproval(http: HttpServletRequest, @PathVariable quoteId: String,
                        @RequestHeader("Idempotency-Key") key: String,
                        @RequestBody body: CommercialApprovalRequest) =
        service.requestQuoteApproval(
            quoteId.commercialUuid(), operation(key, body.operationId),
            HiltechRequestContext.current(http).requireIdentityId(),
            HiltechRequestContext.current(http).correlationId,
        )

    @PostMapping("/quotes/{quoteId}/release")
    fun release(http: HttpServletRequest, @PathVariable quoteId: String,
                @RequestHeader("Idempotency-Key") key: String,
                @RequestBody body: CommercialApprovalRequest) =
        service.releaseQuote(
            quoteId.commercialUuid(), operation(key, body.operationId),
            HiltechRequestContext.current(http).requireIdentityId(),
            HiltechRequestContext.current(http).correlationId,
        )

    @GetMapping("/quotes/{quoteId}/customer-preview")
    fun quotePreview(http: HttpServletRequest, @PathVariable quoteId: String) =
        service.customerQuote(
            quoteId.commercialUuid(),
            HiltechRequestContext.current(http).requireIdentityId(),
        )

    @GetMapping("/opportunities/{opportunityId}/costing")
    fun costing(http: HttpServletRequest, @PathVariable opportunityId: String) =
        service.privateCosting(
            opportunityId.commercialUuid(),
            HiltechRequestContext.current(http).requireIdentityId(),
        )

    @PostMapping("/opportunities/{opportunityId}/contracts")
    fun contract(http: HttpServletRequest, @PathVariable opportunityId: String,
                 @RequestHeader("Idempotency-Key") key: String,
                 @RequestBody body: CommercialContractDraftRequest) =
        service.draftContract(
            opportunityId.commercialUuid(), body, operation(key, body.operationId),
            HiltechRequestContext.current(http).requireIdentityId(),
            HiltechRequestContext.current(http).correlationId,
        )

    @PostMapping("/opportunities/{opportunityId}/client-pos")
    fun clientPo(http: HttpServletRequest, @PathVariable opportunityId: String,
                 @RequestHeader("Idempotency-Key") key: String,
                 @RequestBody body: CommercialClientPoDraftRequest) =
        service.draftClientPo(
            opportunityId.commercialUuid(), body, operation(key, body.operationId),
            HiltechRequestContext.current(http).requireIdentityId(),
            HiltechRequestContext.current(http).correlationId,
        )

    @PostMapping("/opportunities/{opportunityId}/convert")
    fun convert(http: HttpServletRequest, @PathVariable opportunityId: String,
                @RequestHeader("Idempotency-Key") key: String,
                @RequestBody body: CommercialConversionRequest) =
        service.convertToProject(
            opportunityId.commercialUuid(), body, operation(key, body.operationId),
            HiltechRequestContext.current(http).requireIdentityId(),
            HiltechRequestContext.current(http).correlationId,
        )
}

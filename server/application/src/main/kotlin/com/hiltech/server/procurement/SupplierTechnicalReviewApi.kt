package com.hiltech.server.procurement

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@RestController
class SupplierTechnicalReviewController(private val service: SupplierTechnicalReviewService, private val evidence: SupplierTechnicalEvidenceReader) {
    @GetMapping("/v1/sourcing/organizations/{organizationId}/technical-review")
    fun queue(http: HttpServletRequest, @PathVariable organizationId: UUID, @RequestParam(defaultValue = "") search: String,
        @RequestParam(defaultValue = "0") offset: Int, @RequestParam(defaultValue = "40") limit: Int) =
        service.queue(actor(http), organizationId, search, offset, limit)

    @GetMapping("/v1/sourcing/quotes/{quoteId}/technical-review")
    fun detail(http: HttpServletRequest, @PathVariable quoteId: UUID) = service.detail(actor(http), quoteId)

    @PostMapping("/v1/sourcing/quotes/{quoteId}/technical-packets")
    fun publish(http: HttpServletRequest, @PathVariable quoteId: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: SupplierTechnicalPublishRequest): SupplierTechnicalPacketReceiptDto {
        IdempotencyKeyContract.requireMatches(key, technicalUuid(body.operationId))
        return service.publish(actor(http), quoteId, body, HiltechRequestContext.current(http).correlationId)
    }

    @PostMapping("/v1/sourcing/quotes/{quoteId}/technical-documents")
    fun document(http: HttpServletRequest, @PathVariable quoteId: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: SupplierTechnicalDocumentRequest): SupplierTechnicalDocumentSlotDto {
        IdempotencyKeyContract.requireMatches(key, technicalUuid(body.operationId))
        return service.createDocument(actor(http), quoteId, body, HiltechRequestContext.current(http).correlationId)
    }

    @PostMapping("/v1/sourcing/quotes/{quoteId}/technical-documents/{evidenceId}/pages/{page}")
    fun buyerPage(http: HttpServletRequest, @PathVariable quoteId: UUID, @PathVariable evidenceId: UUID, @PathVariable page: Int) =
        evidence.buyerPage(actor(http), quoteId, evidenceId, page, HiltechRequestContext.current(http).correlationId)

    @PostMapping("/v1/sourcing/quotes/{quoteId}/technical-packets/{packetId}/documents/{evidenceId}/pages/{page}")
    fun packetPage(http: HttpServletRequest, @PathVariable quoteId: UUID, @PathVariable packetId: UUID, @PathVariable evidenceId: UUID, @PathVariable page: Int) =
        evidence.packetPage(actor(http), quoteId, packetId, evidenceId, page, HiltechRequestContext.current(http).correlationId)

    @PostMapping("/v1/sourcing/quotes/{quoteId}/technical-packets/{packetId}/display-confirmations/{receiptId}")
    fun displayed(http: HttpServletRequest, @PathVariable quoteId: UUID, @PathVariable packetId: UUID, @PathVariable receiptId: UUID) =
        evidence.confirmDisplayed(actor(http), quoteId, packetId, receiptId, HiltechRequestContext.current(http).correlationId)

    private fun actor(http: HttpServletRequest): UUID = HiltechRequestContext.current(http).identityId?.let(::technicalUuid)
        ?: technicalFail("UNAUTHENTICATED", HttpStatus.UNAUTHORIZED)
}

@Component
class SupplierTechnicalSensitiveHttpFilter : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest) = !request.requestURI.matches(
        Regex("^/v1/sourcing/(?:organizations/[^/]+/technical-review|quotes/[^/]+/technical-(?:review|packets|documents))(?:/.*)?$"))
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        response.setHeader("Cache-Control", "no-store, private")
        response.setHeader("Pragma", "no-cache")
        response.setHeader("X-Content-Type-Options", "nosniff")
        filterChain.doFilter(request, response)
    }
}

package com.hiltech.server.procurement

import com.hiltech.server.identity.IdentitySessionService
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/v1/sourcing/po-candidates/{candidateId}/reviews/{approvalRequestId}")
class PurchaseOrderReviewController(private val service: PurchaseOrderReviewService, private val sessions: IdentitySessionService) {
    @GetMapping
    fun read(request: HttpServletRequest, @PathVariable candidateId: UUID, @PathVariable approvalRequestId: UUID): ResponseEntity<PurchaseOrderReviewDto> =
        response(service.read(actor(request), candidateId, approvalRequestId))

    @PostMapping("/quote-pages")
    fun page(request: HttpServletRequest, @PathVariable candidateId: UUID, @PathVariable approvalRequestId: UUID,
             @RequestBody body: PurchaseQuotePageRequestDto): ResponseEntity<PurchaseQuotePageDto> =
        response(service.quotationPage(actor(request), candidateId, approvalRequestId, body, HiltechRequestContext.current(request).correlationId))

    @PostMapping("/quote-openings")
    fun opened(request: HttpServletRequest, @PathVariable candidateId: UUID, @PathVariable approvalRequestId: UUID,
               @RequestHeader("Idempotency-Key") idempotencyKey: String, @RequestBody body: OpenPurchaseReviewQuoteDto): ResponseEntity<PurchaseQuoteOpeningReceiptDto> {
        IdempotencyKeyContract.requireMatches(idempotencyKey, uuid(body.operationId))
        return response(service.recordOpened(actor(request), candidateId, approvalRequestId, body, HiltechRequestContext.current(request).correlationId))
    }

    @PostMapping("/decisions")
    fun decide(request: HttpServletRequest, @AuthenticationPrincipal jwt: Jwt, @PathVariable candidateId: UUID, @PathVariable approvalRequestId: UUID,
               @RequestHeader("Idempotency-Key") idempotencyKey: String, @RequestHeader("X-Device-Installation-Id") installationId: String,
               @RequestBody body: DecidePurchaseReviewDto): ResponseEntity<PurchaseReviewDecisionReceiptDto> {
        IdempotencyKeyContract.requireMatches(idempotencyKey, uuid(body.operationId))
        sessions.requireRecentReauthentication(sessions.requireCurrentAccess(jwt, uuid(installationId)))
        return response(service.decide(actor(request), candidateId, approvalRequestId, body, HiltechRequestContext.current(request).correlationId))
    }

    private fun actor(request: HttpServletRequest): UUID = HiltechRequestContext.current(request).identityId?.let(::uuid)
        ?: throw ProductApiException("UNAUTHENTICATED", "Authentication is required.", HttpStatus.UNAUTHORIZED)
    private fun uuid(value: String): UUID = runCatching { UUID.fromString(value) }.getOrElse {
        throw ProductApiException("PROCUREMENT_REVIEW_INVALID_IDENTIFIER", "A review identifier is invalid.", HttpStatus.BAD_REQUEST)
    }
    private fun <T : Any> response(value: T): ResponseEntity<T> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value)
}

@RestController
class PurchaseOrderReviewReceiptController(private val service: PurchaseOrderReviewService) {
    @GetMapping("/v1/sourcing/purchase-review-receipts/{approvalRequestId}")
    fun completed(request: HttpServletRequest, @PathVariable approvalRequestId: UUID): ResponseEntity<PurchaseReviewCompletedDto> {
        val actor = HiltechRequestContext.current(request).identityId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ProductApiException("UNAUTHENTICATED", "Authentication is required.", HttpStatus.UNAUTHORIZED)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.completed(actor, approvalRequestId))
    }
}

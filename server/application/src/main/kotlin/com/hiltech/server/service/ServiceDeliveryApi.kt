package com.hiltech.server.service

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.identity.IdentitySessionService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/v1/projects/{projectId}/service-delivery")
@ConditionalOnProperty(prefix = "hiltech.service.delivery", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class ServiceDeliveryProjectController(private val service: ServiceDeliveryService, private val evidence: ServiceDeliveryEvidenceReader,
    private val packages: ServiceDeliveryPackageService) {
    @GetMapping
    fun workspace(http: HttpServletRequest, @PathVariable projectId: UUID) = service.workspace(deliveryActor(http), projectId)

    @PostMapping("/documents")
    fun document(http: HttpServletRequest, @PathVariable projectId: UUID, @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: ServiceDeliveryDocumentRequest): ServiceDeliveryDocumentSlotDto {
        IdempotencyKeyContract.requireMatches(key, deliveryUuid(body.operationId))
        return service.createDocument(deliveryActor(http), projectId, body, HiltechRequestContext.current(http).correlationId)
    }

    @PostMapping("/proposals")
    fun propose(http: HttpServletRequest, @PathVariable projectId: UUID, @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: ServiceDeliveryProposeRequest): ServiceDeliveryReceipt {
        IdempotencyKeyContract.requireMatches(key, deliveryUuid(body.operationId))
        return service.propose(deliveryActor(http), projectId, body, HiltechRequestContext.current(http).correlationId)
    }

    @PostMapping("/documents/{evidenceId}/pages/{page}")
    fun page(http: HttpServletRequest, @PathVariable projectId: UUID, @PathVariable evidenceId: UUID, @PathVariable page: Int) =
        evidence.projectPage(deliveryActor(http), projectId, evidenceId, page, HiltechRequestContext.current(http).correlationId)

    @PostMapping("/releases/{releaseId}/package")
    fun export(http: HttpServletRequest, @PathVariable projectId: UUID, @PathVariable releaseId: UUID) =
        packages.download(deliveryActor(http), projectId, releaseId, null, HiltechRequestContext.current(http).correlationId)

    @PostMapping("/releases/{releaseId}/documents/{evidenceId}")
    fun original(http: HttpServletRequest, @PathVariable projectId: UUID, @PathVariable releaseId: UUID, @PathVariable evidenceId: UUID) =
        packages.download(deliveryActor(http), projectId, releaseId, evidenceId, HiltechRequestContext.current(http).correlationId)
}

@RestController
@RequestMapping("/v1/service/organizations/{organizationId}/delivery-reviews")
@ConditionalOnProperty(prefix = "hiltech.service.delivery", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class ServiceDeliveryReviewController(private val service: ServiceDeliveryService, private val evidence: ServiceDeliveryEvidenceReader,
    private val sessions: IdentitySessionService) {
    @GetMapping
    fun queue(http: HttpServletRequest, @PathVariable organizationId: UUID) = service.reviewQueue(deliveryActor(http), organizationId)

    @GetMapping("/{proposalId}")
    fun proposal(http: HttpServletRequest, @PathVariable organizationId: UUID, @PathVariable proposalId: UUID) =
        service.proposal(deliveryActor(http), organizationId, proposalId)

    @PostMapping("/{proposalId}/decisions")
    fun review(http: HttpServletRequest, @PathVariable organizationId: UUID, @PathVariable proposalId: UUID,
        @AuthenticationPrincipal jwt: Jwt, @RequestHeader("X-Device-Installation-Id") installationId: UUID,
        @RequestHeader("Idempotency-Key") key: String, @RequestBody body: ServiceDeliveryReviewRequest): ServiceDeliveryReceipt {
        IdempotencyKeyContract.requireMatches(key, deliveryUuid(body.operationId))
        sessions.requireRecentReauthentication(sessions.requireCurrentAccess(jwt, installationId))
        return service.review(deliveryActor(http), organizationId, proposalId, body, HiltechRequestContext.current(http).correlationId)
    }

    @PostMapping("/{proposalId}/documents/{evidenceId}/pages/{page}")
    fun page(http: HttpServletRequest, @PathVariable organizationId: UUID, @PathVariable proposalId: UUID,
        @PathVariable evidenceId: UUID, @PathVariable page: Int) =
        evidence.proposalPage(deliveryActor(http), organizationId, proposalId, evidenceId, page, HiltechRequestContext.current(http).correlationId)

    @PostMapping("/{proposalId}/display-confirmations/{receiptId}")
    fun displayed(http: HttpServletRequest, @PathVariable organizationId: UUID, @PathVariable proposalId: UUID, @PathVariable receiptId: UUID) =
        evidence.confirmDisplayed(deliveryActor(http), organizationId, proposalId, receiptId, HiltechRequestContext.current(http).correlationId)
}

private fun deliveryActor(http: HttpServletRequest): UUID = HiltechRequestContext.current(http).identityId
    ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    ?: deliveryFail("UNAUTHENTICATED", HttpStatus.UNAUTHORIZED)

package com.hiltech.server.administration

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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class ConfigurePayrollReviewPolicyRequest(
    val operationId: String, val reviewerEmployeeId: String, val expectedReviewerIdentityId: String, val baseEmployeeVersion: Long,
    val expectedPolicyId: String? = null, val expectedPolicyVersion: Long? = null,
    val expectedBindingId: String? = null, val expectedBindingVersion: Long? = null,
    val pendingPoliciesToSupersede: List<CompanyPendingReviewPolicy> = emptyList(),
    val supersedeReason: String? = null,
)

@RestController
class PayrollReviewPolicyController(private val service: PayrollReviewPolicyService, private val sessions: IdentitySessionService) {
    @GetMapping("/v1/organizations/{organizationId}/administration/review-workflows/{workflow}")
    fun current(request: HttpServletRequest, @PathVariable organizationId: String, @PathVariable workflow: String): ResponseEntity<PayrollReviewSetupResponse> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.current(actor(request), uuid(organizationId), parseWorkflow(workflow)))

    @GetMapping("/v1/organizations/{organizationId}/administration/review-workflows/{workflow}/reviewer-candidates")
    fun candidates(request: HttpServletRequest, @PathVariable organizationId: String, @PathVariable workflow: String,
                   @RequestParam(defaultValue = "") query: String,
                   @RequestParam(defaultValue = "50") limit: Int): ResponseEntity<PayrollReviewerCandidatesResponse> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.candidates(actor(request), uuid(organizationId), parseWorkflow(workflow), query, limit))

    @PostMapping("/v1/organizations/{organizationId}/administration/review-workflows/{workflow}")
    fun configure(servletRequest: HttpServletRequest, @AuthenticationPrincipal jwt: Jwt, @PathVariable organizationId: String, @PathVariable workflow: String,
                  @RequestHeader("Idempotency-Key") idempotencyKey: String,
                  @RequestHeader("X-Device-Installation-Id") installationId: String,
                  @RequestBody request: ConfigurePayrollReviewPolicyRequest): ResponseEntity<ConfigurePayrollReviewPolicyResponse> {
        val actor = actor(servletRequest)
        val operation = uuid(request.operationId)
        IdempotencyKeyContract.requireMatches(idempotencyKey, operation)
        sessions.requireRecentReauthentication(sessions.requireCurrentAccess(jwt, uuid(installationId)))
        val result = service.configure(ConfigurePayrollReviewPolicyCommand(
            operation, uuid(organizationId), uuid(request.reviewerEmployeeId), uuid(request.expectedReviewerIdentityId),
            request.baseEmployeeVersion, request.expectedPolicyId?.let(::uuid), request.expectedPolicyVersion,
            request.expectedBindingId?.let(::uuid), request.expectedBindingVersion,
            actor, HiltechRequestContext.current(servletRequest).correlationId, parseWorkflow(workflow),
            request.pendingPoliciesToSupersede, request.supersedeReason,
        ))
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result)
    }
    private fun actor(request: HttpServletRequest): UUID = HiltechRequestContext.current(request).identityId?.let(::uuid)
        ?: throw ProductApiException("UNAUTHENTICATED", "Authentication is required.", HttpStatus.UNAUTHORIZED)
    private fun uuid(value: String): UUID = runCatching { UUID.fromString(value) }.getOrElse {
        throw ProductApiException("PAYROLL_REVIEW_SETUP_INVALID_IDENTIFIER", "A setup identifier is invalid.", HttpStatus.BAD_REQUEST)
    }
    private fun parseWorkflow(value: String): CompanyReviewWorkflow = CompanyReviewWorkflow.entries.singleOrNull { it.name == value }
        ?: throw ProductApiException("COMPANY_REVIEW_WORKFLOW_UNKNOWN", "The review workflow is not in the supported catalog.", HttpStatus.BAD_REQUEST)
}

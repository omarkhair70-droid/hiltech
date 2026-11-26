package com.hiltech.server.payroll

import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class PayrollApprovalRequestDto(
    val operationId: String,
    val reasonCode: String,
)

data class PayrollApprovalRequestResultDto(
    val approvalRequired: Boolean,
    val approvalRequestId: String?,
    val approvalState: String?,
    val policyVersion: Int,
    val replayed: Boolean,
)

/**
 * Company-configured exception routing through the shared ApprovalEngine.
 * This does NOT mark the draft paid, issue salary funds or create an approval
 * shortcut. Routine policy may say no separate owner approval is required.
 */
@RestController
@ConditionalOnProperty(
    prefix = "hiltech.payroll",
    name = ["approval-request-enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class Payroll9bApprovalController(
    private val drafts: Payroll9bDraftService,
    private val authority: Payroll9aAuthorityPort,
    private val guard: PayrollRunApprovalVersionSource,
    private val approvals: ApprovalEngineService,
    @Value("\${hiltech.payroll.run-approval-policy-key:PAYROLL_RUN_REVIEW}") policyKey: String,
) {
    private val policyKey = payrollRunPolicyKey(policyKey)

    @PostMapping("/v1/payroll/runs/{runId}/approval-requests")
    fun requestReview(
        servletRequest: HttpServletRequest,
        @PathVariable runId: String,
        @RequestHeader(name = "Idempotency-Key") key: String,
        @RequestBody request: PayrollApprovalRequestDto,
    ): PayrollApprovalRequestResultDto {
        val ctx = HiltechRequestContext.current(servletRequest)
        val actor = ctx.identityId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ProductApiException(
                "PAYROLL_AUTHENTICATION_REQUIRED", "Authentication required", HttpStatus.UNAUTHORIZED,
            )
        val operation = runCatching { UUID.fromString(request.operationId) }.getOrElse {
            throw ProductApiException("INVALID_PAYROLL_UUID", "Invalid operation", HttpStatus.BAD_REQUEST)
        }
        IdempotencyKeyContract.requireMatches(key, operation)
        val runIdParsed = runCatching { UUID.fromString(runId) }.getOrElse {
            throw ProductApiException("INVALID_PAYROLL_UUID", "Invalid run", HttpStatus.BAD_REQUEST)
        }
        val draft = drafts.read(actor, runIdParsed)
        val org = UUID.fromString(draft.organizationId)
        if ((!authority.canPrepare(actor, org) && !authority.canReview(actor, org)) || policyKey.isBlank()) {
            Payroll9aPolicy.fail("PAYROLL_APPROVAL_NOT_CONFIGURED", HttpStatus.FORBIDDEN)
        }
        val subject = ApprovalSubjectRef(
            organizationId = org,
            subjectType = "PAYROLL_RUN_VERSION",
            subjectId = runIdParsed,
            subjectVersion = 1L,
        )
        if (!guard.isCurrent(subject)) {
            Payroll9aPolicy.fail("PAYROLL_APPROVAL_SOURCE_STALE", HttpStatus.CONFLICT)
        }
        val result = approvals.requestException(
            actorUserId = actor,
            operationId = operation,
            subject = subject,
            policyKey = policyKey,
            reasonCode = request.reasonCode,
            safeReasonSummary = null,
            correlationId = ctx.correlationId,
            allowAuthorityBypass = false,
        )
        return PayrollApprovalRequestResultDto(
            approvalRequired = result.approvalRequired,
            approvalRequestId = result.approvalRequestId?.toString(),
            approvalState = result.state?.name,
            policyVersion = result.policyVersion,
            replayed = result.replayed,
        )
    }
}

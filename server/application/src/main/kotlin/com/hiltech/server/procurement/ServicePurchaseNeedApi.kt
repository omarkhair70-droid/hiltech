package com.hiltech.server.procurement

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Internal-only, default-deny non-stock service demand. No supplier writes. */
@RestController
class ServicePurchaseNeedController(private val service: ServicePurchaseNeedService) {
    @GetMapping("/v1/service-procurement/teams")
    fun teams(request: HttpServletRequest, @RequestParam organizationId: UUID):
        List<ServicePurchaseTeamOption> = service.teams(request.actor(),organizationId)

    @PostMapping("/v1/service-procurement/teams/{teamId}/needs")
    fun create(request: HttpServletRequest, @PathVariable teamId: UUID,
               @RequestBody input: CreateServicePurchaseNeedRequest):
        ServicePurchaseNeedReceipt =
        service.create(request.context(input.operationId),teamId,input)

    @GetMapping("/v1/service-procurement/teams/{teamId}/needs")
    fun list(request: HttpServletRequest, @PathVariable teamId: UUID):
        List<ServicePurchaseNeedView> = service.list(request.actor(),teamId)

    @GetMapping("/v1/service-procurement/needs/{id}")
    fun detail(request: HttpServletRequest, @PathVariable id: UUID):
        ServicePurchaseNeedView = service.detail(request.actor(),id)

    @PostMapping("/v1/service-procurement/needs/{id}/submit")
    fun submit(request: HttpServletRequest, @PathVariable id: UUID,
               @RequestBody input: ServicePurchaseNeedAction):
        ServicePurchaseNeedReceipt =
        service.submit(request.context(input.operationId),id,input)

    @PostMapping("/v1/service-procurement/needs/{id}/withdraw")
    fun withdraw(request: HttpServletRequest, @PathVariable id: UUID,
                 @RequestBody input: ServicePurchaseNeedAction):
        ServicePurchaseNeedReceipt =
        service.withdraw(request.context(input.operationId),id,input)

    @PostMapping("/v1/service-procurement/needs/{id}/revise")
    fun revise(request: HttpServletRequest, @PathVariable id: UUID,
               @RequestBody input: ReviseServicePurchaseNeedRequest):
        ServicePurchaseNeedReceipt =
        service.revise(request.context(input.operationId),id,input)

    @PostMapping("/v1/service-procurement/needs/{id}/technical-review")
    fun review(request: HttpServletRequest, @PathVariable id: UUID,
               @RequestBody input: ServicePurchaseNeedReview):
        ServicePurchaseNeedReceipt =
        service.review(request.context(input.operationId),id,input)

    private fun HttpServletRequest.actor(): UUID =
        HiltechRequestContext.current(this).identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ProductApiException(
                "UNAUTHENTICATED","Authentication is required.",HttpStatus.UNAUTHORIZED,
            )

    private fun HttpServletRequest.context(operationId: UUID): ProcurementDraftContext {
        val ctx = HiltechRequestContext.current(this)
        IdempotencyKeyContract.requireMatches(ctx.idempotencyKey,operationId)
        return ProcurementDraftContext(operationId,actor(),ctx.correlationId)
    }
}

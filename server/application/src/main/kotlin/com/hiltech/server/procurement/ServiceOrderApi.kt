package com.hiltech.server.procurement

import com.hiltech.server.approval.ApprovalRequestResult
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class ServiceOrderController(private val orders:ServiceOrderService) {
    @PostMapping("/v1/service-procurement/order-candidates")
    fun create(req:HttpServletRequest,@RequestBody body:CreateServiceOrderCandidateRequest):
        ServiceOrderCandidateReceipt = orders.create(req.ctx(body.operationId),body)

    @GetMapping("/v1/service-procurement/order-candidates/{id}")
    fun detail(req:HttpServletRequest,@PathVariable id:UUID):ServiceOrderCandidateView =
        orders.view(req.actor(),id)

    @GetMapping("/v1/service-procurement/organizations/{orgId}/order-queue")
    fun queue(req:HttpServletRequest,@PathVariable orgId:UUID):
        List<ServiceOrderCandidateView> = orders.queue(req.actor(),orgId)

    @PostMapping("/v1/service-procurement/order-candidates/{id}/request-approval")
    fun requestApproval(req:HttpServletRequest,@PathVariable id:UUID,
                        @RequestBody input:RequestServiceOrderApprovalRequest):
        ApprovalRequestResult = orders.requestApproval(req.ctx(input.operationId),id,input)

    @PostMapping("/v1/service-procurement/order-candidates/{id}/issue")
    fun issue(req:HttpServletRequest,@PathVariable id:UUID,
              @RequestBody body:IssueServiceOrderRequest):
        IssuedServiceOrderView = orders.issue(req.ctx(body.operationId),id,body)

    @GetMapping("/v1/service-procurement/orders/{id}")
    fun issued(req:HttpServletRequest,@PathVariable id:UUID):IssuedServiceOrderView =
        orders.issued(req.actor(),id)

    private fun HttpServletRequest.actor():UUID =
        HiltechRequestContext.current(this).identityId
            ?.let {runCatching {UUID.fromString(it)}.getOrNull()}
            ?:throw ProductApiException("UNAUTHENTICATED","Authentication required.",
                HttpStatus.UNAUTHORIZED)
    private fun HttpServletRequest.ctx(op:UUID):ProcurementDraftContext {
        val c=HiltechRequestContext.current(this)
        IdempotencyKeyContract.requireMatches(c.idempotencyKey,op)
        return ProcurementDraftContext(op,actor(),c.correlationId)
    }
}

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
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** No stock receipt, supplier email, payment or Finance payable endpoints. */
@RestController
class ServiceDeliveryInvoiceController(private val service:ServiceDeliveryInvoiceService) {
    @GetMapping("/v1/service-procurement/organizations/{orgId}/delivery-orders")
    fun deliveryOrders(req:HttpServletRequest,@PathVariable orgId:UUID):
        List<ServiceWorkOrderOption> = service.ordersForRole(req.actor(),orgId,"DELIVERY")

    @GetMapping("/v1/service-procurement/organizations/{orgId}/invoice-orders")
    fun financeOrders(req:HttpServletRequest,@PathVariable orgId:UUID):
        List<ServiceWorkOrderOption> = service.ordersForRole(req.actor(),orgId,"INVOICE")

    @PostMapping("/v1/service-procurement/orders/{orderId}/document-slots")
    fun documentSlot(req:HttpServletRequest,@PathVariable orderId:UUID,
                     @RequestBody input:CreateServicePurchaseDocumentSlotRequest):
        ServicePurchaseDocumentSlotReceipt =
        service.documentSlot(req.ctx(input.operationId),orderId,input)

    @PostMapping("/v1/service-procurement/orders/{orderId}/delivery-reviews")
    fun delivery(req:HttpServletRequest,@PathVariable orderId:UUID,
                 @RequestBody input:RecordServiceDeliveryReviewRequest):
        ServiceDeliveryReviewView =
        service.reviewDelivery(req.ctx(input.operationId),orderId,input)

    @PostMapping("/v1/service-procurement/orders/{orderId}/invoices")
    fun invoice(req:HttpServletRequest,@PathVariable orderId:UUID,
                @RequestBody input:CaptureServiceInvoiceRequest):
        ServiceInvoiceView =
        service.captureInvoice(req.ctx(input.operationId),orderId,input)

    @GetMapping("/v1/service-procurement/organizations/{orgId}/invoices")
    fun invoices(req:HttpServletRequest,@PathVariable orgId:UUID):List<ServiceInvoiceView> =
        service.invoices(req.actor(),orgId)

    @GetMapping("/v1/service-procurement/invoices/{id}")
    fun invoice(req:HttpServletRequest,@PathVariable id:UUID):ServiceInvoiceView =
        service.invoiceDetail(req.actor(),id)

    @GetMapping("/v1/service-procurement/invoices/{id}/match-preview")
    fun matchPreview(req:HttpServletRequest,@PathVariable id:UUID):ServiceInvoiceMatchPreview =
        service.preview(req.actor(),id)

    @PostMapping("/v1/service-procurement/invoices/{id}/match")
    fun match(req:HttpServletRequest,@PathVariable id:UUID,
              @RequestBody input:RecordServiceInvoiceMatchRequest):ServiceInvoiceMatchPreview =
        service.recordMatch(req.ctx(input.operationId),id,input)

    @PostMapping("/v1/service-procurement/invoices/{id}/finance-review-handoff")
    fun finance(req:HttpServletRequest,@PathVariable id:UUID,
                @RequestBody input:HandOffServiceInvoiceRequest):ServiceInvoiceHandoffReceipt =
        service.handoff(req.ctx(input.operationId),id,input)

    private fun HttpServletRequest.actor():UUID =
        HiltechRequestContext.current(this).identityId
            ?.let {runCatching {UUID.fromString(it)}.getOrNull()}
            ?:throw ProductApiException("UNAUTHENTICATED","Authentication required.",
                HttpStatus.UNAUTHORIZED)
    private fun HttpServletRequest.ctx(operation:UUID):ProcurementDraftContext {
        val c=HiltechRequestContext.current(this)
        IdempotencyKeyContract.requireMatches(c.idempotencyKey,operation)
        return ProcurementDraftContext(operation,actor(),c.correlationId)
    }
}

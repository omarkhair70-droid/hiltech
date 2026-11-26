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

/** Internal service supplier workflow; not material purchasing and not supplier mail. */
@RestController
class ServiceSupplierSourcingController(private val sourcing: ServiceSupplierSourcingService) {
    @GetMapping("/v1/service-procurement/organizations/{orgId}/sourcing-suppliers")
    fun suppliers(request: HttpServletRequest, @PathVariable orgId: UUID,
                  @RequestParam query: String): ServiceSupplierDirectoryView =
        sourcing.searchSuppliers(request.actor(), orgId, query)

    @GetMapping("/v1/service-procurement/organizations/{orgId}/sourcing-eligible-needs")
    fun eligible(request: HttpServletRequest,@PathVariable orgId: UUID):
        List<ServiceEligibleNeedView> = sourcing.eligibleNeeds(request.actor(),orgId)

    @PostMapping("/v1/service-procurement/needs/{id}/rfqs")
    fun open(request: HttpServletRequest,@PathVariable id: UUID,
             @RequestBody data: OpenServiceRfqRequest): ServiceRfqReceipt =
        sourcing.open(request.command(data.operationId),id,data)

    @GetMapping("/v1/service-procurement/needs/{id}/rfqs")
    fun rfqs(request: HttpServletRequest,@PathVariable id: UUID):
        List<ServiceRfqView> = sourcing.rfqs(request.actor(),id)

    @PostMapping("/v1/service-procurement/rfqs/{id}/document-slots")
    fun reserveQuoteSlot(request: HttpServletRequest,@PathVariable id: UUID,
                         @RequestBody data: CreateServiceQuoteSlotRequest):
        ServiceQuoteSlotReceipt =
        sourcing.createSlot(request.command(data.operationId),id,data)

    @PostMapping("/v1/service-procurement/rfqs/{id}/verified-quotes")
    fun recordQuote(request: HttpServletRequest,@PathVariable id: UUID,
                    @RequestBody data: RecordServiceQuoteRequest):
        ServiceQuoteReceipt =
        sourcing.recordQuote(request.command(data.operationId),id,data)

    @GetMapping("/v1/service-procurement/rfqs/{id}/comparison")
    fun comparison(request: HttpServletRequest,@PathVariable id: UUID):
        List<ServiceQuoteView> = sourcing.comparison(request.actor(),id)

    private fun HttpServletRequest.actor():UUID =
        HiltechRequestContext.current(this).identityId
            ?.let {runCatching {UUID.fromString(it)}.getOrNull()}
            ?:throw ProductApiException(
                "UNAUTHENTICATED","Authentication required.",HttpStatus.UNAUTHORIZED)

    private fun HttpServletRequest.command(op:UUID):ProcurementDraftContext {
        val c=HiltechRequestContext.current(this)
        IdempotencyKeyContract.requireMatches(c.idempotencyKey,op)
        return ProcurementDraftContext(op,actor(),c.correlationId)
    }
}

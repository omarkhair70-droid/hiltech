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

@RestController
class OfficeStockNeedController(private val office: OfficeStockNeedService) {
    @GetMapping("/v1/office-stock/teams")
    fun teams(req: HttpServletRequest, @RequestParam organizationId: UUID):
        List<OfficeTeamOption> = office.teams(req.actor(), organizationId)

    @GetMapping("/v1/office-stock/teams/{teamId}/destinations")
    fun destinations(req: HttpServletRequest, @PathVariable teamId: UUID):
        List<OfficeDestinationOption> = office.destinations(req.actor(), teamId)

    @GetMapping("/v1/office-stock/teams/{teamId}/items")
    fun catalog(req: HttpServletRequest, @PathVariable teamId: UUID,
                @RequestParam(required=false,defaultValue="") search: String):
        List<OfficeStockItemOption> = office.stockCatalog(req.actor(), teamId, search)

    @GetMapping("/v1/office-stock/teams/{teamId}/needs")
    fun list(req: HttpServletRequest, @PathVariable teamId: UUID):
        List<OfficeStockNeedView> = office.list(req.actor(), teamId)

    @PostMapping("/v1/office-stock/teams/{teamId}/needs")
    fun create(req: HttpServletRequest,@PathVariable teamId: UUID,
               @RequestBody data: CreateOfficeStockNeedRequest): OfficeStockNeedReceipt =
        office.create(req.command(data.operationId),teamId,data)

    @GetMapping("/v1/office-stock/needs/{id}")
    fun detail(req: HttpServletRequest,@PathVariable id:UUID): OfficeStockNeedView =
        office.detail(req.actor(),id)

    @PostMapping("/v1/office-stock/needs/{id}/submit")
    fun submit(req: HttpServletRequest,@PathVariable id:UUID,
               @RequestBody data:SubmitPurchaseRequirementRequest): OfficeStockNeedReceipt =
        office.submit(req.command(data.operationId),id,data)

    @PostMapping("/v1/office-stock/needs/{id}/revise")
    fun revise(req: HttpServletRequest,@PathVariable id:UUID,
               @RequestBody data:ReviseOfficeStockNeedRequest): OfficeStockNeedReceipt =
        office.revise(req.command(data.operationId),id,data)

    @PostMapping("/v1/office-stock/needs/{id}/withdraw")
    fun withdraw(req: HttpServletRequest,@PathVariable id:UUID,
                 @RequestBody data:WithdrawPurchaseRequirementRequest): OfficeStockNeedReceipt =
        office.withdraw(req.command(data.operationId),id,data)

    @PostMapping("/v1/office-stock/needs/{id}/technical-review")
    fun review(req: HttpServletRequest,@PathVariable id:UUID,
               @RequestBody data:ReviewPurchaseRequirementRequest): OfficeStockNeedReceipt =
        office.review(req.command(data.operationId),id,data)

    private fun HttpServletRequest.actor():UUID =
        HiltechRequestContext.current(this).identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?:throw ProductApiException("UNAUTHENTICATED","Authentication required.",
                HttpStatus.UNAUTHORIZED)
    private fun HttpServletRequest.command(op:UUID):ProcurementDraftContext {
        val c=HiltechRequestContext.current(this)
        IdempotencyKeyContract.requireMatches(c.idempotencyKey,op)
        return ProcurementDraftContext(op,actor(),c.correlationId)
    }
}

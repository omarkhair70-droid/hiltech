package com.hiltech.server.work

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** S04A: assigned actor's own outstanding issued stock, never Warehouse stock. */
@RestController
@RequestMapping("/v1/work-orders")
class FieldPersonalIssuedStockController(
    private val service: FieldPersonalIssuedStockService,
) {
    @GetMapping("/{workOrderId}/my-issued-stock")
    fun mine(
        request: HttpServletRequest,
        @PathVariable workOrderId: UUID,
    ): FieldPersonalIssuedStockSnapshot {
        val actor = HiltechRequestContext.current(request).identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ProductApiException(
                "AUTHENTICATION_REQUIRED",
                "An authenticated identity is required.",
                HttpStatus.UNAUTHORIZED,
            )
        return service.ownIssued(actor, workOrderId)
    }
}

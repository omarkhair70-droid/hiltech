package com.hiltech.server.work

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.util.UUID

data class FieldAssignedAssetAvailabilityView(
    val state: String,
    val reasonCodes: List<String>,
    val calibrationStatus: String,
    val calibrationDueAt: String?,
    val certificateOnFile: Boolean,
    val incidentTypes: List<String>,
)

data class FieldAssignedAssetLookupView(
    val assetCode: String,
    val name: String?,
    val typeName: String,
    val manufacturer: String?,
    val model: String?,
    val serialNumber: String?,
    val condition: String,
    val locationCode: String?,
    val employeeCode: String?,
    val custodySince: String?,
    val availability: FieldAssignedAssetAvailabilityView?,
    val assetId: UUID,
)

fun interface FieldAssignedAssetSourcePort {
    fun lookupForWork(
        actor: UUID,
        opaqueCode: String,
        organizationId: UUID,
        workOrderId: UUID,
    ): FieldAssignedAssetLookupView?
}

/**
 * Field-scoped QR/manual lookup keeps Work authorization in the Work module
 * and delegates canonical Asset/custody verification through a port.
 *
 * The Work module never imports Warehouse implementation types; Warehouse is
 * the adapter because it already depends on Work for resource fulfillment.
 */
@Service
class FieldAssignedAssetLookupService(
    private val source: WorkAssignmentSourceAuthorityPort,
    private val authorization: WorkAssignmentAuthorizationPort,
    private val work: WorkPersistencePort,
    private val assets: FieldAssignedAssetSourcePort,
    private val clock: Clock,
    @Value("\${hiltech.field.assigned-asset-lookup.enabled:false}")
    private val enabled: Boolean = false,
) {
    fun lookup(
        actor: UUID,
        workOrderId: UUID,
        opaqueCode: String,
    ): FieldAssignedAssetLookupView {
        if (!enabled) hidden()
        val now = clock.instant()
        if (
            !source.isCurrentAssignedUser(
                actor,
                workOrderId,
                now,
            ) ||
            !authorization.canUseAssignedWork(
                actor,
                workOrderId,
                "can_view",
            )
        ) {
            hidden()
        }
        val order =
            work.workOrder(workOrderId)
                ?: hidden()
        val code = opaqueCode.trim()
        if (
            !Regex(
                "[A-Za-z0-9_-]{1,128}",
            ).matches(code)
        ) {
            hidden()
        }
        return assets.lookupForWork(
            actor = actor,
            opaqueCode = code,
            organizationId =
                order.organizationId,
            workOrderId =
                order.workOrderId,
        ) ?: hidden()
    }

    private fun hidden(): Nothing =
        throw ProductApiException(
            "FIELD_ASSET_LOOKUP_UNAVAILABLE",
            "The assigned Asset context is not available.",
            HttpStatus.NOT_FOUND,
        )
}

@RestController
@RequestMapping("/v1/field/work-orders")
class FieldAssignedAssetLookupController(
    private val service:
        FieldAssignedAssetLookupService,
) {
    @GetMapping(
        "/{workOrderId}/assets/by-tag/{opaqueCode}",
    )
    fun lookup(
        request: HttpServletRequest,
        @PathVariable
        workOrderId: UUID,
        @PathVariable
        opaqueCode: String,
    ): FieldAssignedAssetLookupView =
        service.lookup(
            request.fieldAssetActor(),
            workOrderId,
            opaqueCode,
        )
}

private fun HttpServletRequest.fieldAssetActor(): UUID {
    val context =
        HiltechRequestContext.current(this)
    return context.identityId
        ?.let {
            runCatching {
                UUID.fromString(it)
            }.getOrNull()
        }
        ?: throw ProductApiException(
            "AUTHENTICATION_REQUIRED",
            "An authenticated identity is required.",
            HttpStatus.UNAUTHORIZED,
        )
}

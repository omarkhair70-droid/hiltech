package com.hiltech.server.work

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.warehouse.AssetPassportPersistence
import com.hiltech.server.warehouse.AssetPassportService
import com.hiltech.server.warehouse.AssetTagLookupView
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

/**
 * Field-scoped QR/manual lookup deliberately narrows the existing Asset lookup.
 * It does not add a field Asset permission: the actor must already pass the
 * canonical AssetPassport lookup AND the Asset must currently belong to the
 * selected assigned WorkOrder context.
 */
@Service
class FieldAssignedAssetLookupService(
    private val source: WorkAssignmentSourceAuthorityPort,
    private val authorization: WorkAssignmentAuthorizationPort,
    private val work: WorkPersistencePort,
    private val assetService: AssetPassportService,
    private val assets: AssetPassportPersistence,
    private val clock: Clock,
    @Value("\${hiltech.field.assigned-asset-lookup.enabled:false}")
    private val enabled: Boolean = false,
) {
    fun lookup(actor: UUID, workOrderId: UUID, opaqueCode: String): AssetTagLookupView {
        if (!enabled) hidden()
        val now = clock.instant()
        if (!source.isCurrentAssignedUser(actor, workOrderId, now) ||
            !authorization.canUseAssignedWork(actor, workOrderId, "can_view")) {
            hidden()
        }
        val order = work.workOrder(workOrderId) ?: hidden()
        val code = opaqueCode.trim()

        // Reuse the existing safe Asset authorization / availability projection.
        // This intentionally does not grant field staff warehouse or Asset rights.
        val result = assetService.lookup(actor, code)
        val identity = assets.asset(result.assetId) ?: hidden()
        val custody = assets.custody(result.assetId) ?: hidden()
        if (identity.organizationId != order.organizationId ||
            custody.projectId != order.projectId ||
            custody.siteId != order.siteId ||
            custody.workOrderId != order.workOrderId) {
            hidden()
        }

        // Fail closed if the tag or custody changed between the canonical lookup
        // and the WorkOrder-scope check.
        if (assets.activeTagAsset(code) != identity.id ||
            assets.custody(identity.id)?.sourceMovementId != custody.sourceMovementId) {
            hidden()
        }

        // Field lookup needs safe identity/availability, not another employee's
        // code or a warehouse/bin location.
        return result.copy(
            locationCode = null,
            employeeCode = null,
            custodySince = null,
        )
    }

    private fun hidden(): Nothing = throw ProductApiException(
        "FIELD_ASSET_LOOKUP_UNAVAILABLE",
        "The assigned Asset context is not available.",
        HttpStatus.NOT_FOUND,
    )
}

@RestController
@RequestMapping("/v1/field/work-orders")
class FieldAssignedAssetLookupController(
    private val service: FieldAssignedAssetLookupService,
) {
    @GetMapping("/{workOrderId}/assets/by-tag/{opaqueCode}")
    fun lookup(
        request: HttpServletRequest,
        @PathVariable workOrderId: UUID,
        @PathVariable opaqueCode: String,
    ): AssetTagLookupView = service.lookup(request.fieldAssetActor(), workOrderId, opaqueCode)
}

private fun HttpServletRequest.fieldAssetActor(): UUID {
    val context = HiltechRequestContext.current(this)
    return context.identityId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw ProductApiException(
            "AUTHENTICATION_REQUIRED",
            "An authenticated identity is required.",
            HttpStatus.UNAUTHORIZED,
        )
}

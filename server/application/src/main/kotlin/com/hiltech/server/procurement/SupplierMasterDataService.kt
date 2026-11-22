package com.hiltech.server.procurement

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

/**
 * Maintaining the authorized supplier register is distinct from being able
 * to request quotes, review technical quality or approve expenditure.
 */
interface SupplierMasterDataAuthorizationPort {
    fun canMaintainSupplierLinks(actor: UUID, purchasingOrganizationId: UUID): Boolean
}

/** No user, role or supplier is granted master-data authority by default. */
@Component
class DenyAllSupplierMasterDataAuthorization : SupplierMasterDataAuthorizationPort {
    override fun canMaintainSupplierLinks(
        actor: UUID,
        purchasingOrganizationId: UUID,
    ): Boolean = false
}

data class ChangeSupplierLinkRequest(
    val operationId: UUID,
    val supplierOrganizationId: UUID,
    val expectedRevisionNumber: Int,
    val state: String,
    /** Operator-attested reference; validation against company records is a rollout gate. */
    val sourceReference: String,
)

data class SupplierLinkEventReceipt(
    val eventId: UUID,
    val organizationId: UUID,
    val supplierOrganizationId: UUID,
    val revisionNumber: Int,
    val state: String,
    val replayed: Boolean,
)

@Service
class SupplierMasterDataService @Autowired constructor(
    private val sourcing: SupplierSourcingPersistence,
    private val authority: SupplierMasterDataAuthorizationPort,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
    @Value("\${hiltech.procurement.sourcing.enabled:false}")
    private val sourcingEnabled: Boolean = false,
    @Value("\${hiltech.procurement.supplier-master-data.enabled:false}")
    private val masterDataEnabled: Boolean = false,
) {
    fun changeSupplierLink(
        ctx: ProcurementDraftContext,
        purchasingOrganizationId: UUID,
        request: ChangeSupplierLinkRequest,
    ): SupplierLinkEventReceipt {
        requireAuthority(ctx.actor, purchasingOrganizationId)
        val state = request.state.trim().uppercase()
        val sourceReference = request.sourceReference.trim()
        if (request.operationId != ctx.operationId ||
            request.expectedRevisionNumber < 1 ||
            request.supplierOrganizationId == purchasingOrganizationId ||
            state !in setOf("ACTIVE", "INACTIVE") ||
            sourceReference.length !in 8..240 ||
            sourceReference.any { it.code < 32 }
        ) invalid()

        val eventId = UUID.nameUUIDFromBytes(
            "hiltech:procurement:supplier-link:${ctx.operationId}"
                .toByteArray(Charsets.UTF_8),
        )
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(
            listOf(
                purchasingOrganizationId,
                request.supplierOrganizationId,
                request.expectedRevisionNumber,
                state,
                sourceReference,
            ).joinToString("\u001f").toByteArray(Charsets.UTF_8),
        ).joinToString("") { "%02x".format(it) }

        val execution = commands.execute(
            IdempotentCommandSpec(
                ctx.operationId,
                ctx.actor,
                "PROCUREMENT_SUPPLIER_LINK_RECORDED",
                "SUPPLIER_LINK_EVENT",
                eventId,
                fingerprint,
                ctx.correlationId,
            ),
        ) {
            if (!sourcing.lockActiveOrganizationForSupplierLinks(purchasingOrganizationId)) {
                unavailable()
            }
            requireAuthority(ctx.actor, purchasingOrganizationId)
            val supplier = sourcing.supplierTypeAndStatus(request.supplierOrganizationId)
                ?: unavailable()
            if (supplier.first != "SUPPLIER" ||
                (state == "ACTIVE" && supplier.second != "ACTIVE")
            ) unavailable()

            val previous = sourcing.currentSupplierLink(
                purchasingOrganizationId, request.supplierOrganizationId,
            )
            val revision = (previous?.revisionNumber ?: 0) + 1
            if (request.expectedRevisionNumber != revision || previous?.state == state ||
                (state == "INACTIVE" && previous?.state != "ACTIVE")
            ) conflict()

            val at = clock.instant()
            sourcing.insertSupplierLinkEvent(
                SupplierLinkEventRecord(
                    eventId,
                    ctx.operationId,
                    purchasingOrganizationId,
                    request.supplierOrganizationId,
                    revision,
                    state,
                    sourceReference,
                    ctx.actor,
                    at,
                    ctx.correlationId,
                ),
            )
            audit.append(
                AuditEventRecord(
                    actorUserId = ctx.actor,
                    action = "PROCUREMENT_SUPPLIER_LINK_RECORDED",
                    targetType = "SUPPLIER_LINK_EVENT",
                    targetId = eventId,
                    occurredAt = at,
                    correlationId = ctx.correlationId,
                    reason = "SUPPLIER_LINK_$state",
                ),
            )
            IdempotentCommandOutcome(
                "PROCUREMENT_SUPPLIER_LINK_RECORDED",
                """{"eventId":"$eventId"}""",
            )
        }
        return SupplierLinkEventReceipt(
            eventId,
            purchasingOrganizationId,
            request.supplierOrganizationId,
            request.expectedRevisionNumber,
            state,
            execution.replayed,
        )
    }

    private fun requireAuthority(actor: UUID, organizationId: UUID) {
        if (!sourcingEnabled || !masterDataEnabled ||
            !authority.canMaintainSupplierLinks(actor, organizationId)
        ) unavailable()
    }

    private fun unavailable(): Nothing = throw ProductApiException(
        "SUPPLIER_MASTER_DATA_UNAVAILABLE",
        "Supplier register operation is not available.",
        HttpStatus.NOT_FOUND,
    )

    private fun invalid(): Nothing = throw ProductApiException(
        "INVALID_SUPPLIER_LINK_EVENT",
        "Invalid supplier register event.",
        HttpStatus.BAD_REQUEST,
    )

    private fun conflict(): Nothing = throw ProductApiException(
        "SUPPLIER_LINK_REVISION_CONFLICT",
        "Supplier relationship changed; refresh its current revision.",
        HttpStatus.CONFLICT,
    )
}

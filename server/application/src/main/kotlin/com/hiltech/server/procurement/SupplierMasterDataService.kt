package com.hiltech.server.procurement

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.ProductApiException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
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

data class ChangeSupplierLinkRequest(
    val operationId: UUID,
    val supplierOrganizationId: UUID,
    val expectedRevisionNumber: Int,
    val state: String,
    /** Human reason or source reference, recorded as an operator attestation. */
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

data class SupplierRegistryCapabilities(val organizationId: UUID, val available: Boolean, val canMaintain: Boolean)
data class CreateSupplierRequest(val operationId: UUID, val profile: SupplierProfileData)
data class UpdateSupplierProfileRequest(val operationId: UUID, val baseVersion: Long, val profile: SupplierProfileData)
data class SupplierProfileReceipt(
    val operationId: UUID,
    val organizationId: UUID,
    val supplierOrganizationId: UUID,
    val version: Long,
    val action: String,
    val replayed: Boolean,
)

@Service
class SupplierMasterDataService @Autowired constructor(
    private val sourcing: SupplierSourcingPersistence,
    private val registry: SupplierRegistryPersistence,
    private val authority: SupplierMasterDataAuthorizationPort,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
    @Value("\${hiltech.procurement.supplier-master-data.enabled:true}")
    private val masterDataEnabled: Boolean = true,
) {
    fun capabilities(actor: UUID, org: UUID) = SupplierRegistryCapabilities(
        org, masterDataEnabled, masterDataEnabled && authority.canMaintainSupplierLinks(actor, org),
    )

    /** Only an explicitly authorized supplier-registry custodian can search. */
    fun supplierRegistry(
        actor: UUID,
        purchasingOrganizationId: UUID,
        nameContains: String,
    ): List<SupplierRegistryCandidate> {
        requireAuthority(actor, purchasingOrganizationId)
        val search = nameContains.trim()
        if (search.length > 80 || search.any { it.code < 32 }) invalid()
        return sourcing.supplierRegistryCandidates(purchasingOrganizationId, search)
    }

    fun supplierDetail(actor: UUID, org: UUID, supplier: UUID): SupplierRegistryDetail {
        requireAuthority(actor, org)
        return registry.detail(org, supplier) ?: unavailable()
    }

    fun supplierHistory(actor: UUID, org: UUID, supplier: UUID): List<SupplierRegistryHistoryEntry> {
        supplierDetail(actor, org, supplier)
        return registry.history(org, supplier)
    }

    fun createSupplier(ctx: ProcurementDraftContext, org: UUID, request: CreateSupplierRequest): SupplierProfileReceipt {
        requireAuthority(ctx.actor, org)
        if (request.operationId != ctx.operationId) invalidProfile()
        val profile = normalizedProfile(request.profile)
        val supplier = commandId("supplier", ctx.operationId)
        val eventId = commandId("supplier-profile", ctx.operationId)
        val execution = commands.execute(IdempotentCommandSpec(
            ctx.operationId, ctx.actor, "PROCUREMENT_SUPPLIER_CREATED", "SUPPLIER_PROFILE", supplier,
            profileFingerprint(org, supplier, 0, profile), ctx.correlationId,
        )) {
            lockAndAuthorize(ctx.actor, org)
            val at = clock.instant()
            registry.insertIdentity(supplier, profile, at)
            val code = registry.reserveSupplierCode(org)
            check(registry.saveProfile(org, supplier, 0, profile, ctx.actor, at, code))
            registry.appendProfileEvent(eventId, ctx, org, supplier, 1, at)
            sourcing.insertSupplierLinkEvent(SupplierLinkEventRecord(
                commandId("supplier-link", ctx.operationId), ctx.operationId, org, supplier, 1, "ACTIVE",
                "REGISTERED_IN_APPLICATION", ctx.actor, at, ctx.correlationId,
            ))
            audit.append(AuditEventRecord(
                actorUserId = ctx.actor, action = "PROCUREMENT_SUPPLIER_CREATED", targetType = "SUPPLIER_PROFILE",
                targetId = eventId, occurredAt = at, correlationId = ctx.correlationId, reason = "SUPPLIER_REGISTERED",
            ))
            IdempotentCommandOutcome("PROCUREMENT_SUPPLIER_CREATED", """{"supplierOrganizationId":"$supplier","version":1}""")
        }
        return SupplierProfileReceipt(ctx.operationId, org, supplier, 1, "CREATED", execution.replayed)
    }

    fun updateSupplierProfile(
        ctx: ProcurementDraftContext, org: UUID, supplier: UUID, request: UpdateSupplierProfileRequest,
    ): SupplierProfileReceipt {
        requireAuthority(ctx.actor, org)
        if (request.operationId != ctx.operationId || request.baseVersion < 0 || request.baseVersion == Long.MAX_VALUE) invalidProfile()
        val profile = normalizedProfile(request.profile)
        val version = request.baseVersion + 1
        val eventId = commandId("supplier-profile", ctx.operationId)
        val execution = commands.execute(IdempotentCommandSpec(
            ctx.operationId, ctx.actor, "PROCUREMENT_SUPPLIER_PROFILE_SAVED", "SUPPLIER_PROFILE", supplier,
            profileFingerprint(org, supplier, request.baseVersion, profile), ctx.correlationId,
        )) {
            lockAndAuthorize(ctx.actor, org)
            val current = registry.detail(org, supplier) ?: unavailable()
            if (current.version != request.baseVersion) profileConflict()
            val at = clock.instant()
            if (!registry.saveProfile(org, supplier, request.baseVersion, profile, ctx.actor, at)) profileConflict()
            registry.appendProfileEvent(eventId, ctx, org, supplier, version, at)
            audit.append(AuditEventRecord(
                actorUserId = ctx.actor, action = "PROCUREMENT_SUPPLIER_PROFILE_SAVED", targetType = "SUPPLIER_PROFILE",
                targetId = eventId, occurredAt = at, correlationId = ctx.correlationId, reason = "SUPPLIER_PROFILE_CORRECTED",
            ))
            IdempotentCommandOutcome("PROCUREMENT_SUPPLIER_PROFILE_SAVED", """{"supplierOrganizationId":"$supplier","version":$version}""")
        }
        return SupplierProfileReceipt(ctx.operationId, org, supplier, version, "UPDATED", execution.replayed)
    }

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
            // A guessed global ID cannot import another company's supplier.
            registry.detail(purchasingOrganizationId, request.supplierOrganizationId) ?: unavailable()
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
        if (!masterDataEnabled ||
            !authority.canMaintainSupplierLinks(actor, organizationId)
        ) unavailable()
    }

    private fun lockAndAuthorize(actor: UUID, org: UUID) {
        if (!sourcing.lockActiveOrganizationForSupplierLinks(org)) unavailable()
        requireAuthority(actor, org)
    }

    private fun commandId(kind: String, operation: UUID): UUID = UUID.nameUUIDFromBytes(
        "hiltech:procurement:$kind:$operation".toByteArray(Charsets.UTF_8),
    )

    private fun normalizedProfile(value: SupplierProfileData): SupplierProfileData {
        fun field(text: String?, limit: Int, required: Boolean = false, multiline: Boolean = false): String? {
            val trimmed = text?.trim()?.takeIf { it.isNotEmpty() }
            if ((required && trimmed == null) || (trimmed != null && (trimmed.length > limit ||
                    trimmed.any { it.code < 32 && !(multiline && it == '\n') || it.code == 127 }))) invalidProfile()
            return trimmed
        }
        val email = field(value.email, 254)
        if (email != null && !Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(email)) invalidProfile()
        return SupplierProfileData(
            field(value.legalName, 240, true)!!, field(value.displayName, 160, true)!!,
            field(value.contactName, 160), email, field(value.phone, 40), field(value.taxReference, 80), field(value.address, 500, multiline = true),
        )
    }

    private fun profileFingerprint(org: UUID, supplier: UUID, version: Long, profile: SupplierProfileData): String =
        IdempotencyKeyContract.fingerprint(listOf(
            org, supplier, version, profile.legalName, profile.displayName, profile.contactName.orEmpty(),
            profile.email.orEmpty(), profile.phone.orEmpty(), profile.taxReference.orEmpty(), profile.address.orEmpty(),
        ).joinToString("\u001f"))

    private fun invalidProfile(): Nothing = throw ProductApiException(
        "INVALID_SUPPLIER_PROFILE", "Review the supplier name and contact details.", HttpStatus.BAD_REQUEST,
    )

    private fun profileConflict(): Nothing = throw ProductApiException(
        "SUPPLIER_PROFILE_VERSION_CONFLICT", "Supplier details changed; refresh before saving.", HttpStatus.CONFLICT,
    )

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

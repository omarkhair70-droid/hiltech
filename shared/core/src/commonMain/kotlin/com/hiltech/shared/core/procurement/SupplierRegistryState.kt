package com.hiltech.shared.core.procurement

import com.hiltech.shared.core.network.HiltechApiException
import kotlinx.serialization.Serializable

/** Full frozen semantic request, retained until receipt or a confirmed first rejection. */
@Serializable
data class SupplierRegistryIntent(
    val operationId: String,
    val organizationId: String,
    val action: String,
    val displayName: String,
    val supplierId: String? = null,
    val profile: SupplierProfileDto? = null,
    val baseVersion: Long = 0,
    val relationshipRevision: Int = 0,
    val relationshipState: String? = null,
    val reason: String? = null,
)

data class SupplierRegistryAcknowledgement(val supplierId: String, val action: String, val replayed: Boolean)

suspend fun SupplierMasterDataApiClient.applyRegistryIntent(
    intent: SupplierRegistryIntent,
    installationId: String,
): SupplierRegistryAcknowledgement = when (intent.action) {
    "CREATE" -> createSupplier(intent.organizationId, CreateSupplierDto(intent.operationId, requireNotNull(intent.profile)), installationId)
        .let { SupplierRegistryAcknowledgement(it.supplierOrganizationId, "CREATED", it.replayed) }
    "UPDATE" -> updateProfile(intent.organizationId, requireNotNull(intent.supplierId),
        UpdateSupplierProfileDto(intent.operationId, intent.baseVersion, requireNotNull(intent.profile)), installationId)
        .let { SupplierRegistryAcknowledgement(it.supplierOrganizationId, "UPDATED", it.replayed) }
    "LINK" -> changeSupplierLink(intent.organizationId, ChangeSupplierLinkDto(
        intent.operationId, requireNotNull(intent.supplierId), intent.relationshipRevision,
        requireNotNull(intent.relationshipState), requireNotNull(intent.reason),
    ), installationId).let { SupplierRegistryAcknowledgement(it.supplierOrganizationId, it.state, it.replayed) }
    else -> error("Unknown supplier registry action")
}

fun normalizedSupplierProfile(value: SupplierProfileDto): SupplierProfileDto {
    fun optional(text: String?) = text?.trim()?.takeIf { it.isNotEmpty() }
    val display = value.displayName.trim()
    return value.copy(
        displayName = display, legalName = optional(value.legalName) ?: display,
        contactName = optional(value.contactName), email = optional(value.email), phone = optional(value.phone),
        taxReference = optional(value.taxReference), address = optional(value.address),
    )
}

/** Field keys, not translated text; the native form explains each issue beside its field. */
fun supplierProfileProblems(value: SupplierProfileDto): Set<String> {
    val profile = normalizedSupplierProfile(value)
    fun invalid(text: String?, max: Int, required: Boolean = false, multiline: Boolean = false) =
        (required && text.isNullOrEmpty()) || (text != null && (text.length > max ||
            text.any { it.code < 32 && !(multiline && it == '\n') || it.code == 127 }))
    return buildSet {
        if (invalid(profile.displayName, 160, true)) add("displayName")
        if (invalid(profile.legalName, 240, true)) add("legalName")
        if (invalid(profile.contactName, 160)) add("contactName")
        if (invalid(profile.email, 254) || (profile.email != null &&
                !Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(profile.email))) add("email")
        if (invalid(profile.phone, 40)) add("phone")
        if (invalid(profile.taxReference, 80)) add("taxReference")
        if (invalid(profile.address, 500, multiline = true)) add("address")
    }
}

data class SupplierRegistryFailureDisposition(val hasUnknownOutcome: Boolean, val rejectionConfirmed: Boolean)

fun supplierRegistryFailureDisposition(hadUnknownOutcome: Boolean, failure: Throwable): SupplierRegistryFailureDisposition {
    val api = failure as? HiltechApiException
    val explicitRejection = api != null && api.httpStatus?.let { it in 400..499 } == true &&
        api.envelope?.code == api.code && !api.retryable && api.code != "IDEMPOTENCY_KEY_REUSED"
    val storedFailure = explicitRejection && api?.httpStatus == 409 && api.code == "OPERATION_PREVIOUSLY_FAILED"
    return SupplierRegistryFailureDisposition(
        hasUnknownOutcome = !storedFailure && (hadUnknownOutcome || !explicitRejection),
        rejectionConfirmed = explicitRejection && (!hadUnknownOutcome || storedFailure),
    )
}

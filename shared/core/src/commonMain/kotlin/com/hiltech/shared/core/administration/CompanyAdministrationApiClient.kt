package com.hiltech.shared.core.administration

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import com.hiltech.shared.core.people.CompanyResponsibilitiesApiClient
import com.hiltech.shared.core.people.EmployeeResponsibilityTargetDto
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val companyReviewWorkflows = setOf("PAYROLL_SOURCE", "PAYROLL_RUN", "FINANCE_PAYROLL_RESULT", "PROCUREMENT_PO", "SERVICE_HANDOVER", "SERVICE_WARRANTY")

@Serializable
data class CompanyAdministrationCapabilitiesDto(
    val organizationId: String, val canManageResponsibilities: Boolean, val canViewEmployeeResponsibilities: Boolean,
    val canManageApprovalPolicies: Boolean, val managedAuthorityKeys: List<String>, val delegableAuthorityKeys: List<String>,
    val ownPeopleAdministrationStatus: String = "NOT_DELEGATED",
    val canManageCompanySettings: Boolean = false,
) {
    val available: Boolean get() = canViewEmployeeResponsibilities || canManageApprovalPolicies || canManageCompanySettings
}
@Serializable
data class ResponsibilityEmployeeDirectoryDto(val items: List<EmployeeResponsibilityTargetDto>, val hasMore: Boolean)

@Serializable
data class OwnPeopleAdministrationIntent(val operationId: String, val organizationId: String)
@Serializable
private data class ActivateOwnPeopleAdministrationRequest(val operationId: String)
@Serializable
data class OwnPeopleAdministrationReceiptDto(
    val operationId: String, val organizationId: String, val identityId: String, val authorityKey: String,
    val bindingId: String, val current: Boolean, val accessReady: Boolean, val version: Long, val replayed: Boolean,
)

@Serializable
data class CompanyReviewSetupDto(
    val organizationId: String, val status: String, val policyId: String?, val policyRevision: Int?, val policyVersion: Long?,
    val bindingId: String?, val bindingVersion: Long?, val reviewerPrincipalType: String?, val reviewerDisplayName: String?,
    val reviewerEmployeeId: String?, val pendingReviews: Int, val asOf: String, val workflow: String,
    val canConfigure: Boolean = true,
    val pendingPolicies: List<CompanyPendingReviewPolicyDto> = emptyList(),
)
@Serializable
data class CompanyPendingReviewPolicyDto(val policyId: String, val policyRevision: Int, val policyVersion: Long, val pendingReviews: Int)
@Serializable
data class CompanyReviewerCandidateDto(
    val employeeId: String, val identityId: String, val employeeVersion: Long, val displayName: String, val employeeCode: String,
)
@Serializable
data class CompanyReviewerCandidatesDto(val items: List<CompanyReviewerCandidateDto>, val hasMore: Boolean)
@Serializable
data class CompanyReviewSetupIntent(
    val operationId: String, val organizationId: String, val workflow: String,
    val reviewerEmployeeId: String, val expectedReviewerIdentityId: String, val baseEmployeeVersion: Long,
    val reviewerDisplayName: String, val expectedPolicyId: String?, val expectedPolicyVersion: Long?,
    val expectedBindingId: String?, val expectedBindingVersion: Long?, val pendingReviews: Int,
    val pendingPoliciesToSupersede: List<CompanyPendingReviewPolicyDto> = emptyList(),
    val supersedeReason: String? = null,
)
@Serializable
private data class ConfigureCompanyReviewRequest(
    val operationId: String, val reviewerEmployeeId: String, val expectedReviewerIdentityId: String, val baseEmployeeVersion: Long,
    val expectedPolicyId: String?, val expectedPolicyVersion: Long?, val expectedBindingId: String?, val expectedBindingVersion: Long?,
    val pendingPoliciesToSupersede: List<CompanyPendingReviewPolicyDto> = emptyList(),
    val supersedeReason: String? = null,
)
@Serializable
data class CompanyReviewSetupReceiptDto(
    val operationId: String, val organizationId: String, val policyId: String, val policyRevision: Int,
    val bindingId: String, val current: Boolean, val replayed: Boolean, val workflow: String,
)

class CompanyAdministrationApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    val responsibilities = CompanyResponsibilitiesApiClient(api, json)
    val operationalSettings = CompanyOperationalSettingsApiClient(api, json)

    suspend fun capabilities(organizationId: String, installationId: String): CompanyAdministrationCapabilitiesDto {
        requireUuid(organizationId)
        return api.request(HttpMethod.Get, "/v1/organizations/$organizationId/administration/capabilities", HiltechRequestOptions(installationId),
            decode = { json.decodeFromString(CompanyAdministrationCapabilitiesDto.serializer(), it) },
        ).also { require(it.organizationId == organizationId) }
    }

    suspend fun employees(organizationId: String, query: String, installationId: String): ResponsibilityEmployeeDirectoryDto {
        requireUuid(organizationId)
        require(query.length <= 120)
        return api.request(HttpMethod.Get, "/v1/organizations/$organizationId/administration/employees?query=${query.encodeURLParameter()}&limit=50",
            HiltechRequestOptions(installationId), decode = { json.decodeFromString(ResponsibilityEmployeeDirectoryDto.serializer(), it) })
    }
    suspend fun activateOwnPeopleAdministration(intent: OwnPeopleAdministrationIntent, installationId: String,
        onCommandSending: () -> Unit = {}): OwnPeopleAdministrationReceiptDto {
        requireUuid(intent.organizationId)
        requireUuid(intent.operationId)
        val body = json.encodeToString(ActivateOwnPeopleAdministrationRequest.serializer(), ActivateOwnPeopleAdministrationRequest(intent.operationId))
        onCommandSending()
        return api.request(HttpMethod.Post, "/v1/organizations/${intent.organizationId}/administration/own-people-administration",
            HiltechRequestOptions(installationId, intent.operationId), body,
            decode = { json.decodeFromString(OwnPeopleAdministrationReceiptDto.serializer(), it) },
        ).also { require(it.operationId == intent.operationId && it.organizationId == intent.organizationId &&
            it.authorityKey == "PEOPLE_ADMIN" && it.version >= 1) }
    }

    suspend fun reviewSetup(organizationId: String, workflow: String, installationId: String): CompanyReviewSetupDto =
        api.request(HttpMethod.Get, path(organizationId, workflow), HiltechRequestOptions(installationId),
            decode = { json.decodeFromString(CompanyReviewSetupDto.serializer(), it) },
        ).also { require(it.organizationId == organizationId && it.workflow == workflow) }

    suspend fun reviewerCandidates(organizationId: String, workflow: String, query: String, installationId: String): CompanyReviewerCandidatesDto {
        require(query.length <= 120)
        return api.request(HttpMethod.Get, path(organizationId, workflow) + "/reviewer-candidates?query=${query.encodeURLParameter()}&limit=50",
            HiltechRequestOptions(installationId), decode = { json.decodeFromString(CompanyReviewerCandidatesDto.serializer(), it) })
    }

    suspend fun configureReview(intent: CompanyReviewSetupIntent, installationId: String,
                                onCommandSending: () -> Unit = {}): CompanyReviewSetupReceiptDto {
        listOf(intent.operationId, intent.reviewerEmployeeId, intent.expectedReviewerIdentityId).forEach(::requireUuid)
        intent.expectedPolicyId?.let(::requireUuid)
        intent.expectedBindingId?.let(::requireUuid)
        require(intent.baseEmployeeVersion > 0 && (intent.expectedPolicyId == null) == (intent.expectedPolicyVersion == null) &&
            (intent.expectedBindingId == null) == (intent.expectedBindingVersion == null))
        if (intent.pendingPoliciesToSupersede.isNotEmpty()) {
            require(intent.supersedeReason.orEmpty().trim().length in 3..1000)
            require(intent.pendingPoliciesToSupersede.map { it.policyId }.distinct().size == intent.pendingPoliciesToSupersede.size)
            intent.pendingPoliciesToSupersede.forEach { pending ->
                requireUuid(pending.policyId)
                require(pending.policyRevision > 0 && pending.policyVersion > 0 && pending.pendingReviews > 0)
            }
            require(intent.pendingPoliciesToSupersede.sumOf { it.pendingReviews } == intent.pendingReviews)
        }
        val endpoint = path(intent.organizationId, intent.workflow)
        val body = json.encodeToString(ConfigureCompanyReviewRequest.serializer(), ConfigureCompanyReviewRequest(
            intent.operationId, intent.reviewerEmployeeId, intent.expectedReviewerIdentityId, intent.baseEmployeeVersion,
            intent.expectedPolicyId, intent.expectedPolicyVersion, intent.expectedBindingId, intent.expectedBindingVersion,
            intent.pendingPoliciesToSupersede, intent.supersedeReason,
        ))
        onCommandSending()
        return api.request(HttpMethod.Post, endpoint, HiltechRequestOptions(installationId, intent.operationId), body,
            decode = { json.decodeFromString(CompanyReviewSetupReceiptDto.serializer(), it) },
        ).also { require(it.operationId == intent.operationId && it.organizationId == intent.organizationId &&
            it.workflow == intent.workflow && it.policyRevision > 0) }
    }

    private fun path(organizationId: String, workflow: String): String {
        requireUuid(organizationId)
        require(workflow in companyReviewWorkflows)
        return "/v1/organizations/$organizationId/administration/review-workflows/$workflow"
    }
    private fun requireUuid(value: String) = require(UUID_PATTERN.matches(value))
    companion object {
        private val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}

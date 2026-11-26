package com.hiltech.shared.core.people

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechApiException
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class EmployeeAccountReferenceDto(val identityId: String, val identityVersion: Long, val membershipId: String, val membershipVersion: Long)
@Serializable
data class OwnAccountCandidateDto(val displayName: String? = null, val identityVersion: Long, val membershipId: String, val membershipVersion: Long)
@Serializable
data class CredentialReissueSummaryDto(val operationId: String, val status: String, val version: Long, val completedAt: String? = null)
@Serializable
data class EmployeeAccountAccessDto(val employeeId: String, val organizationId: String, val employeeVersion: Long,
    val account: EmployeeAccountReferenceDto? = null, val canReissue: Boolean = false, val ownAccountCandidate: OwnAccountCandidateDto? = null,
    val latestReissue: CredentialReissueSummaryDto? = null)
@Serializable
data class EmployeeCredentialReissueIntent(val operationId: String, val employeeId: String, val organizationId: String,
    val baseEmployeeVersion: Long, val expectedIdentityId: String, val baseIdentityVersion: Long, val expectedMembershipId: String,
    val baseMembershipVersion: Long, val previousOperationId: String?, val previousOperationVersion: Long?, val reason: String)
@Serializable
private data class EmployeeCredentialReissueRequest(val operationId: String, val baseEmployeeVersion: Long, val expectedIdentityId: String,
    val baseIdentityVersion: Long, val expectedMembershipId: String, val baseMembershipVersion: Long,
    val previousOperationId: String?, val previousOperationVersion: Long?, val reason: String)
@Serializable
data class EmployeeCredentialReissueReceipt(val operationId: String, val employeeId: String, val organizationId: String, val identityId: String,
    val status: String, val version: Long, val replayed: Boolean, val temporaryCredential: String? = null, val safeFailureCode: String? = null) {
    override fun toString() = "EmployeeCredentialReissueReceipt(operationId=$operationId,status=$status,credential=redacted)"
}
@Serializable
data class LinkOwnEmployeeAccountIntent(val operationId: String, val employeeId: String, val organizationId: String,
    val baseEmployeeVersion: Long, val baseIdentityVersion: Long, val expectedMembershipId: String, val baseMembershipVersion: Long,
    val accountDisplayName: String)
@Serializable
private data class LinkOwnEmployeeAccountRequest(val operationId: String, val baseEmployeeVersion: Long, val baseIdentityVersion: Long,
    val expectedMembershipId: String, val baseMembershipVersion: Long)
@Serializable
data class LinkOwnEmployeeAccountReceipt(val operationId: String, val employeeId: String, val organizationId: String,
    val employeeVersion: Long, val linked: Boolean, val replayed: Boolean)

class EmployeeAccountAccessApiClient(private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false }) {
    suspend fun current(employeeId: String, installationId: String): EmployeeAccountAccessDto {
        uuid(employeeId)
        return api.request(HttpMethod.Get, "/v1/employees/$employeeId/account-access", HiltechRequestOptions(installationId),
            decode = { json.decodeFromString(EmployeeAccountAccessDto.serializer(), it) }).also { result ->
            require(result.employeeId == employeeId && result.employeeVersion >= 1)
            uuid(result.organizationId)
            require(!result.canReissue || result.account != null)
            require(result.account == null || result.ownAccountCandidate == null)
            result.account?.let { uuid(it.identityId); uuid(it.membershipId); require(it.identityVersion > 0 && it.membershipVersion > 0) }
            result.ownAccountCandidate?.let { uuid(it.membershipId); require(it.identityVersion > 0 && it.membershipVersion > 0) }
            result.latestReissue?.let { uuid(it.operationId); require(result.account != null && it.version > 0 && it.status in setOf("ISSUED", "REJECTED", "UNCONFIRMED")) }
        }
    }
    suspend fun reissue(intent: EmployeeCredentialReissueIntent, installationId: String, onCommandSending: () -> Unit = {}): EmployeeCredentialReissueReceipt {
        listOf(intent.operationId, intent.employeeId, intent.organizationId, intent.expectedIdentityId, intent.expectedMembershipId).forEach(::uuid)
        intent.previousOperationId?.let(::uuid)
        require(intent.baseEmployeeVersion > 0 && intent.baseIdentityVersion > 0 && intent.baseMembershipVersion > 0)
        require((intent.previousOperationId == null) == (intent.previousOperationVersion == null) && (intent.previousOperationVersion ?: 1) > 0)
        require(intent.reason.trim().length in 1..500)
        val body = json.encodeToString(EmployeeCredentialReissueRequest.serializer(), EmployeeCredentialReissueRequest(intent.operationId,
            intent.baseEmployeeVersion, intent.expectedIdentityId, intent.baseIdentityVersion, intent.expectedMembershipId, intent.baseMembershipVersion,
            intent.previousOperationId, intent.previousOperationVersion, intent.reason))
        onCommandSending()
        return api.request(HttpMethod.Post, "/v1/employees/${intent.employeeId}/credential-reissues", HiltechRequestOptions(installationId, intent.operationId), body,
            decode = { json.decodeFromString(EmployeeCredentialReissueReceipt.serializer(), it) }).also {
            require(it.operationId == intent.operationId && it.employeeId == intent.employeeId && it.organizationId == intent.organizationId &&
                it.identityId == intent.expectedIdentityId && it.version >= 1 && it.status in setOf("ISSUED", "REJECTED", "UNCONFIRMED"))
            require((it.status == "ISSUED" && !it.replayed) == (it.temporaryCredential != null))
            require(it.temporaryCredential == null || it.temporaryCredential.isNotBlank())
        }
    }
    suspend fun linkOwn(intent: LinkOwnEmployeeAccountIntent, installationId: String, onCommandSending: () -> Unit = {}): LinkOwnEmployeeAccountReceipt {
        listOf(intent.operationId, intent.employeeId, intent.organizationId, intent.expectedMembershipId).forEach(::uuid)
        require(intent.baseEmployeeVersion > 0 && intent.baseIdentityVersion > 0 && intent.baseMembershipVersion > 0)
        val body = json.encodeToString(LinkOwnEmployeeAccountRequest.serializer(), LinkOwnEmployeeAccountRequest(intent.operationId,
            intent.baseEmployeeVersion, intent.baseIdentityVersion, intent.expectedMembershipId, intent.baseMembershipVersion))
        onCommandSending()
        return api.request(HttpMethod.Post, "/v1/employees/${intent.employeeId}/own-account-link", HiltechRequestOptions(installationId, intent.operationId), body,
            decode = { json.decodeFromString(LinkOwnEmployeeAccountReceipt.serializer(), it) }).also {
            require(it.operationId == intent.operationId && it.employeeId == intent.employeeId && it.organizationId == intent.organizationId && it.employeeVersion > 0)
        }
    }
    private fun uuid(value: String) = require(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(value))
}

internal fun accountAccessFailureDisposition(hadUnknown: Boolean, failure: Throwable): CompanyResponsibilityFailureDisposition {
    val api = failure as? HiltechApiException
    if (!hadUnknown && api?.code == "ACCOUNT_CREDENTIAL_COMMAND_BUSY" && api.envelope?.code == api.code && api.httpStatus == 409) {
        return CompanyResponsibilityFailureDisposition(false, true)
    }
    return companyResponsibilityFailureDisposition(hadUnknown, failure)
}

package com.hiltech.shared.core.people

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ResponsibilityDelegationBindingDto(val bindingId: String, val authorityKey: String,
    val canDelegateAdministration: Boolean, val current: Boolean, val version: Long, val canEnd: Boolean)
@Serializable
data class EmployeeResponsibilityDelegationDto(val employeeId: String, val organizationId: String, val employeeVersion: Long,
    val linkedIdentityId: String?, val available: Boolean, val canGrant: Boolean, val delegableAuthorityKeys: List<String>,
    val bindings: List<ResponsibilityDelegationBindingDto>)
@Serializable
data class ResponsibilityDelegationReceiptDto(val operationId: String, val employeeId: String, val organizationId: String,
    val identityId: String, val resultCode: String, val bindings: List<ResponsibilityDelegationBindingDto>, val replayed: Boolean)
@Serializable
data class ResponsibilityDelegationIntent(val operationId: String, val employeeId: String, val organizationId: String,
    val expectedIdentityId: String, val baseEmployeeVersion: Long, val employeeDisplayName: String,
    val authorityKeys: List<String>, val canDelegateAdministration: Boolean, val action: String,
    val bindingId: String? = null, val baseBindingVersion: Long? = null)
@Serializable
private data class GrantResponsibilityDelegationRequestDto(val operationId: String, val organizationId: String,
    val expectedIdentityId: String, val baseEmployeeVersion: Long, val authorityKeys: List<String>, val canDelegateAdministration: Boolean)
@Serializable
private data class EndResponsibilityDelegationRequestDto(val operationId: String, val organizationId: String,
    val expectedIdentityId: String, val baseEmployeeVersion: Long, val authorityKey: String, val baseBindingVersion: Long)

class ResponsibilityDelegationApiClient(private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false }) {
    suspend fun current(employeeId: String, installationId: String): EmployeeResponsibilityDelegationDto {
        uuid(employeeId)
        return api.request(HttpMethod.Get, "/v1/employees/$employeeId/responsibility-administration", HiltechRequestOptions(installationId),
            decode = { json.decodeFromString(EmployeeResponsibilityDelegationDto.serializer(), it) },
        ).also { require(it.employeeId == employeeId) }
    }
    suspend fun apply(intent: ResponsibilityDelegationIntent, installationId: String, onCommandSending: () -> Unit = {}): ResponsibilityDelegationReceiptDto {
        listOf(intent.operationId, intent.employeeId, intent.organizationId, intent.expectedIdentityId).forEach(::uuid)
        require(intent.baseEmployeeVersion > 0 && intent.authorityKeys.isNotEmpty() && intent.authorityKeys.all { it in employeeCompanyResponsibilityKeys })
        require(intent.authorityKeys.distinct().size == intent.authorityKeys.size && intent.action in setOf("GRANT", "END"))
        val ending = intent.action == "END"
        val body = if (ending) {
            uuid(requireNotNull(intent.bindingId)); require((intent.baseBindingVersion ?: 0) > 0 && intent.authorityKeys.size == 1)
            json.encodeToString(EndResponsibilityDelegationRequestDto.serializer(), EndResponsibilityDelegationRequestDto(
                intent.operationId, intent.organizationId, intent.expectedIdentityId, intent.baseEmployeeVersion,
                intent.authorityKeys.single(), requireNotNull(intent.baseBindingVersion)))
        } else {
            require(intent.bindingId == null && intent.baseBindingVersion == null)
            json.encodeToString(GrantResponsibilityDelegationRequestDto.serializer(), GrantResponsibilityDelegationRequestDto(
                intent.operationId, intent.organizationId, intent.expectedIdentityId, intent.baseEmployeeVersion,
                intent.authorityKeys.sorted(), intent.canDelegateAdministration))
        }
        val path = "/v1/employees/${intent.employeeId}/responsibility-administration" + if (ending) "/${intent.bindingId}/end" else ""
        onCommandSending()
        val receipt = api.request(HttpMethod.Post, path, HiltechRequestOptions(installationId, intent.operationId), body,
            decode = { json.decodeFromString(ResponsibilityDelegationReceiptDto.serializer(), it) })
        require(receipt.operationId == intent.operationId && receipt.employeeId == intent.employeeId && receipt.organizationId == intent.organizationId &&
            receipt.identityId == intent.expectedIdentityId && receipt.bindings.map { it.authorityKey }.toSet() == intent.authorityKeys.toSet() &&
            receipt.resultCode == if (ending) "RESPONSIBILITY_DELEGATION_ENDED" else "RESPONSIBILITY_DELEGATION_GRANTED")
        if (ending) require(receipt.bindings.single().bindingId == intent.bindingId)
        return receipt
    }
    private fun uuid(value: String) = require(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(value))
}

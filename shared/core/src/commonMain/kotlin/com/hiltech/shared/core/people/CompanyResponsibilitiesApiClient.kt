package com.hiltech.shared.core.people

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechApiException
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val employeeCompanyResponsibilityKeys = setOf(
    "PEOPLE_ADMIN", "PROJECT_ADMIN", "SALES_VIEW", "SALES_WRITE", "SALES_COST_VIEW", "SALES_RELEASE", "SALES_AWARD",
    "PAYROLL_READ", "PAYROLL_PREPARE", "PAYROLL_REVIEW", "SUPPLIER_MASTER_DATA_ADMIN", "APPROVAL_POLICY_ADMIN", "COMPANY_SETTINGS_ADMIN",
    "FINANCE_PAYROLL_PREPARE", "FINANCE_PAYROLL_REVIEW",
    "PROCUREMENT_BUYER",
    "PROCUREMENT_TECHNICAL_REVIEW",
    "PROCUREMENT_NEED_REQUEST",
    "PROCUREMENT_NEED_REVIEW",
    "PROCUREMENT_ORDER_ISSUE",
    "PROCUREMENT_SERVICE_ACCEPT",
    "FINANCE_SUPPLIER_REVIEW",
    "FINANCE_EMPLOYEE_REVIEW",
    "FINANCE_COMMERCIAL_REVIEW",
    "FINANCE_PAYABLE_PREPARE",
    "FINANCE_PAYABLE_POST",
    "FINANCE_RECEIVABLE_PREPARE",
    "FINANCE_RECEIVABLE_EXECUTE",
    "FINANCE_EMPLOYEE_PREPARE",
    "FINANCE_EMPLOYEE_EXECUTE",
    "FINANCE_BANK_STATEMENT_PREPARE",
    "FINANCE_BANK_STATEMENT_IMPORT",
    "FINANCE_BANK_RECONCILIATION_PREPARE",
    "FINANCE_BANK_RECONCILIATION_FINALIZE",
    "FINANCE_CASH_PREPARE",
    "FINANCE_CASH_EXECUTE",
    "FINANCE_PERIOD_CLOSE_PREPARE",
    "FINANCE_PERIOD_CLOSE_EXECUTE",
    "FINANCE_REPORT_READ",
)

@Serializable
data class EmployeeResponsibilityTargetDto(
    val employeeId: String, val organizationId: String, val version: Long, val displayName: String,
    val employeeCode: String = "", val employeeState: String = "", val accountStatus: String = "",
)

@Serializable
data class EmployeeCompanyResponsibilitiesDto(
    val employeeId: String, val organizationId: String, val employeeVersion: Long,
    val linkedIdentityId: String?, val accountStatus: String, val scope: String, val asOf: String,
    val catalog: List<CompanyResponsibilityCatalogDto>, val bindings: List<CompanyResponsibilityBindingDto>,
    val viewScope: String = "DELEGATED_KEYS", val canDelegateAdministration: Boolean = false,
)
@Serializable
data class CompanyResponsibilityCatalogDto(val authorityKey: String, val domain: String, val canGrant: Boolean)
@Serializable
data class CompanyResponsibilityBindingDto(
    val bindingId: String, val authorityKey: String, val domain: String, val assignmentSource: String,
    val canEnd: Boolean, val current: Boolean, val version: Long,
)
@Serializable
data class CompanyResponsibilityCommandDto(
    val employeeId: String, val organizationId: String, val identityId: String, val authorityKey: String,
    val bindingId: String, val resultCode: String, val current: Boolean, val version: Long, val replayed: Boolean,
    val operationId: String,
    val effectiveAccessRemaining: Boolean = false,
)
@Serializable
data class EmployeeCompanyResponsibilityIntent(
    val operationId: String, val employeeId: String, val organizationId: String,
    val baseEmployeeVersion: Long, val expectedIdentityId: String, val authorityKey: String,
    val employeeDisplayName: String, val action: String, val bindingId: String? = null, val baseBindingVersion: Long? = null,
)
@Serializable
private data class GrantCompanyResponsibilityRequest(
    val operationId: String, val organizationId: String, val baseEmployeeVersion: Long,
    val expectedIdentityId: String, val authorityKey: String,
)
@Serializable
private data class EndCompanyResponsibilityRequest(
    val operationId: String, val organizationId: String, val baseEmployeeVersion: Long,
    val expectedIdentityId: String, val authorityKey: String, val baseBindingVersion: Long,
)

data class CompanyResponsibilityFailureDisposition(val hasUnknownOutcome: Boolean, val rejectionConfirmed: Boolean)

/** A later authorization/version rejection does not establish the fate of an earlier lost response. */
fun companyResponsibilityFailureDisposition(hadUnknownOutcome: Boolean, failure: Throwable): CompanyResponsibilityFailureDisposition {
    val api = failure as? HiltechApiException
    val explicitRejection = api != null && api.httpStatus?.let { it in 400..499 } == true &&
        api.envelope?.code == api.code && !api.retryable && api.code != "IDEMPOTENCY_KEY_REUSED"
    val storedFailure = explicitRejection && api?.httpStatus == 409 && api.code == "OPERATION_PREVIOUSLY_FAILED"
    return CompanyResponsibilityFailureDisposition(
        !storedFailure && (hadUnknownOutcome || !explicitRejection),
        explicitRejection && (!hadUnknownOutcome || storedFailure),
    )
}

class CompanyResponsibilitiesApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    val delegation = ResponsibilityDelegationApiClient(api, json)
    suspend fun current(employeeId: String, installationId: String): EmployeeCompanyResponsibilitiesDto {
        requireUuid(employeeId)
        return api.request(HttpMethod.Get, "/v1/employees/$employeeId/company-responsibilities",
            HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(EmployeeCompanyResponsibilitiesDto.serializer(), it) },
        ).also { require(it.employeeId == employeeId && it.scope == "ORGANIZATION_CATALOG") }
    }

    suspend fun apply(intent: EmployeeCompanyResponsibilityIntent, installationId: String,
                      onCommandSending: () -> Unit = {}): CompanyResponsibilityCommandDto {
        listOf(intent.operationId, intent.employeeId, intent.organizationId, intent.expectedIdentityId).forEach(::requireUuid)
        require(intent.baseEmployeeVersion > 0 && intent.authorityKey in employeeCompanyResponsibilityKeys)
        require(intent.action == "GRANT" || intent.action == "END")
        val ending = intent.action == "END"
        val body = if (ending) {
            requireUuid(requireNotNull(intent.bindingId))
            require((intent.baseBindingVersion ?: 0) > 0)
            json.encodeToString(EndCompanyResponsibilityRequest.serializer(), EndCompanyResponsibilityRequest(
                intent.operationId, intent.organizationId, intent.baseEmployeeVersion, intent.expectedIdentityId,
                intent.authorityKey, requireNotNull(intent.baseBindingVersion),
            ))
        } else {
            require(intent.bindingId == null && intent.baseBindingVersion == null)
            json.encodeToString(GrantCompanyResponsibilityRequest.serializer(), GrantCompanyResponsibilityRequest(
                intent.operationId, intent.organizationId, intent.baseEmployeeVersion, intent.expectedIdentityId, intent.authorityKey,
            ))
        }
        val path = "/v1/employees/${intent.employeeId}/company-responsibilities" +
            if (ending) "/${intent.bindingId}/end" else ""
        onCommandSending()
        val result = api.request(HttpMethod.Post, path,
            HiltechRequestOptions(installationId, intent.operationId), body,
            decode = { json.decodeFromString(CompanyResponsibilityCommandDto.serializer(), it) },
        )
        require(result.operationId == intent.operationId && result.employeeId == intent.employeeId &&
            result.organizationId == intent.organizationId && result.identityId == intent.expectedIdentityId &&
            result.authorityKey == intent.authorityKey && result.version > 0 &&
            result.resultCode == if (ending) "COMPANY_RESPONSIBILITY_ENDED" else "COMPANY_RESPONSIBILITY_GRANTED") {
            "The responsibility receipt does not match the submitted operation."
        }
        if (ending) require(result.bindingId == intent.bindingId)
        return result
    }

    private fun requireUuid(value: String) = require(UUID_PATTERN.matches(value))
    companion object {
        private val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}

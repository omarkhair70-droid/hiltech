package com.hiltech.shared.core.warehouse

import kotlinx.serialization.Serializable
import com.hiltech.shared.core.network.HiltechApiException

/** A scoped source snapshot, not an assertion of all employee access. */
@Serializable
data class EmployeeWarehouseResponsibilitiesDto(
    val employeeId: String,
    val organizationId: String,
    val employeeVersion: Long,
    val linkedIdentityId: String?,
    val accountStatus: String,
    val scope: String,
    val asOf: String,
    val warehouses: List<EmployeeManagedWarehouseDto>,
)

@Serializable
data class EmployeeManagedWarehouseDto(
    val warehouseId: String,
    val code: String,
    val name: String,
    val grantableAuthorityKeys: List<String>,
    val bindings: List<EmployeeWarehouseBindingDto>,
)

@Serializable
data class EmployeeWarehouseBindingDto(
    val bindingId: String,
    val authorityKey: String,
    val assignmentSource: String,
    val canEnd: Boolean,
    val version: Long,
)

/** Preserve the complete intent across retries; never regenerate its operation ID. */
@Serializable
data class EmployeeWarehouseAuthorityIntent(
    val operationId: String,
    val organizationId: String,
    val employeeId: String,
    val employeeVersion: Long,
    val employeeDisplayName: String,
    val action: String,
    val warehouseId: String,
    val warehouseName: String,
    val principalId: String,
    val authorityKey: String,
    val effectiveFrom: String,
    val clientOccurredAt: String,
    val bindingId: String? = null,
    val baseVersion: Long? = null,
)

/** No command was sent: the current employee projection could not be confirmed. */
class EmployeeWarehousePreparationException(val safeCode: String) : IllegalStateException(safeCode)

data class EmployeeWarehouseFailureDisposition(
    val hasUnknownOutcome: Boolean,
    val rejectionConfirmed: Boolean,
)

/** A later access/version rejection does not resolve an earlier lost response. */
fun employeeWarehouseFailureDisposition(
    hadUnknownOutcome: Boolean,
    failure: Throwable,
): EmployeeWarehouseFailureDisposition {
    if (failure is EmployeeWarehousePreparationException) {
        return EmployeeWarehouseFailureDisposition(hadUnknownOutcome, !hadUnknownOutcome)
    }
    val api = failure as? HiltechApiException
    val explicitRejection = api != null && api.httpStatus?.let { it in 400..499 } == true &&
        api.envelope?.code == api.code && !api.retryable && api.code != "IDEMPOTENCY_KEY_REUSED"
    val storedFailure = explicitRejection && api?.httpStatus == 409 && api?.code == "OPERATION_PREVIOUSLY_FAILED"
    return EmployeeWarehouseFailureDisposition(
        hasUnknownOutcome = !storedFailure && (hadUnknownOutcome || !explicitRejection),
        rejectionConfirmed = explicitRejection && (!hadUnknownOutcome || storedFailure),
    )
}

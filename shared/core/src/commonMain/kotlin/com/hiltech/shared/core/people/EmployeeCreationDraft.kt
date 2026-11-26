package com.hiltech.shared.core.people

import com.hiltech.shared.core.design.isValidOptionalHiltechDate

/** Personal data is kept in memory. Job assignment and account invitation are separate source commands. */
data class EmployeeCreationDraft(
    val displayName: String = "",
    val employeeCode: String = "",
    val startDate: String = "",
    val employmentTypeCode: String? = null,
    val legalName: String = "",
    val mobile: String = "",
    val email: String = "",
) {
    fun normalized() = copy(
        displayName = displayName.trim(),
        employeeCode = employeeCode.trim().uppercase(),
        startDate = startDate.trim(),
        employmentTypeCode = employmentTypeCode?.trim()?.uppercase()?.takeIf { it.isNotBlank() },
        legalName = legalName.trim(),
        mobile = mobile.trim(),
        email = email.trim().lowercase(),
    )

    fun isValid(): Boolean {
        val value = normalized()
        return value.displayName.length in 1..160 &&
            value.employeeCode.length in 1..64 &&
            value.startDate.isNotBlank() && isValidOptionalHiltechDate(value.startDate) &&
            (value.employmentTypeCode == null || Regex("^[A-Z][A-Z0-9_-]{0,79}$").matches(value.employmentTypeCode)) &&
            value.legalName.length <= 240 && value.mobile.length <= 64 && value.email.length <= 320 &&
            (value.email.isEmpty() || Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(value.email))
    }

    /** Include every submitted field and its length so retries reuse only an identical intent. */
    fun operationSeed(identityId: String, organizationId: String): String {
        val value = normalized()
        return listOf("hiltech-employee-create-v2", identityId, organizationId,
            value.employeeCode, value.displayName, value.startDate, value.employmentTypeCode,
            value.legalName, value.mobile, value.email).joinToString("") {
            it?.let { part -> "${part.length}:$part" } ?: "-1:"
        }
    }

    fun request(operationId: String, organizationId: String): CreateEmployeeRequestDto {
        require(isValid())
        val value = normalized()
        return CreateEmployeeRequestDto(operationId, organizationId, value.displayName,
            value.employeeCode, value.startDate, value.employmentTypeCode,
            legalName = value.legalName.ifBlank { null },
            mobile = value.mobile.ifBlank { null }, email = value.email.ifBlank { null })
    }

    fun matches(employee: EmployeeDetailDto, organizationId: String): Boolean {
        val value = normalized()
        return employee.organizationId == organizationId && employee.employeeCode == value.employeeCode &&
            employee.displayName == value.displayName
    }
}

internal fun observedEmploymentTypes(directory: EmployeeDirectoryDto?): List<String> =
    directory?.items.orEmpty().mapNotNull { it.currentEmploymentTypeCode }
        .filter { Regex("^[A-Z][A-Z0-9_-]{0,79}$").matches(it) }.distinct().sorted()

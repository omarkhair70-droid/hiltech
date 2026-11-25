package com.hiltech.server.payroll

import org.springframework.http.HttpStatus
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/** No wage, deduction rate or statutory formula is supplied by the application. */
object PayrollSourcePolicy {
    const val REVIEW_POLICY = "PAYROLL_SOURCE_REVIEW"
    const val SUBJECT_TYPE = "PAYROLL_SOURCE_RECORD"
    val domains = setOf("POLICY", "COMPENSATION", "APPROVED_TIME", "FINANCE")

    fun validate(request: PayrollSourceRequestDto) {
        uuid(request.operationId)
        request.replacesRecordId?.let(::uuid)
        if (request.domain !in domains || !reference(request.sourceReference) ||
            !Regex("^[A-Z]{3}$").matches(request.currency)
        ) invalid("PAYROLL_SOURCE_INVALID")
        if (request.domain == "POLICY") {
            if (request.employeeId != null || request.noInputs || request.components.isNotEmpty() ||
                request.fractionalDigits == null || request.fractionalDigits !in 0..6 ||
                request.roundingMode !in RoundingMode.values().map { it.name } ||
                !reference(request.companyAuthorizationReference) ||
                !reference(request.statutoryPolicyReference) ||
                (request.deductionPolicyReference != null && !reference(request.deductionPolicyReference))
            ) invalid("PAYROLL_SOURCE_POLICY_INCOMPLETE")
            return
        }
        uuid(request.employeeId ?: invalid("PAYROLL_SOURCE_EMPLOYEE_REQUIRED"))
        if (request.fractionalDigits != null || request.roundingMode != null ||
            request.companyAuthorizationReference != null || request.statutoryPolicyReference != null ||
            request.deductionPolicyReference != null || request.components.size > 100 ||
            request.noInputs != request.components.isEmpty() ||
            (request.domain == "COMPENSATION" && request.noInputs)
        ) invalid("PAYROLL_SOURCE_COVERAGE_REQUIRED")
        if (request.components.map { it.code }.distinct().size != request.components.size) {
            invalid("PAYROLL_DUPLICATE_COMPONENT")
        }
        request.components.forEach { component ->
            val allowedKinds = if (request.domain == "FINANCE") setOf("DEDUCTION", "REIMBURSEMENT") else setOf("EARNING")
            val amount = component.amount.toBigDecimalOrNull()
            if (!reference(component.description) || component.kind !in allowedKinds || !Regex("^[A-Z][A-Z0-9_-]{0,79}$").matches(component.code) ||
                amount == null || amount <= BigDecimal.ZERO || amount.precision() > 18 || amount.scale() > 6 ||
                amount.precision() - amount.scale() > 14
            ) invalid("PAYROLL_SOURCE_COMPONENT_INVALID")
            if ((component.kind == "DEDUCTION" && !reference(component.deductionPlanReference)) ||
                (component.kind != "DEDUCTION" && component.deductionPlanReference != null)
            ) invalid("PAYROLL_DEDUCTION_PLAN_REQUIRED")
        }
    }

    fun reference(value: String?): Boolean = value != null && value.trim() == value &&
        value.length in 3..240 && value.none { it == '|' || it == '\n' || it == '\r' || it.code < 32 }

    fun uuid(value: String): UUID = runCatching { UUID.fromString(value) }
        .getOrElse { invalid("INVALID_PAYROLL_UUID") }

    private fun invalid(code: String): Nothing = Payroll9aPolicy.fail(code, HttpStatus.BAD_REQUEST)
}

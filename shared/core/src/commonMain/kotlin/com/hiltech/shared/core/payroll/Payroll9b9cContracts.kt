package com.hiltech.shared.core.payroll

import kotlinx.serialization.Serializable

@Serializable
data class CreatePayrollDraftRequestDto(val operationId: String)

@Serializable
data class PayrollRunLineDto(
    val employeeId: String,
    val snapshotId: String,
    val currency: String,
    val gross: String,
    val deductions: String,
    val reimbursements: String,
    val net: String,
    val sourceFingerprint: String,
)

@Serializable
data class PayrollRunDto(
    val runVersionId: String,
    val organizationId: String,
    val payrollPeriodId: String,
    val generation: Int,
    val state: String,
    val currency: String,
    val gross: String,
    val deductions: String,
    val reimbursements: String,
    val net: String,
    val sourceFingerprint: String,
    val lines: List<PayrollRunLineDto>,
    val approvalAllowed: Boolean = false,
    val paymentAllowed: Boolean = false,
)

@Serializable
data class PayrollRunDraftCommandDto(
    val run: PayrollRunDto,
    val replayed: Boolean,
    val correlationId: String,
)

@Serializable
data class PrivatePayslipDto(
    val runVersionId: String,
    val employeeId: String,
    val periodStart: String,
    val periodEnd: String,
    val generation: Int,
    val currency: String,
    val gross: String,
    val deductions: String,
    val reimbursements: String,
    val net: String,
    val paymentStatus: String,
    val financeEvidenceReference: String? = null,
)

@Serializable
data class OwnPayslipsDto(
    val items: List<PrivatePayslipDto>,
    val correlationId: String,
)

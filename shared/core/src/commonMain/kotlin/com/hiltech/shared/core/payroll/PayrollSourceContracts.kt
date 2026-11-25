package com.hiltech.shared.core.payroll

import kotlinx.serialization.Serializable

@Serializable
data class PayrollCapabilitiesDto(
    val canPrepare: Boolean = false,
    val canReview: Boolean = false,
    val periodsAvailable: Boolean = false,
    val runsAvailable: Boolean = false,
    val runApprovalAvailable: Boolean = false,
    val releaseAvailable: Boolean = false,
    val financeHandoffAvailable: Boolean = false,
    val financeStatusAvailable: Boolean = false,
    val runReviewConfigured: Boolean = false,
)

/** Explicit, reviewed period inputs. Empty source records require a positive no-input attestation. */
@Serializable
data class PayrollSourceComponentDto(
    val code: String,
    val kind: String,
    val amount: String,
    val deductionPlanReference: String? = null,
    val description: String = "",
)

@Serializable
data class PayrollSourceRequestDto(
    val operationId: String,
    val domain: String,
    val employeeId: String? = null,
    val replacesRecordId: String? = null,
    val sourceReference: String,
    val currency: String,
    val noInputs: Boolean = false,
    val components: List<PayrollSourceComponentDto> = emptyList(),
    val fractionalDigits: Int? = null,
    val roundingMode: String? = null,
    val companyAuthorizationReference: String? = null,
    val statutoryPolicyReference: String? = null,
    val deductionPolicyReference: String? = null,
)

@Serializable
data class PayrollSourceRecordDto(
    val recordId: String,
    val periodId: String,
    val domain: String,
    val employeeId: String?,
    val revision: Long,
    val replacesRecordId: String?,
    val sourceReference: String,
    val currency: String,
    val noInputs: Boolean,
    val components: List<PayrollSourceComponentDto>,
    val fractionalDigits: Int?,
    val roundingMode: String?,
    val companyAuthorizationReference: String?,
    val statutoryPolicyReference: String?,
    val deductionPolicyReference: String?,
    val preparedByIdentityId: String,
    val createdAt: String,
    val approvalState: String,
    val approvalRequestId: String?,
)

@Serializable
data class PayrollSourcesDto(
    val periodId: String,
    val records: List<PayrollSourceRecordDto>,
    val reviewPolicyReady: Boolean,
    val calculationReady: Boolean,
    val blockers: List<String>,
)

@Serializable
data class PayrollSourceCommandDto(
    val record: PayrollSourceRecordDto,
    val replayed: Boolean,
)

@Serializable
data class PayrollSourceApprovalRequestDto(val operationId: String)

@Serializable
data class PayrollSourceDecisionRequestDto(
    val operationId: String,
    val decision: String,
    val comment: String? = null,
)

internal fun payrollSourceReceiptMatches(periodId: String, request: PayrollSourceRequestDto, record: PayrollSourceRecordDto): Boolean {
    fun normalizedAmount(value: String): String {
        val whole = value.substringBefore('.').trimStart('0').ifEmpty { "0" }
        val fraction = value.substringAfter('.', "").trimEnd('0')
        return whole + if (fraction.isEmpty()) "" else ".$fraction"
    }
    fun components(values: List<PayrollSourceComponentDto>) = values.map { it.copy(amount = normalizedAmount(it.amount)) }.sortedBy { it.code }
    return record.periodId == periodId && record.domain == request.domain && record.employeeId == request.employeeId &&
        record.replacesRecordId == request.replacesRecordId && record.sourceReference == request.sourceReference &&
        record.currency == request.currency && record.noInputs == request.noInputs &&
        record.fractionalDigits == request.fractionalDigits && record.roundingMode == request.roundingMode &&
        record.companyAuthorizationReference == request.companyAuthorizationReference &&
        record.statutoryPolicyReference == request.statutoryPolicyReference && record.deductionPolicyReference == request.deductionPolicyReference &&
        components(record.components) == components(request.components)
}

package com.hiltech.server.payroll

import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class PayrollSourcePolicyTest {
    private val base = PayrollSourceRequestDto(UUID.randomUUID().toString(), "COMPENSATION", UUID.randomUUID().toString(),
        sourceReference = "Synthetic employment schedule", currency = "EGP",
        components = listOf(PayrollSourceComponentDto("BASE", "EARNING", "100.00", description = "Synthetic base pay")))

    @Test
    fun missingSourceCannotBeRepresentedByZero() {
        assertEquals("PAYROLL_SOURCE_COVERAGE_REQUIRED", assertThrows<ProductApiException> {
            PayrollSourcePolicy.validate(base.copy(components = emptyList()))
        }.code)
        assertThrows<ProductApiException> { PayrollSourcePolicy.validate(base.copy(components = emptyList(), noInputs = true)) }
        PayrollSourcePolicy.validate(base.copy(domain = "APPROVED_TIME", noInputs = true, components = emptyList()))
        PayrollSourcePolicy.validate(base.copy(domain = "FINANCE", noInputs = true, components = emptyList()))
    }

    @Test
    fun componentCannotCrossOwnerOrSilentlyBecomeADeduction() {
        assertThrows<ProductApiException> { PayrollSourcePolicy.validate(base.copy(domain = "FINANCE")) }
        val deduction = base.copy(domain = "FINANCE", components = listOf(PayrollSourceComponentDto("DEDUCT", "DEDUCTION", "1", description = "Synthetic deduction")))
        assertEquals("PAYROLL_DEDUCTION_PLAN_REQUIRED", assertThrows<ProductApiException> { PayrollSourcePolicy.validate(deduction) }.code)
        PayrollSourcePolicy.validate(deduction.copy(components = deduction.components.map { it.copy(deductionPlanReference = "Synthetic approved recovery plan") }))
        for (amount in listOf("0", "-1", "0.0000001", "1e30", "NaN", "999999999999999")) {
            assertThrows<ProductApiException> { PayrollSourcePolicy.validate(base.copy(components = base.components.map { it.copy(amount = amount) })) }
        }
    }

    @Test
    fun policyCannotUseBareCheckboxesOrInjectedFingerprintSeparators() {
        val policy = PayrollSourceRequestDto(UUID.randomUUID().toString(), "POLICY", sourceReference = "Synthetic signed policy", currency = "EGP")
        assertThrows<ProductApiException> { PayrollSourcePolicy.validate(policy) }
        val reviewed = policy.copy(fractionalDigits = 2, roundingMode = "HALF_EVEN",
            companyAuthorizationReference = "Synthetic company authorization", statutoryPolicyReference = "Synthetic accountant review")
        PayrollSourcePolicy.validate(reviewed)
        assertThrows<ProductApiException> { PayrollSourcePolicy.validate(reviewed.copy(statutoryPolicyReference = "Fake|Other")) }
        assertThrows<ProductApiException> { PayrollSourcePolicy.validate(base.copy(components = base.components + base.components)) }
    }
}

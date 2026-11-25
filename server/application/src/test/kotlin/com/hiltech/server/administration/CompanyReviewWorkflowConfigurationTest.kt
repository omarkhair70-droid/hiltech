package com.hiltech.server.administration

import com.hiltech.server.approval.CompanyReviewPolicyKeys
import com.hiltech.server.payroll.PayrollRunPolicyConfiguration
import com.hiltech.server.procurement.ProcurementReviewPolicyConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.env.MockEnvironment

class CompanyReviewWorkflowConfigurationTest {
    @Test fun blankConfigurationResolvesEveryExistingSourceWithoutCreatingAPolicyOrAuthority() {
        val environment = MockEnvironment()
        val catalog = CompanyReviewWorkflowConfiguration(environment)
        assertEquals(23, CompanyReviewPolicyKeys.defaults.size)
        assertEquals(27, CompanyReviewWorkflow.entries.size)
        CompanyReviewWorkflow.entries.forEach { workflow ->
            assertEquals(workflow.policyKey, catalog.key(workflow))
            assertEquals(listOf(workflow), catalog.group(workflow))
            assertEquals(workflow.authorityKey, catalog.authorityKey(workflow))
            workflow.configurationProperty?.let { property ->
                assertEquals(workflow.policyKey, CompanyReviewPolicyKeys.resolve(environment, property))
                environment.setProperty(property, "  ")
                assertEquals(workflow.policyKey, CompanyReviewPolicyKeys.resolve(environment, property))
            }
        }
        assertEquals(CompanyReviewPolicyKeys.defaults.keys,
            CompanyReviewWorkflow.entries.mapNotNull { it.configurationProperty }.toSet())
    }

    @Test fun explicitKeysArePreservedIncludingInvalidValuesForTheOwningSourceToReject() {
        val property = "hiltech.finance.cashbox.opening-policy-key"
        assertEquals("INSTALLED_CASH_REVIEW", CompanyReviewPolicyKeys.resolve(" INSTALLED_CASH_REVIEW ", property))
        assertEquals("invalid installed key", CompanyReviewPolicyKeys.resolve(" invalid installed key ", property))
        assertThrows<NoSuchElementException> { CompanyReviewPolicyKeys.resolve("SHOULD_NOT_ALLOW", "arbitrary.setting") }
        val environment = MockEnvironment().withProperty(property, "INSTALLED_CASH_REVIEW")
        val configuration = CompanyReviewWorkflowConfiguration(environment,
            PayrollRunPolicyConfiguration("INSTALLED_PAYROLL"), ProcurementReviewPolicyConfiguration("INSTALLED_PO"))
        assertEquals("INSTALLED_CASH_REVIEW", configuration.key(CompanyReviewWorkflow.FINANCE_CASHBOX_OPENING))
        assertEquals("INSTALLED_PAYROLL", configuration.key(CompanyReviewWorkflow.PAYROLL_RUN))
        assertEquals("INSTALLED_PO", configuration.key(CompanyReviewWorkflow.PROCUREMENT_PO))
    }

    @Test fun aSharedInstalledKeyHasOneStableNamedAuthorityRegardlessOfSelectedWorkflow() {
        val first = CompanyReviewWorkflow.FINANCE_EMPLOYEE_ADVANCE
        val second = CompanyReviewWorkflow.FINANCE_IMPREST
        val environment = MockEnvironment().withProperty(first.configurationProperty!!, "INSTALLED_SHARED_REVIEW")
            .withProperty(second.configurationProperty!!, "INSTALLED_SHARED_REVIEW")
        val catalog = CompanyReviewWorkflowConfiguration(environment)
        assertEquals(setOf(first, second), catalog.group(first).toSet())
        assertEquals(catalog.group(first), catalog.group(second))
        assertEquals(first.authorityKey, catalog.authorityKey(first))
        assertEquals(first.authorityKey, catalog.authorityKey(second))
    }

    @Test fun aFixedSourceContractKeepsItsAuthorityWhenAnotherInstalledWorkflowSharesItsKey() {
        val source = CompanyReviewWorkflow.PAYROLL_SOURCE
        val configured = CompanyReviewWorkflow.FINANCE_EMPLOYEE_ADVANCE
        val catalog = CompanyReviewWorkflowConfiguration(MockEnvironment().withProperty(configured.configurationProperty!!, source.policyKey))
        assertEquals(setOf(source, configured), catalog.group(configured).toSet())
        assertEquals(source.authorityKey, catalog.authorityKey(configured))
        assertEquals(source.authorityKey, catalog.authorityKey(source))
    }
}

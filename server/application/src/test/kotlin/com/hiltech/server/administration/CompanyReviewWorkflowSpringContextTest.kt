package com.hiltech.server.administration

import com.hiltech.server.payroll.PayrollRunPolicyConfiguration
import com.hiltech.server.procurement.ProcurementReviewPolicyConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.mock.env.MockEnvironment

/** Exercises Spring's real constructor selection, rather than constructing the catalog in a domain fixture. */
class CompanyReviewWorkflowSpringContextTest {
    @Test fun springConstructsTheCatalogWithTheInstalledEnvironmentAndSourceConfigurations() {
        AnnotationConfigApplicationContext().use { context ->
            context.environment = MockEnvironment()
                .withProperty("hiltech.payroll.run-approval-policy-key", "INSTALLED_PAYROLL_REVIEW")
                .withProperty("hiltech.procurement.approval-policy-key", "INSTALLED_PURCHASE_REVIEW")
                .withProperty("hiltech.finance.cashbox.opening-policy-key", "INSTALLED_CASHBOX_REVIEW")
            context.register(PayrollRunPolicyConfiguration::class.java, ProcurementReviewPolicyConfiguration::class.java,
                CompanyReviewWorkflowConfiguration::class.java)
            context.refresh()
            val catalog = context.getBean(CompanyReviewWorkflowConfiguration::class.java)
            assertEquals("INSTALLED_PAYROLL_REVIEW", catalog.key(CompanyReviewWorkflow.PAYROLL_RUN))
            assertEquals("INSTALLED_PURCHASE_REVIEW", catalog.key(CompanyReviewWorkflow.PROCUREMENT_PO))
            assertEquals("INSTALLED_CASHBOX_REVIEW", catalog.key(CompanyReviewWorkflow.FINANCE_CASHBOX_OPENING))
            assertEquals(CompanyReviewWorkflow.FINANCE_CASHBOX_OPENING.authorityKey,
                catalog.authorityKey(CompanyReviewWorkflow.FINANCE_CASHBOX_OPENING))
        }
    }

    @Test fun springConstructsDefaultSourceKeysWithExactlyOneRequiredAutowiredConstructor() {
        val required = CompanyReviewWorkflowConfiguration::class.java.declaredConstructors.filterNot { it.isSynthetic }
            .filter { it.getAnnotation(Autowired::class.java)?.required == true }
        assertEquals(1, required.size)
        AnnotationConfigApplicationContext().use { context ->
            context.environment = MockEnvironment()
            context.register(PayrollRunPolicyConfiguration::class.java, ProcurementReviewPolicyConfiguration::class.java,
                CompanyReviewWorkflowConfiguration::class.java)
            context.refresh()
            val catalog = context.getBean(CompanyReviewWorkflowConfiguration::class.java)
            assertEquals("PAYROLL_RUN_REVIEW", catalog.key(CompanyReviewWorkflow.PAYROLL_RUN))
            assertEquals("PROCUREMENT_PO_REVIEW", catalog.key(CompanyReviewWorkflow.PROCUREMENT_PO))
            assertEquals("FINANCE_CASHBOX_OPENING_REVIEW", catalog.key(CompanyReviewWorkflow.FINANCE_CASHBOX_OPENING))
        }
    }
}

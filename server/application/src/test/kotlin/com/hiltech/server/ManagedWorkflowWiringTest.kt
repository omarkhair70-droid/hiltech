package com.hiltech.server

import com.hiltech.server.administration.JdbcPayrollReviewPolicyPersistence
import com.hiltech.server.payroll.Payroll9aService
import com.hiltech.server.payroll.PayrollSourceService
import com.hiltech.server.procurement.SupplierMasterDataService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertNotNull
import org.mockito.Mockito.mock
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.Environment
import java.time.Clock

/** Real Spring construction, which direct domain fixtures do not exercise. */
class ManagedWorkflowWiringTest {
    @Test fun reviewPolicyRepositoryIsConstructedBySpring() = assertWired(JdbcPayrollReviewPolicyPersistence::class.java)
    @Test fun payrollPeriodsAreConstructedBySpring() = assertWired(Payroll9aService::class.java)
    @Test fun payrollSourcesAreConstructedBySpring() = assertWired(PayrollSourceService::class.java)
    @Test fun supplierRegistryIsConstructedBySpring() = assertWired(SupplierMasterDataService::class.java)

    private fun assertWired(type: Class<*>) {
        AnnotationConfigApplicationContext().use { context ->
            // Replace only dependencies at this boundary; Spring must discover
            // and instantiate the actual managed class and resolve its values.
            type.declaredConstructors.filterNot { it.isSynthetic }
                .maxBy { it.parameterCount }.parameters.forEach { parameter ->
                    if (parameter.isAnnotationPresent(Value::class.java) ||
                        parameter.type == ObjectProvider::class.java || parameter.type == Environment::class.java
                    ) return@forEach
                    val bean = if (parameter.type == Clock::class.java) Clock.systemUTC() else mock(parameter.type)
                    context.beanFactory.registerSingleton(parameter.type.name, bean)
                }
            context.register(type)
            context.refresh()
            assertNotNull(context.getBean(type))
        }
    }
}

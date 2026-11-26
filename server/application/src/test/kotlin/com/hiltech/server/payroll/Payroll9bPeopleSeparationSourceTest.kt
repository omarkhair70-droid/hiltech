package com.hiltech.server.payroll

import com.hiltech.server.people.EmployeeState
import com.hiltech.server.people.OffboardingEmployeeContext
import com.hiltech.server.people.OffboardingPersistencePort
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDate
import java.util.UUID

class Payroll9bPeopleSeparationSourceTest {
    @Test
    fun rehiredActiveEmployeeNeverInheritsOldOffboardingEligibility() {
        val org = UUID.randomUUID()
        val employee = UUID.randomUUID()
        val people = Mockito.mock(OffboardingPersistencePort::class.java)
        Mockito.`when`(people.employee(employee, false)).thenReturn(
            OffboardingEmployeeContext(
                employeeId = employee,
                organizationId = org,
                personId = UUID.randomUUID(),
                employeeCode = "SYNTH_REHIRED",
                displayName = "Synthetic Rehired",
                state = EmployeeState.ACTIVE,
                hireDate = LocalDate.parse("2026-10-01"),
                employeeVersion = 2,
                activeEmploymentId = UUID.randomUUID(),
                employmentStartDate = LocalDate.parse("2026-10-01"),
                employmentVersion = 1,
            ),
        )
        val source = PayrollPeopleSeparationSource(
            people,
            StaticListableBeanFactory()
                .getBeanProvider(PayrollApprovedFinalPayScopeSourcePort::class.java),
            JdbcTemplate(),
        )
        assertNull(source.verifiedFor(org, UUID.randomUUID(), employee))
        Mockito.verify(people, Mockito.never()).latestForEmployee(employee)
        assertNull(source.verifiedFor(UUID.randomUUID(), UUID.randomUUID(), employee))
    }
}

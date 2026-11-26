package com.hiltech.shared.core.people

import com.hiltech.shared.core.network.ErrorEnvelope
import com.hiltech.shared.core.network.HiltechApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmployeeInitialAssignmentTest {
    private val employee = EmployeeDetailDto("employee-a", "org-a", "E01", "PREBOARDING", "Test", version = 1)
    private fun row(id: String, org: String = "org-a", state: String = "ACTIVE") =
        WorkforceAssignmentDto("assignment-$id", org, id, id, "Test $id", teamId = "team-a",
            teamName = "Team A", roleCode = "ENGINEERING", roleLabel = "Engineer", state = state,
            effectiveFrom = "2026-11-26T00:00:00Z", version = 1)

    @Test fun choicesAreCurrentSameOrganizationAndExcludeTheEmployee() {
        val choices = initialAssignmentChoices(employee, listOf(row("employee-b"), row("employee-c", "org-b"),
            row("employee-d", state = "ENDED"), row(employee.employeeId)))
        assertEquals(listOf("ENGINEERING"), choices.roles.map { it.roleCode })
        assertEquals(listOf("team-a" to "Team A"), choices.teams)
        assertEquals(listOf("employee-b" to "Test employee-b"), choices.managers)
    }

    @Test fun onlyTheSpecificMissingAssignmentResponsePermitsCreation() {
        fun error(code: String, status: Int) = HiltechApiException(code, "test", status, false,
            ErrorEnvelope(code, "Test", "test"), "Test")
        assertTrue(initialAssignmentAbsent(error("WORKFORCE_ASSIGNMENT_NOT_FOUND", 404)))
        assertFalse(initialAssignmentAbsent(error("EMPLOYEE_NOT_FOUND", 404)))
        assertFalse(initialAssignmentAbsent(error("WORKFORCE_ASSIGNMENT_NOT_FOUND", 403)))
    }

    @Test fun receiptMustMatchEmployeeOrganizationAndRequestedAssignment() {
        val request = CreateWorkforceAssignmentRequestDto("op", 1, "team-a", "ENGINEERING", "Engineer", null, "2026-11-26T00:00:00Z")
        val receipt = row(employee.employeeId)
        assertTrue(initialAssignmentReceiptMatches(employee, request, receipt))
        assertFalse(initialAssignmentReceiptMatches(employee, request, receipt.copy(organizationId = "org-b")))
        assertFalse(initialAssignmentReceiptMatches(employee, request, receipt.copy(roleCode = "OTHER")))
        assertFalse(initialAssignmentReceiptMatches(employee, request, receipt.copy(reportsToEmployeeId = "employee-b")))
    }
}

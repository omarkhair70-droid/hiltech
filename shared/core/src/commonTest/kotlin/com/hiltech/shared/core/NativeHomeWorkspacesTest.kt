package com.hiltech.shared.core

import com.hiltech.shared.core.people.EmployeeDetailDto
import com.hiltech.shared.core.people.EmployeeDirectoryDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeHomeWorkspacesTest {
    private fun workspace(id: String) = NativeOperationalWorkspace(id, id, { true }, content = {})

    @Test
    fun accountingAndSalesUseOnlyCurrentlyAuthorizedSourceRoutes() {
        val routes = listOf("finance-completion-operator", "finance-bank-statement", "commercial-read",
            "payroll-admin-read", "my-payslips", "executive-home").map(::workspace)
        val access = mapOf(
            "finance-completion-operator" to WorkspaceAvailability.AVAILABLE,
            "finance-bank-statement" to WorkspaceAvailability.UNAVAILABLE,
            "commercial-read" to WorkspaceAvailability.AVAILABLE,
            "payroll-admin-read" to WorkspaceAvailability.DENIED,
            "my-payslips" to WorkspaceAvailability.AVAILABLE,
            "executive-home" to WorkspaceAvailability.CHECKING,
        )
        val groups = nativeHomeOperationalGroups(routes, access)
        assertEquals(setOf(NativeWorkspaceGroup.Finance, NativeWorkspaceGroup.Sales), groups.keys)
        assertEquals(listOf("finance-completion-operator"), groups[NativeWorkspaceGroup.Finance]?.map { it.id })
        assertEquals(listOf("my-payslips"), nativeHomePersonalWorkspaces(routes, access).map { it.id })
        assertEquals(NativeWorkspaceGroup.Personal, nativeWorkspaceGroup("my-payslips"))
    }

    @Test
    fun ambiguousDuplicateAndRevokedRoutesNeverAppearOnHome() {
        val routes = listOf(workspace("commercial-read"), workspace("commercial-read"),
            workspace("my-payslips"), workspace("payroll-admin-read"))
        val access = routes.associate { it.id to WorkspaceAvailability.AVAILABLE }.toMutableMap()
        assertFalse(nativeHomeOperationalGroups(routes, access).values.flatten().any { it.id == "commercial-read" })
        access["payroll-admin-read"] = WorkspaceAvailability.DENIED
        access["my-payslips"] = WorkspaceAvailability.UNAVAILABLE
        assertTrue(nativeHomeOperationalGroups(routes, access).isEmpty())
        assertTrue(nativeHomePersonalWorkspaces(routes, access).isEmpty())
    }

    @Test
    fun personalProfileDoesNotGiveAccessToPeopleDirectoryOrAnotherIdentity() {
        val profile = EmployeeDetailDto("employee-a", "org-a", "E001", "ACTIVE", "Test employee",
            linkedIdentityId = "identity-a", version = 1)
        val ownOnly = HiltechPeopleState(ownProfile = profile)
        assertFalse(nativePeopleDirectoryAvailable(ownOnly))
        assertTrue(nativePeopleDirectoryAvailable(ownOnly.copy(directory = EmployeeDirectoryDto(emptyList(), true, "test"))))
        assertEquals(profile, nativeOwnEmployeeProfile(ownOnly, "identity-a", "org-a"))
        assertNull(nativeOwnEmployeeProfile(ownOnly, "identity-b", "org-a"))
        assertNull(nativeOwnEmployeeProfile(ownOnly, "identity-a", "org-b"))
        assertNull(nativeOwnEmployeeProfile(ownOnly.copy(loading = true), "identity-a", "org-a"))
    }

    @Test
    fun loadingAndNetworkFailureAreNotRepresentedAsZeroAssignments() {
        assertEquals(HomeFieldOverview(HomeFieldStatus.Loading), homeFieldOverview(HiltechFieldState(loading = true)))
        assertEquals(HomeFieldOverview(HomeFieldStatus.Unavailable), homeFieldOverview(HiltechFieldState(errorMessage = "Unavailable")))
        assertEquals(HomeFieldOverview(HomeFieldStatus.Cached, 0), homeFieldOverview(HiltechFieldState(cachedTodayAt = 1)))
        assertEquals(HomeFieldOverview(HomeFieldStatus.Current, 0), homeFieldOverview(HiltechFieldState()))
    }
}

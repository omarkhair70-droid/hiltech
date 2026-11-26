package com.hiltech.shared.core.people

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmployeeCreationDraftTest {
    private val draft = EmployeeCreationDraft(" Test employee ", " e-01 ", "2026-11-26",
        "full_time", " Official Name ", " +20 100 000 0000 ", " TEST@example.invalid ")

    @Test fun sourceRequestContainsAllEnteredIdentityFieldsWithoutGrantOrLink() {
        val request = draft.request("operation", "org-a")
        assertEquals("Test employee", request.displayName)
        assertEquals("E-01", request.employeeCode)
        assertEquals("FULL_TIME", request.employmentTypeCode)
        assertEquals("Official Name", request.legalName)
        assertEquals("+20 100 000 0000", request.mobile)
        assertEquals("test@example.invalid", request.email)
        assertNull(request.linkedUserIdentityId)
        assertNull(request.hireDate)
    }

    @Test fun exactNormalizedIntentIsStableAndEveryChangedFieldOrScopeChangesSeed() {
        val seed = draft.operationSeed("actor-a", "org-a")
        assertEquals(seed, draft.normalized().operationSeed("actor-a", "org-a"))
        assertNotEquals(seed, draft.copy(email = "other@example.invalid").operationSeed("actor-a", "org-a"))
        assertNotEquals(seed, draft.copy(mobile = "100").operationSeed("actor-a", "org-a"))
        assertNotEquals(seed, draft.copy(legalName = "Different").operationSeed("actor-a", "org-a"))
        assertNotEquals(seed, draft.operationSeed("actor-b", "org-a"))
        assertNotEquals(seed, draft.operationSeed("actor-a", "org-b"))
    }

    @Test fun invalidInputCannotBecomeACommand() {
        assertTrue(draft.isValid())
        assertFalse(draft.copy(startDate = "2026-02-30").isValid())
        assertFalse(draft.copy(displayName = " ").isValid())
        assertFalse(draft.copy(email = "not an email").isValid())
        assertFalse(draft.copy(employmentTypeCode = "unknown/type").isValid())
        assertTrue(draft.copy(email = "", employmentTypeCode = null).isValid())
    }

    @Test fun observedTypesAreNotAnInventedCatalog() {
        fun row(code: String?, id: String) = EmployeeDirectoryItemDto(id, id, "Test", "ACTIVE", false,
            currentEmploymentTypeCode = code, version = 1)
        val source = EmployeeDirectoryDto(listOf(row("CUSTOM_CONTRACT", "a"), row("CUSTOM_CONTRACT", "b"),
            row(null, "c"), row("invalid/type", "d")), true, "test")
        assertEquals(listOf("CUSTOM_CONTRACT"), observedEmploymentTypes(source))
        assertTrue(observedEmploymentTypes(null).isEmpty())
    }
}

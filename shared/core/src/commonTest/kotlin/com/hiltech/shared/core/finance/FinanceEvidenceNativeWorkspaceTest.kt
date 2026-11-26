package com.hiltech.shared.core.finance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FinanceEvidenceNativeWorkspaceTest {
    private val org = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private val anotherOrg = "bbbbbbbb-cccc-4ddd-8eee-ffffffffffff"
    private val proposal = "11111111-2222-4333-8444-555555555555"
    private val anotherProposal = "22222222-3333-4444-8555-666666666666"

    @Test
    fun onlyRealUuidMayStartFinanceSourceLookup() {
        assertTrue(financeLookupIdIsUuid(proposal))
        assertFalse(financeLookupIdIsUuid(""))
        assertFalse(financeLookupIdIsUuid("not-a-uuid"))
        assertFalse(financeLookupIdIsUuid(proposal + "/record"))
        assertFalse(financeLookupIdIsUuid(" " + proposal))
        assertFalse(financeLookupIdIsUuid("00000000000000000000000000000000"))
    }

    @Test
    fun currentResponseMustMatchBothOrganizationAndRequestedRecord() {
        assertTrue(financeReadSourceMatches(org, org, proposal, proposal))
        assertFalse(financeReadSourceMatches(org, anotherOrg, proposal, proposal))
        assertFalse(financeReadSourceMatches(org, org, proposal, anotherProposal))
        assertFalse(financeReadSourceMatches(org, anotherOrg, proposal, anotherProposal))
        assertFalse(financeReadSourceMatches("", "", proposal, proposal))
        assertFalse(financeReadSourceMatches(org, org, "", ""))
    }

    @Test
    fun financeOperationLabelsRemainUniqueAndExplicit() {
        val kinds = FinanceReadKind.entries
        assertEquals(15, kinds.size)
        assertEquals(kinds.size, kinds.map { it.label }.distinct().size)
        assertTrue(kinds.all { it.label.isNotBlank() })
        assertTrue(kinds.contains(FinanceReadKind.BANK_RECONCILIATION))
        assertTrue(kinds.contains(FinanceReadKind.CASH_COUNT))
        assertTrue(kinds.contains(FinanceReadKind.EMPLOYEE_SETTLEMENT))
    }
}

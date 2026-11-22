package com.hiltech.shared.core.procurement

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PurchaseRequirementFormRulesTest {
    @Test fun validPositiveQuantitiesNeedAnExplanation() {
        assertTrue(canCreatePurchaseDraft("3", "Cable installation", serialized = false))
        assertTrue(canCreatePurchaseDraft("0.125", "Allowance", serialized = false))
        assertFalse(canCreatePurchaseDraft("0", "Not permitted", serialized = false))
        assertFalse(canCreatePurchaseDraft("0.000", "Not permitted", serialized = false))
        assertFalse(canCreatePurchaseDraft("-5", "Not permitted", serialized = false))
        assertFalse(canCreatePurchaseDraft("2e3", "Not permitted", serialized = false))
        assertFalse(canCreatePurchaseDraft("1", "  ", serialized = false))
        assertFalse(canCreatePurchaseDraft("1", "z".repeat(501), serialized = false))
    }

    @Test fun serializedItemsRequireWholeUnits() {
        assertTrue(canCreatePurchaseDraft("2.000", "Replacement", serialized = true))
        assertFalse(canCreatePurchaseDraft("2.25", "Replacement", serialized = true))
    }
}

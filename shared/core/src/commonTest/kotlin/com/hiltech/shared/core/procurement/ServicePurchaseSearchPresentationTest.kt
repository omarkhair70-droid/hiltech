package com.hiltech.shared.core.procurement

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServicePurchaseSearchPresentationTest {
    @Test
    fun onlyExactSubmittedSupplierSearchForSameRfqMayBeShown() {
        assertTrue(serviceSupplierResultsBelongToCurrentQuery(" Cairo Metals ", "Cairo Metals", "rfq-11", "rfq-11"))
        assertFalse(serviceSupplierResultsBelongToCurrentQuery("Cairo", "Cairo Metals", "rfq-11", "rfq-11"))
        assertFalse(serviceSupplierResultsBelongToCurrentQuery("cairo metals", "Cairo Metals", "rfq-11", "rfq-11"))
        assertFalse(serviceSupplierResultsBelongToCurrentQuery("Cairo Metals", "Cairo Metals", "rfq-12", "rfq-11"))
    }

    @Test
    fun missingOrBlankQueryAndMissingRfqNeverExposeCachedSupplier() {
        assertFalse(serviceSupplierResultsBelongToCurrentQuery("", null, "rfq-11", "rfq-11"))
        assertFalse(serviceSupplierResultsBelongToCurrentQuery("  ", "", "rfq-11", "rfq-11"))
        assertFalse(serviceSupplierResultsBelongToCurrentQuery("Cairo Metals", "Cairo Metals", null, "rfq-11"))
        assertFalse(serviceSupplierResultsBelongToCurrentQuery("Cairo Metals", "Cairo Metals", "rfq-11", null))
    }

    @Test
    fun lateResponseCannotRepopulateResultsAfterInputOrSourceChanged() {
        assertTrue(serviceSupplierSearchResponseIsCurrent(3, 3, "Supplier A", "Supplier A", "rfq-11", "rfq-11"))
        assertFalse(serviceSupplierSearchResponseIsCurrent(4, 3, "Supplier A", "Supplier A", "rfq-11", "rfq-11"))
        assertFalse(serviceSupplierSearchResponseIsCurrent(3, 3, "Supplier B", "Supplier A", "rfq-11", "rfq-11"))
        assertFalse(serviceSupplierSearchResponseIsCurrent(3, 3, "Supplier A", "Supplier A", "rfq-12", "rfq-11"))
    }
}

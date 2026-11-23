package com.hiltech.shared.core.procurement

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServicePurchaseFormPresentationTest {
    @Test
    fun revokedOrBusyServiceNeedNeverOffersAnActionEvenWithCachedPermission() {
        assertTrue(serviceNeedActionEnabled(sourceCurrent = true, permittedBySource = true, busy = false))
        assertFalse(serviceNeedActionEnabled(sourceCurrent = false, permittedBySource = true, busy = false))
        assertFalse(serviceNeedActionEnabled(sourceCurrent = true, permittedBySource = false, busy = false))
        assertFalse(serviceNeedActionEnabled(sourceCurrent = true, permittedBySource = true, busy = true))
        assertFalse(serviceNeedActionEnabled(sourceCurrent = false, permittedBySource = false, busy = true))
    }

    @Test
    fun selectedOrderIsRevokedWhenCurrentSourceListChanges() {
        assertTrue(servicePurchaseOrderStillAvailable("order-1", listOf("order-1", "order-2")))
        assertFalse(servicePurchaseOrderStillAvailable("order-1", listOf("order-2")))
        assertFalse(servicePurchaseOrderStillAvailable("order-1", emptyList()))
        assertFalse(servicePurchaseOrderStillAvailable(null, listOf("order-1")))
        assertFalse(servicePurchaseOrderStillAvailable(" ", listOf("order-1")))
    }

    @Test
    fun invoiceCaptureRequiresBoundedDecimalStringsAndExactFormat() {
        assertTrue(serviceInvoiceInputIsWellFormed("INV-123", "2026-11-23", "EGP", "100.25", "0"))
        assertTrue(serviceInvoiceInputIsWellFormed("INV-123", "2026-11-23", "USD", "0", "0.000001"))
        assertFalse(serviceInvoiceInputIsWellFormed("INV-123", "2026-11-23", "EGP", "NaN", "0"))
        assertFalse(serviceInvoiceInputIsWellFormed("INV-123", "2026-11-23", "EGP", "Infinity", "0"))
        assertFalse(serviceInvoiceInputIsWellFormed("INV-123", "2026-11-23", "EGP", "-2", "0"))
        assertFalse(serviceInvoiceInputIsWellFormed("INV-123", "2026-11-23", "EGP", "1e9", "0"))
        assertFalse(serviceInvoiceInputIsWellFormed("INV-123", "2026-11-23", "EGP", "5", "0.0000001"))
        assertFalse(serviceInvoiceInputIsWellFormed("INV-123", "2026-11-23", "EGP", "0005", "0"))
    }

    @Test
    fun invoiceDateMustExistOnGregorianCalendarIncludingLeapYears() {
        assertTrue(serviceInvoiceDateIsReal("2028-02-29"))
        assertTrue(serviceInvoiceDateIsReal("2000-02-29"))
        assertFalse(serviceInvoiceDateIsReal("2026-02-29"))
        assertFalse(serviceInvoiceDateIsReal("1900-02-29"))
        assertFalse(serviceInvoiceDateIsReal("2026-04-31"))
        assertFalse(serviceInvoiceDateIsReal("2026-13-01"))
        assertFalse(serviceInvoiceDateIsReal("2026-00-10"))
        assertFalse(serviceInvoiceDateIsReal("0000-01-01"))
        assertFalse(serviceInvoiceDateIsReal("2026-11-00"))
        assertFalse(serviceInvoiceInputIsWellFormed("INV-123", "2026-02-29", "EGP", "100", "0"))
        assertTrue(serviceInvoiceInputIsWellFormed("INV-123", "2028-02-29", "EGP", "100", "0"))
    }

    @Test
    fun invoiceCaptureRejectsUnformattedSourceMetadata() {
        assertFalse(serviceInvoiceInputIsWellFormed("X", "2026-11-23", "EGP", "5", "0"))
        assertFalse(serviceInvoiceInputIsWellFormed("INV-123", "23/11/2026", "EGP", "5", "0"))
        assertFalse(serviceInvoiceInputIsWellFormed("INV-123", "2026-11-23", "egp", "5", "0"))
        assertFalse(serviceInvoiceInputIsWellFormed("INV-123", "2026-11-23", "EGPX", "5", "0"))
        assertFalse(serviceInvoiceInputIsWellFormed("INV-123", "2026-11-23", "EGP", "5", ""))
    }
}

package com.hiltech.shared.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WarehouseQuickScanQueryTest {
    @Test
    fun resultCanOnlyBeDisplayedForExactSubmittedQuery() {
        assertTrue(warehouseScanResultMatchesQuery("ASSET_012", "ASSET_012"))
        assertTrue(warehouseScanResultMatchesQuery("  ASSET_012  ", "ASSET_012"))
        assertFalse(warehouseScanResultMatchesQuery("ASSET_013", "ASSET_012"))
        assertFalse(warehouseScanResultMatchesQuery("asset_012", "ASSET_012"))
    }

    @Test
    fun changingToAnotherScanOrSearchCannotReuseStaleResult() {
        assertFalse(warehouseScanResultMatchesQuery("ITEM-002", "ITEM-001"))
        assertFalse(warehouseScanResultMatchesQuery("PT-55", "PT-55-old"))
        assertFalse(warehouseScanResultMatchesQuery("anything", null))
    }

    @Test
    fun clearingInputDoesNotShowPreviousAssetOrStockResult() {
        assertFalse(warehouseScanResultMatchesQuery("", "ASSET_012"))
        assertFalse(warehouseScanResultMatchesQuery("  ", "ITEM-001"))
        assertFalse(warehouseScanResultMatchesQuery("", null))
        assertFalse(warehouseScanResultMatchesQuery("", ""))
    }
}

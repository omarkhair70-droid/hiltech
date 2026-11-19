package com.hiltech.android

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.hiltech.shared.core.HiltechShell
import com.hiltech.shared.core.HiltechShellState
import com.hiltech.shared.core.StocktakeHumanProofSection
import com.hiltech.shared.core.identity.IdentityBootstrapDto
import com.hiltech.shared.core.warehouse.InventoryAdjustmentRequestDto
import com.hiltech.shared.core.warehouse.InventoryDiscrepancyDto
import com.hiltech.shared.core.warehouse.StockCountRecordDto
import com.hiltech.shared.core.warehouse.StocktakeDetailDto
import com.hiltech.shared.core.warehouse.StocktakeSessionDto
import com.hiltech.shared.core.warehouse.StocktakeSnapshotLineDto
import com.hiltech.shared.core.warehouse.StocktakeVariancesDto

/** DEBUG-only mobile proof. Synthetic transitions exercise the production shared Stocktake controls. */
class StocktakeEvidenceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val actor = "99999999-9999-4999-8999-999999999999"
        val organization = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        val warehouse = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val location = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val item = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        val sessionId = "11111111-2222-4333-8444-555555555555"
        val snapshotId = "22222222-3333-4444-8555-666666666666"
        val discrepancyId = "55555555-6666-4777-8888-999999999999"
        val at = "2026-11-19T10:00:00Z"

        val session = StocktakeSessionDto(
            id = sessionId,
            organizationId = organization,
            warehouseId = warehouse,
            rootStorageLocationId = location,
            state = "ACTIVE",
            stockItemIds = listOf(item),
            snapshotAt = at,
            createdBy = actor,
            createdAt = "2026-11-19T09:59:00Z",
            correlationId = "synthetic-mobile-stocktake",
            version = 2,
        )
        val snapshot = StocktakeSnapshotLineDto(
            id = snapshotId,
            sessionId = sessionId,
            organizationId = organization,
            stockItemId = item,
            itemCode = "SYNTH-COUNT-CABLE",
            storageLocationId = location,
            locationCode = "SYNTH-BIN-01",
            expectedOnHand = "6",
            expectedReserved = "0",
            expectedDamaged = "0",
            expectedQuarantine = "0",
            unitCode = "EA",
            balanceVersion = 4,
            itemVersion = 2,
            snapshotAt = at,
        )
        val status = TextView(this).apply {
            text = "SYNTH-STOCKTAKE-READY"
            textSize = 14f
        }
        val content = ComposeView(this).apply {
            setContent {
                var detail by remember {
                    mutableStateOf(
                        StocktakeDetailDto(
                            session = session,
                            snapshotLines = listOf(snapshot),
                            countRecords = emptyList(),
                            discrepancies = emptyList(),
                            adjustmentRequests = emptyList(),
                        ),
                    )
                }
                var variances by remember { mutableStateOf<StocktakeVariancesDto?>(null) }

                HiltechShell(
                    state = HiltechShellState.SignedIn(
                        identity = IdentityBootstrapDto(
                            identityId = actor,
                            identityStatus = "ACTIVE",
                            identityVersion = 1,
                            organizations = emptyList(),
                            teams = emptyList(),
                        ),
                    ),
                    warehouseOperator = {
                        StocktakeHumanProofSection(
                            detail = detail,
                            variances = variances,
                            busy = false,
                            onCount = { line, quantity, scanSource, notes ->
                                check(line.id == snapshotId)
                                check(quantity in setOf("7", "5"))
                                val previous = detail.countRecords.lastOrNull()
                                val recount = previous != null
                                val count = StockCountRecordDto(
                                    id = if (recount) {
                                        "44444444-5555-4666-8777-888888888888"
                                    } else {
                                        "33333333-4444-4555-8666-777777777777"
                                    },
                                    sessionId = sessionId,
                                    snapshotLineId = snapshotId,
                                    organizationId = organization,
                                    stockItemId = item,
                                    storageLocationId = location,
                                    countedQuantity = quantity,
                                    unitCode = "EA",
                                    countedBy = actor,
                                    countedAt = if (recount) "2026-11-19T10:07:00Z" else "2026-11-19T10:05:00Z",
                                    scanSource = scanSource,
                                    notes = notes,
                                    supersedesCountRecordId = previous?.id,
                                    operationId = if (recount) {
                                        "88888888-9999-4aaa-8bbb-cccccccccccc"
                                    } else {
                                        "77777777-8888-4999-8aaa-bbbbbbbbbbbb"
                                    },
                                    correlationId = "synthetic-mobile-stocktake",
                                )
                                detail = detail.copy(countRecords = detail.countRecords + count)
                                status.text = if (recount) {
                                    "SYNTH-STOCKTAKE-RECOUNT-RECEIPT"
                                } else {
                                    "SYNTH-STOCKTAKE-COUNT-RECEIPT"
                                }
                            },
                            onSubmit = {
                                val latest = detail.countRecords.last()
                                check(latest.countedQuantity == "5")
                                val discrepancy = InventoryDiscrepancyDto(
                                    id = discrepancyId,
                                    sessionId = sessionId,
                                    snapshotLineId = snapshotId,
                                    latestCountRecordId = latest.id,
                                    organizationId = organization,
                                    stockItemId = item,
                                    storageLocationId = location,
                                    expectedQuantity = "6",
                                    countedQuantity = "5",
                                    varianceQuantity = "-1",
                                    snapshotBalanceVersion = 4,
                                    currentBalanceVersion = 4,
                                    sourceChangedSinceSnapshot = false,
                                    state = "OPEN",
                                    createdBy = actor,
                                    createdAt = "2026-11-19T10:08:00Z",
                                    updatedAt = "2026-11-19T10:08:00Z",
                                    version = 1,
                                )
                                val submitted = detail.session.copy(state = "SUBMITTED", version = 3)
                                detail = detail.copy(session = submitted, discrepancies = listOf(discrepancy))
                                variances = StocktakeVariancesDto(submitted, listOf(discrepancy), emptyList())
                                status.text = "SYNTH-STOCKTAKE-SUBMIT-RECEIPT"
                            },
                            onRequestAdjustment = { discrepancy, reason ->
                                check(discrepancy.id == discrepancyId)
                                check(reason == "Verified shortage")
                                val request = InventoryAdjustmentRequestDto(
                                    id = "66666666-7777-4888-8999-aaaaaaaaaaaa",
                                    discrepancyId = discrepancy.id,
                                    sessionId = sessionId,
                                    organizationId = organization,
                                    stockItemId = item,
                                    storageLocationId = location,
                                    deltaQuantity = "-1",
                                    unitCode = "EA",
                                    reason = reason,
                                    requestedBy = actor,
                                    requestedAt = "2026-11-19T10:09:00Z",
                                    version = 1,
                                )
                                val updated = discrepancy.copy(state = "ADJUSTMENT_REQUESTED", version = 2)
                                detail = detail.copy(
                                    discrepancies = listOf(updated),
                                    adjustmentRequests = listOf(request),
                                )
                                variances = StocktakeVariancesDto(
                                    detail.session,
                                    listOf(updated),
                                    listOf(request),
                                )
                                status.text = "SYNTH-STOCKTAKE-ADJUSTMENT-RECEIPT"
                            },
                        )
                    },
                )
            }
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(status, LinearLayout.LayoutParams(-1, -2))
                addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
            },
        )
    }
}

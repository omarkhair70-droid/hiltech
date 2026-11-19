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
import com.hiltech.shared.core.ReceivingDirectorySection
import com.hiltech.shared.core.ReceivingHumanProofSection
import com.hiltech.shared.core.identity.IdentityBootstrapDto
import com.hiltech.shared.core.warehouse.*

/** DEBUG-only human proof. Synthetic transitions exercise the production shared Receiving controls. */
class ReceivingEvidenceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val recordId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val lineId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val actor = "99999999-9999-4999-8999-999999999999"
        val at = "2026-11-19T06:45:00Z"
        val item = StockItemDto(
            id = "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
            organizationId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
            itemCode = "SYNTH-RX-SERIAL",
            name = "Synthetic serialized receiving stock",
            categoryDefinitionId = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
            categoryRevision = 1,
            baseUnitCode = "EA",
            classification = "STOCK_SERIALIZED",
            serialized = true,
            lotTracked = false,
            active = true,
            version = 3,
        )
        val initialRecord = ReceivingRecordDto(
            id = recordId,
            organizationId = item.organizationId,
            destinationStorageLocationId = "ffffffff-ffff-4fff-8fff-ffffffffffff",
            sourceType = "OTHER",
            sourceRef = "SYNTH-MOBILE",
            deliveryDocumentRef = "SYNTH-MOBILE-DOC",
            state = "IN_PROGRESS",
            createdBy = actor,
            createdAt = "2026-11-19T06:30:00Z",
            updatedAt = "2026-11-19T06:30:00Z",
            correlationId = "synthetic-mobile-receiving",
            version = 1,
        )
        val initialLine = ReceivingLineDto(
            id = lineId,
            receivingRecordId = recordId,
            organizationId = item.organizationId,
            candidateClass = "STOCK_SERIALIZED",
            expectedStockItemId = item.id,
            expectedQuantity = "2.000000",
            unitCode = item.baseUnitCode,
            sourceLineRef = "MOBILE-LINE-01",
            version = 1,
        )
        val directory = ReceivingDirectoryDto(listOf(initialRecord), false)
        val status = TextView(this).apply {
            text = "SYNTH-RECEIVING-READY"
            textSize = 14f
        }
        val content = ComposeView(this).apply {
            setContent {
                var search by remember { mutableStateOf("") }
                var opened by remember { mutableStateOf(false) }
                var detail by remember {
                    mutableStateOf(ReceivingDetailDto(initialRecord, listOf(initialLine), emptyList()))
                }
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
                        if (!opened) {
                            ReceivingDirectorySection(
                                directory = directory,
                                selectedId = null,
                                busy = false,
                                search = search,
                                changeSearch = { search = it },
                                open = {
                                    check(it == recordId)
                                    opened = true
                                    status.text = "SYNTH-RECEIVING-SEARCH-PASS"
                                },
                            )
                        } else {
                            ReceivingHumanProofSection(
                                detail = detail,
                                stockItems = listOf(item),
                                assetCatalog = AssetCatalogDto(emptyList(), false),
                                receiveStock = { line, quantity, condition, serials ->
                                    check(line.id == lineId)
                                    check(quantity == "2.000000")
                                    check(condition == AssetCondition.GOOD)
                                    check(serials == listOf("SER-001", "SER-002"))
                                    val received = line.copy(
                                        receivedQuantity = quantity,
                                        condition = condition,
                                        serials = serials,
                                        receivedBy = actor,
                                        receivedAt = at,
                                        version = line.version + 1,
                                    )
                                    detail = detail.copy(
                                        record = detail.record.copy(updatedAt = at, version = detail.record.version + 1),
                                        lines = listOf(received),
                                    )
                                    status.text = "SYNTH-RECEIVING-STOCK-RECEIPT"
                                },
                                receiveAsset = { _, _, _, _, _, _, _, _, _ ->
                                    error("Serialized Stock must not become Asset in mobile proof.")
                                },
                                discrepancy = { scope, type, description ->
                                    check(scope == null)
                                    check(type == "SHORT_QTY")
                                    check(description == "Short delivery")
                                    val discrepancy = ReceivingDiscrepancyDto(
                                        id = "12121212-1212-4212-8212-121212121212",
                                        receivingRecordId = recordId,
                                        receivingLineId = null,
                                        organizationId = item.organizationId,
                                        type = type,
                                        description = description,
                                        createdBy = actor,
                                        createdAt = at,
                                        version = 1,
                                    )
                                    detail = detail.copy(
                                        record = detail.record.copy(updatedAt = at, version = detail.record.version + 1),
                                        discrepancies = listOf(discrepancy),
                                    )
                                    status.text = "SYNTH-RECEIVING-DISCREPANCY-RECEIPT"
                                },
                                complete = {
                                    check(detail.lines.all { it.receivedAt != null })
                                    check(detail.discrepancies.single().type == "SHORT_QTY")
                                    detail = detail.copy(
                                        record = detail.record.copy(
                                            state = "RECEIVED_WITH_DISCREPANCY",
                                            receivedBy = actor,
                                            receivedAt = at,
                                            updatedAt = at,
                                            version = detail.record.version + 1,
                                        ),
                                    )
                                    status.text = "SYNTH-RECEIVING-FINAL-RECEIPT · RECEIVED_WITH_DISCREPANCY"
                                },
                            )
                        }
                    },
                )
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status, LinearLayout.LayoutParams(-1, -2))
            addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        })
    }
}

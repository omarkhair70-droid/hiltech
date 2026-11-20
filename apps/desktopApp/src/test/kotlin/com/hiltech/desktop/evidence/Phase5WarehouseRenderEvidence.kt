package com.hiltech.desktop.evidence

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.AssetInventoryState
import com.hiltech.shared.core.AssetLookupState
import com.hiltech.shared.core.AssetPassportSection
import com.hiltech.shared.core.AssetServiceQueue
import com.hiltech.shared.core.ProjectResourceResults
import com.hiltech.shared.core.WarehouseReservationResults
import com.hiltech.shared.core.ReceivingHumanProofSection
import com.hiltech.shared.core.StocktakeHumanProofSection
import com.hiltech.shared.core.warehouse.InventoryAdjustmentRequestDto
import com.hiltech.shared.core.warehouse.InventoryDiscrepancyDto
import com.hiltech.shared.core.warehouse.StockCountRecordDto
import com.hiltech.shared.core.warehouse.StocktakeDetailDto
import com.hiltech.shared.core.warehouse.StocktakeSessionDto
import com.hiltech.shared.core.warehouse.StocktakeSnapshotLineDto
import com.hiltech.shared.core.warehouse.StocktakeVariancesDto
import com.hiltech.shared.core.warehouse.RequirementCandidatesDto
import com.hiltech.shared.core.warehouse.ReservationDto
import com.hiltech.shared.core.warehouse.ReceivingDetailDto
import com.hiltech.shared.core.warehouse.ReceivingDiscrepancyDto
import com.hiltech.shared.core.warehouse.ReceivingLineDto
import com.hiltech.shared.core.warehouse.ReceivingRecordDto
import com.hiltech.shared.core.warehouse.ResourceCandidateDto
import com.hiltech.shared.core.warehouse.WarehouseReservationQueueDto
import com.hiltech.shared.core.warehouse.WarehouseWorkRequestDto
import com.hiltech.shared.core.warehouse.WorkResourceCandidatesDto
import com.hiltech.shared.core.warehouse.WorkResourceDemandDto
import com.hiltech.shared.core.AssetServiceDetail
import com.hiltech.shared.core.warehouse.AssetAvailabilityDto
import com.hiltech.shared.core.warehouse.AssetAvailabilityReasonDto
import com.hiltech.shared.core.warehouse.AssetMaintenanceDto
import com.hiltech.shared.core.warehouse.AssetServiceCapabilitiesDto
import com.hiltech.shared.core.warehouse.AssetServiceDetailDto
import com.hiltech.shared.core.warehouse.AssetServiceQueueEntryDto
import com.hiltech.shared.core.AssetTagLookupSection
import com.hiltech.shared.core.HiltechWarehouseState
import com.hiltech.shared.core.warehouse.AssetCatalogDto
import com.hiltech.shared.core.warehouse.AssetCondition
import com.hiltech.shared.core.warehouse.AssetCustodyDto
import com.hiltech.shared.core.warehouse.AssetIdentityDto
import com.hiltech.shared.core.warehouse.AssetMovementDto
import com.hiltech.shared.core.warehouse.AssetPassportDto
import com.hiltech.shared.core.warehouse.AssetTagDto
import com.hiltech.shared.core.warehouse.AssetTagLookupDto
import com.hiltech.shared.core.warehouse.AssetTypeDto
import com.hiltech.shared.core.StockInventorySection
import com.hiltech.shared.core.PhysicalAssetOperations
import com.hiltech.shared.core.IssuedStockOperations
import com.hiltech.shared.core.warehouse.IssuedStockContextDto
import com.hiltech.shared.core.warehouse.IssuedStockDirectoryDto
import com.hiltech.shared.core.warehouse.IssuedStockDirectoryEntryDto
import com.hiltech.shared.core.StockInventoryState
import com.hiltech.shared.core.WarehouseSetupSection
import com.hiltech.shared.core.WarehouseCommandCenterSection
import com.hiltech.shared.core.WarehouseQuickScanControls
import com.hiltech.shared.core.WarehouseQuickScanState
import com.hiltech.shared.core.warehouse.WarehouseCommandCenterDto
import com.hiltech.shared.core.warehouse.WarehouseCommandCenterItemDto
import com.hiltech.shared.core.warehouse.WarehouseCommandCenterQueueDto
import com.hiltech.shared.core.warehouse.WarehouseInventoryOverviewDto
import com.hiltech.shared.core.warehouse.InventoryImportBatchDto
import com.hiltech.shared.core.warehouse.InventoryImportRowDto
import com.hiltech.shared.core.warehouse.StockBalanceDto
import com.hiltech.shared.core.warehouse.StockCatalogDto
import com.hiltech.shared.core.warehouse.StockCategoryDto
import com.hiltech.shared.core.warehouse.StockItemDto
import com.hiltech.shared.core.warehouse.StockMovementDto
import com.hiltech.shared.core.warehouse.StockUnitDto
import com.hiltech.shared.core.warehouse.StorageLocationDto
import com.hiltech.shared.core.warehouse.StorageLocationListDto
import com.hiltech.shared.core.warehouse.WarehouseDto
import com.hiltech.shared.core.warehouse.WarehouseListDto
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalComposeUiApi::class)
fun main() = runBlocking<Unit> {
    val directory = Path.of(
        System.getenv("HILTECH_PHASE5_WAREHOUSE_EVIDENCE_DIR") ?: "build/reports/phase5-warehouse",
    )
    Files.createDirectories(directory)
    val scroll = ScrollState(0)
    val state = syntheticWarehouse()
    val scene = ImageComposeScene(width = 940, height = 940, coroutineContext = coroutineContext) {
        Column(
            Modifier.fillMaxSize().background(Color.White).verticalScroll(scroll).padding(24.dp),
        ) {
            WarehouseSetupSection(state = state)
        }
    }
    try {
        captureScene(scene, directory.resolve("warehouse-setup.png"))
        check(scroll.maxValue in 1 until Int.MAX_VALUE)
        scroll.scrollTo(scroll.maxValue)
        captureScene(scene, directory.resolve("import-review.png"))
        println("HILTECH_PHASE5_WAREHOUSE_RENDER_CAPTURED synthetic=true")
    } finally {
        scene.close()
    }
    val commandCenter = WarehouseCommandCenterDto(
        warehouseId = requireNotNull(state.selectedWarehouseId),
        organizationId = requireNotNull(state.directory).items.single().organizationId,
        warehouseCode = "TEST-WH",
        warehouseName = "Synthetic warehouse",
        asOf = "2026-11-20T00:00:00Z",
        inventory = WarehouseInventoryOverviewDto(4, 3, 1, 2, false, "LOW_STOCK_THRESHOLD_NOT_CONFIGURED"),
        dueReturns = WarehouseCommandCenterQueueDto(listOf(
            WarehouseCommandCenterItemDto("ASSET_MOVEMENT", "return-source", "SYNTH-TOOL", "OVERDUE",
                listOf("RETURN_OVERDUE"), dueAt = "2026-11-19T16:00:00Z", workOrderId = "synthetic-work",
                drillType = "ASSET", drillId = "synthetic-asset"),
        ), false),
        activeReservations = WarehouseCommandCenterQueueDto(listOf(
            WarehouseCommandCenterItemDto("STOCK_RESERVATION", "reservation-source", "FIBER-01", "ACTIVE",
                listOf("ACTIVE_RESERVATION"), workOrderId = "synthetic-work",
                drillType = "STOCK_ITEM", drillId = "synthetic-stock-item"),
        ), false),
        assetExceptions = WarehouseCommandCenterQueueDto(listOf(
            WarehouseCommandCenterItemDto("ASSET_CALIBRATION", "calibration-source", "SYNTH-METER",
                "BLOCKED_CALIBRATION", listOf("CALIBRATION_EXPIRED"), dueAt = "2026-11-18T12:00:00Z"),
        ), false),
        receivingQueue = WarehouseCommandCenterQueueDto(listOf(
            WarehouseCommandCenterItemDto("RECEIVING", "receiving-source", "OTHER · delivery-17",
                "RECEIVED_WITH_DISCREPANCY", listOf("RECEIVING_RECEIVED_WITH_DISCREPANCY"),
                drillType = "RECEIVING", drillId = "receiving-source"),
        ), false),
        stocktakeQueue = WarehouseCommandCenterQueueDto(listOf(
            WarehouseCommandCenterItemDto("INVENTORY_ADJUSTMENT_REQUEST", "adjustment-source",
                "FIBER-01 · adjustment", "PENDING", listOf("ADJUSTMENT_PENDING"),
                drillType = "STOCKTAKE", drillId = "synthetic-stocktake"),
        ), false),
        importExceptions = WarehouseCommandCenterQueueDto(listOf(
            WarehouseCommandCenterItemDto("INVENTORY_IMPORT_ROW", "import-row-source", "LEGACY-PART",
                "NEEDS_REVIEW", listOf("UNKNOWN_UOM","LEGACY_COLUMN_SEMANTICS_AMBIGUOUS")),
        ), false),
        recentMovements = WarehouseCommandCenterQueueDto(listOf(
            WarehouseCommandCenterItemDto("STOCK_MOVEMENT", "movement-source", "FIBER-01",
                "ISSUE", listOf("ISSUE"), occurredAt = "2026-11-19T23:00:00Z", workOrderId = "synthetic-work",
                drillType = "STOCK_ITEM", drillId = "synthetic-stock-item"),
        ), false),
        projectResourceReadiness = com.hiltech.shared.core.warehouse.WarehouseProjectResourceReadinessQueueDto(
            visible = true,
            items = listOf(
                com.hiltech.shared.core.warehouse.WarehouseProjectResourceReadinessItemDto(
                    workOrderId = "synthetic-work",
                    workOrderCode = "SYNTH-WORK",
                    title = "Synthetic installation",
                    projectId = "synthetic-project",
                    siteId = "synthetic-site",
                    requirementId = "synthetic-material-requirement",
                    requirementKey = "FIBER-MATERIAL",
                    requirementFamily = "MATERIAL",
                    resourceKind = "STOCK",
                    state = "BLOCKED",
                    reasonCode = "STOCK_RESERVED_FOR_OTHER_WORK",
                    factSourceId = "synthetic-stock-item",
                    factSourceVersion = 3,
                    sourceAsOf = "2026-11-20T00:00:00Z",
                    candidateCount = 1,
                ),
                com.hiltech.shared.core.warehouse.WarehouseProjectResourceReadinessItemDto(
                    workOrderId = "synthetic-work-2",
                    workOrderCode = "SYNTH-WORK-2",
                    title = "Synthetic cabinet installation",
                    projectId = "synthetic-project",
                    siteId = "synthetic-site",
                    requirementId = "synthetic-asset-requirement",
                    requirementKey = "FIBER-TESTER",
                    requirementFamily = "ASSET",
                    resourceKind = "ASSET",
                    state = "READY_TO_RESERVE",
                    reasonCode = "ASSET_AVAILABLE",
                    factSourceId = "synthetic-asset",
                    factSourceVersion = 4,
                    sourceAsOf = "2026-11-20T00:00:00Z",
                    candidateCount = 1,
                ),
            ),
            limited = false,
        ),
    )
    val commandCenterScene = ImageComposeScene(width = 980, height = 1320, coroutineContext = coroutineContext) {
        Column(Modifier.fillMaxSize().background(Color.White).padding(24.dp)) {
            WarehouseCommandCenterSection(commandCenter)
        }
    }
    captureAndClose(commandCenterScene, directory.resolve("warehouse-command-center.png"))

    val quickAssetId = "aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa"
    val quickState = WarehouseQuickScanState(
        assetTagResult = AssetTagLookupDto(
            assetCode = "SYNTH-PASSPORT",
            name = "Synthetic instrument",
            typeName = "Synthetic Asset type",
            serialNumber = "SYNTH-SERIAL",
            condition = AssetCondition.GOOD,
            locationCode = "TEST-WH-1",
            assetId = quickAssetId,
        ),
        assetSearchResults = listOf(
            AssetIdentityDto(
                id = quickAssetId,
                organizationId = requireNotNull(state.directory).items.single().organizationId,
                warehouseId = requireNotNull(state.selectedWarehouseId),
                assetCode = "SYNTH-PASSPORT",
                name = "Synthetic instrument",
                serialNumber = "SYNTH-SERIAL",
                typeId = "bbbbbbbb-1111-4111-8111-bbbbbbbbbbbb",
                typeName = "Synthetic Asset type",
                typeRevision = 1,
                condition = AssetCondition.GOOD,
                lifecycle = "ACTIVE",
                duplicateSerial = false,
                version = 4,
            ),
        ),
        stockSearchResults = listOf(
            StockItemDto(
                id = "cccccccc-1111-4111-8111-cccccccccccc",
                organizationId = requireNotNull(state.directory).items.single().organizationId,
                itemCode = "SYNTH-STOCK-01",
                name = "Synthetic cable",
                partNumber = "SYNTH-PART-01",
                categoryDefinitionId = "dddddddd-1111-4111-8111-dddddddddddd",
                categoryRevision = 1,
                baseUnitCode = "EA",
                classification = "STOCK_QUANTITY",
                serialized = false,
                lotTracked = false,
                active = true,
                version = 2,
            ),
        ),
    )
    val quickScene = ImageComposeScene(width = 720, height = 980, coroutineContext = coroutineContext) {
        Column(Modifier.fillMaxSize().background(Color.White).padding(24.dp)) {
            WarehouseQuickScanControls(quickState, false, {}, {}, {})
        }
    }
    captureAndClose(quickScene, directory.resolve("warehouse-quick-scan.png"))

    val stock = syntheticStock(state)
    val inventory = ImageComposeScene(width = 940, height = 940, coroutineContext = coroutineContext) {
        Column(Modifier.fillMaxSize().background(Color.White).padding(24.dp)) {
            StockInventorySection(stock, state.selectedWarehouseId) {}
        }
    }
    try {
        captureScene(inventory, directory.resolve("stock-inventory.png"))
    } finally {
        inventory.close()
    }
    val assets = syntheticAsset(state)
    val passportScene = ImageComposeScene(width = 980, height = 1180, coroutineContext = coroutineContext) {
        Column(Modifier.fillMaxSize().background(Color.White).padding(24.dp)) {
            AssetPassportSection(assets, state.selectedWarehouseId) {}
        }
    }
    captureAndClose(passportScene, directory.resolve("asset-passport.png"))
    val lookupScene = ImageComposeScene(width = 400, height = 640, coroutineContext = coroutineContext) {
        Column(Modifier.fillMaxSize().background(Color.White).padding(20.dp)) {
            AssetTagLookupSection(AssetLookupState(result = AssetTagLookupDto(
                "SYNTH-PASSPORT", "Synthetic instrument", "Synthetic Asset type", condition = AssetCondition.GOOD,
                locationCode = "TEST-WH-1", serialNumber = "SYNTH-SERIAL",
            ))) {}
        }
    }
    captureAndClose(lookupScene, directory.resolve("asset-tag-lookup-compose.png"))
    val asset = requireNotNull(assets.selected).identity
    val evaluation = AssetAvailabilityDto("BLOCKED_CALIBRATION", asset.version, "2026-11-13T12:00:00Z",
        listOf(
            AssetAvailabilityReasonDto("BLOCKED_CALIBRATION", "CALIBRATION_EXPIRED", "ASSET_CALIBRATION", "synthetic-calibration", 1),
            AssetAvailabilityReasonDto("BLOCKED_MAINTENANCE", "MAINTENANCE_IN_PROGRESS", "ASSET_MAINTENANCE", "synthetic-service", 1),
        ), "EXPIRED", "2026-11-12T12:00:00Z", true, "IN_PROGRESS")
    val service = AssetServiceDetailDto(evaluation, emptyList(), emptyList(),
        listOf(AssetMaintenanceDto("synthetic-service", "MAINTENANCE", "IN_PROGRESS", "Synthetic bench inspection",
            sentAt = "2026-11-13T08:00:00Z", version = 1)), AssetServiceCapabilitiesDto(true, true, true))
    val availabilityScene = ImageComposeScene(width = 980, height = 910, coroutineContext = coroutineContext) {
        Column(Modifier.fillMaxSize().background(Color.White).padding(24.dp)) {
            AssetServiceQueue(assets.copy(serviceQueue = listOf(AssetServiceQueueEntryDto(asset.id, asset.assetCode, asset.name, evaluation)))) {}
            AssetServiceDetail(asset.id, service, false) {}
        }
    }
    captureAndClose(availabilityScene, directory.resolve("asset-service-queue.png"))
    val expiry = "2026-11-13T18:00:00Z"
    val demand = WorkResourceDemandDto("synthetic-requirement", 3, "FIBER-TOOLS", "ASSET", "ASSET_TOOL",
        assetTypeId = asset.typeId, quantity = "1", version = 1)
    val candidate = ResourceCandidateDto("ASSET", asset.id, asset.assetCode, "synthetic-bin", 4, expiry, reasonCodes = listOf("ASSET_RESERVED"))
    val requirement = RequirementCandidatesDto(demand, "ASSET_RESERVED", listOf(candidate))
    val reservation = ReservationDto("ASSET", "synthetic-reservation", asset.id, "synthetic-work", demand.requirementId,
        "synthetic-project", "synthetic-site", state = "ACTIVE", version = 1, expiresAt = expiry, requestedBy = "synthetic-operator")
    val workScene = ImageComposeScene(width = 920, height = 720, coroutineContext = coroutineContext) {
        Column(Modifier.fillMaxSize().background(Color.White).padding(24.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
            ProjectResourceResults(WorkResourceCandidatesDto("synthetic-work", 5, true, listOf(requirement)), listOf(reservation), false) {
                _, _, _, _ -> Unit
            }
            val request = WarehouseWorkRequestDto("synthetic-work", "SYNTH-WORK", "Synthetic installation", 5, listOf(requirement))
            WarehouseReservationResults(WarehouseReservationQueueDto(true, listOf(request), listOf(reservation)), request.workOrderId,
                false, {}, { _, _, _, _, _ -> Unit }, { _, _ -> Unit })
        }
    }
    captureAndClose(workScene, directory.resolve("work-resource-reservations.png"))
    val selectedWarehouse = requireNotNull(state.selectedWarehouseId)
    val stockItem = requireNotNull(stock.selected)
    val issuedContext = IssuedStockContextDto(stockItem.id, "synthetic-employee", "synthetic-project", "synthetic-site",
        "synthetic-work", "synthetic-requirement", "6.123456", 3)
    val issuedState = stock.copy(issuedWarehouseId = selectedWarehouse, issued = IssuedStockDirectoryDto(
        listOf(IssuedStockDirectoryEntryDto(issuedContext, "SYNTH-EMP", "Synthetic employee",
            "SYNTH-WORK", "Synthetic installation", 5, stockItem.baseUnitCode)), false))
    val issuedScene = ImageComposeScene(width = 720, height = 610, coroutineContext = coroutineContext) {
        Column(Modifier.fillMaxSize().background(Color.White).padding(24.dp)) {
            IssuedStockOperations(stockItem, selectedWarehouse, issuedState) {}
        }
    }
    captureAndClose(issuedScene, directory.resolve("stock-issued-custody.png"))
    val passport = requireNotNull(assets.selected)
    val bin = assets.locations.first()
    val returning = passport.copy(canOperatePhysical = true, custody = AssetCustodyDto(
        employeeId = "synthetic-employee", employeeCode = "SYNTH-EMP",
        workOrderId = "synthetic-work", sourceMovementId = "synthetic-checkout", expectedReturnAt = expiry))
    val returnScene = ImageComposeScene(width = 720, height = 610, coroutineContext = coroutineContext) {
        Column(Modifier.fillMaxSize().background(Color.White).padding(24.dp)) {
            PhysicalAssetOperations(returning, assets.copy(selected = returning), {})
        }
    }
    captureAndClose(returnScene, directory.resolve("asset-physical-return.png"))
    val returned = passport.copy(canOperatePhysical = true, currentReturnInspected = false,
        custody = AssetCustodyDto(storageLocationId = bin.storageLocationId, locationCode = bin.code,
            sourceMovementId = "synthetic-return"))
    val inspectionScene = ImageComposeScene(width = 720, height = 610, coroutineContext = coroutineContext) {
        Column(Modifier.fillMaxSize().background(Color.White).padding(24.dp)) {
            PhysicalAssetOperations(returned, assets.copy(selected = returned, movements = listOf(
                AssetMovementDto("synthetic-return", "RETURN", toLocationId = bin.storageLocationId, occurredAt = expiry))), {})
        }
    }
    captureAndClose(inspectionScene, directory.resolve("asset-return-inspection.png"))

    val receivingAt = "2026-11-19T06:30:00Z"
    val receivingRecordId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    val receivingStockLine = ReceivingLineDto(
        id = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
        receivingRecordId = receivingRecordId,
        organizationId = requireNotNull(state.locations).items.first().organizationId,
        candidateClass = "STOCK_SERIALIZED",
        expectedStockItemId = stockItem.id,
        expectedQuantity = "2.000000",
        receivedQuantity = "2.000000",
        unitCode = stockItem.baseUnitCode,
        condition = AssetCondition.GOOD,
        serials = listOf("RX-STOCK-001", "RX-STOCK-002"),
        sourceLineRef = "DELIVERY-LINE-01",
        receivedBy = "synthetic-operator",
        receivedAt = receivingAt,
        version = 2,
    )
    val receivingAssetType = requireNotNull(assets.catalog).types.first()
    val receivingAssetLine = ReceivingLineDto(
        id = "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
        receivingRecordId = receivingRecordId,
        organizationId = receivingStockLine.organizationId,
        candidateClass = "COMPANY_ASSET",
        expectedAssetTypeDefinitionId = receivingAssetType.id,
        expectedQuantity = "1.000000",
        receivedQuantity = "1.000000",
        condition = AssetCondition.GOOD,
        serials = listOf("RX-ASSET-001"),
        sourceLineRef = "DELIVERY-LINE-02",
        receivedAssetId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
        receivedBy = "synthetic-operator",
        receivedAt = receivingAt,
        version = 2,
    )
    val receivingDetail = ReceivingDetailDto(
        record = ReceivingRecordDto(
            id = receivingRecordId,
            organizationId = receivingStockLine.organizationId,
            destinationStorageLocationId = requireNotNull(state.locations).items.first().storageLocationId,
            sourceType = "PO",
            sourceRef = "PO-SYNTH-204",
            deliveryDocumentRef = "DELIVERY-SYNTH-88",
            state = "RECEIVED_WITH_DISCREPANCY",
            createdBy = "synthetic-operator",
            createdAt = "2026-11-19T06:00:00Z",
            updatedAt = receivingAt,
            receivedBy = "synthetic-operator",
            receivedAt = receivingAt,
            correlationId = "synthetic-receiving-render",
            version = 6,
        ),
        lines = listOf(receivingStockLine, receivingAssetLine),
        discrepancies = listOf(
            ReceivingDiscrepancyDto(
                id = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
                receivingRecordId = receivingRecordId,
                receivingLineId = receivingStockLine.id,
                organizationId = receivingStockLine.organizationId,
                type = "SHORT_QTY",
                description = "Synthetic discrepancy retained without rewriting expected source",
                createdBy = "synthetic-operator",
                createdAt = receivingAt,
                version = 1,
            ),
        ),
    )
    val receivingScene = ImageComposeScene(width = 900, height = 1180, coroutineContext = coroutineContext) {
        Column(Modifier.fillMaxSize().background(Color.White).padding(24.dp)) {
            ReceivingHumanProofSection(
                detail = receivingDetail,
                stockItems = listOf(stockItem.copy(classification = "STOCK_SERIALIZED", serialized = true)),
                assetCatalog = AssetCatalogDto(listOf(receivingAssetType), false),
                receiveStock = { _, _, _, _ -> error("Final Receiving render is read-only.") },
                receiveAsset = { _, _, _, _, _, _, _, _, _ -> error("Final Receiving render is read-only.") },
                discrepancy = { _, _, _ -> error("Final Receiving render is read-only.") },
                complete = { error("Final Receiving render is read-only.") },
            )
        }
    }
    captureAndClose(receivingScene, directory.resolve("receiving-final.png"))

    val stocktakeSessionId = "11111111-2222-4333-8444-555555555555"
    val stocktakeSnapshotId = "22222222-3333-4444-8555-666666666666"
    val firstCountId = "33333333-4444-4555-8666-777777777777"
    val recountId = "44444444-5555-4666-8777-888888888888"
    val discrepancyId = "55555555-6666-4777-8888-999999999999"
    val adjustmentId = "66666666-7777-4888-8999-aaaaaaaaaaaa"
    val approvalId = "abababab-abab-4bab-8bab-abababababab"
    val movementId = "cdcdcdcd-cdcd-4dcd-8dcd-cdcdcdcdcdcd"
    val stocktakeSession = StocktakeSessionDto(
        id = stocktakeSessionId,
        organizationId = receivingStockLine.organizationId,
        warehouseId = selectedWarehouse,
        rootStorageLocationId = bin.storageLocationId,
        state = "ACTIVE",
        stockItemIds = listOf(stockItem.id),
        snapshotAt = "2026-11-19T08:00:00Z",
        createdBy = "synthetic-operator",
        createdAt = "2026-11-19T07:59:00Z",
        correlationId = "synthetic-stocktake-render",
        version = 2,
    )
    val stocktakeSnapshot = StocktakeSnapshotLineDto(
        id = stocktakeSnapshotId,
        sessionId = stocktakeSessionId,
        organizationId = receivingStockLine.organizationId,
        stockItemId = stockItem.id,
        itemCode = stockItem.itemCode,
        storageLocationId = bin.storageLocationId,
        locationCode = bin.code,
        expectedOnHand = "6.000000",
        expectedReserved = "0.000000",
        expectedDamaged = "0.000000",
        expectedQuarantine = "0.000000",
        unitCode = stockItem.baseUnitCode,
        balanceVersion = 4,
        itemVersion = stockItem.version,
        snapshotAt = "2026-11-19T08:00:00Z",
    )
    val firstCount = StockCountRecordDto(
        id = firstCountId,
        sessionId = stocktakeSessionId,
        snapshotLineId = stocktakeSnapshotId,
        organizationId = receivingStockLine.organizationId,
        stockItemId = stockItem.id,
        storageLocationId = bin.storageLocationId,
        countedQuantity = "7.000000",
        unitCode = stockItem.baseUnitCode,
        countedBy = "synthetic-counter",
        countedAt = "2026-11-19T08:05:00Z",
        scanSource = "SCAN-SYNTH-COUNT-01",
        notes = "Initial physical count",
        operationId = "77777777-8888-4999-8aaa-bbbbbbbbbbbb",
        correlationId = "synthetic-stocktake-render",
    )
    val recount = StockCountRecordDto(
        id = recountId,
        sessionId = stocktakeSessionId,
        snapshotLineId = stocktakeSnapshotId,
        organizationId = receivingStockLine.organizationId,
        stockItemId = stockItem.id,
        storageLocationId = bin.storageLocationId,
        countedQuantity = "5.000000",
        unitCode = stockItem.baseUnitCode,
        countedBy = "synthetic-counter",
        countedAt = "2026-11-19T08:07:00Z",
        scanSource = "SCAN-SYNTH-COUNT-02",
        notes = "Verified recount",
        supersedesCountRecordId = firstCountId,
        operationId = "88888888-9999-4aaa-8bbb-cccccccccccc",
        correlationId = "synthetic-stocktake-render",
    )
    val activeStocktake = StocktakeDetailDto(
        session = stocktakeSession,
        snapshotLines = listOf(stocktakeSnapshot),
        countRecords = listOf(firstCount, recount),
        discrepancies = emptyList(),
        adjustmentRequests = emptyList(),
    )
    val countScene = ImageComposeScene(width = 820, height = 1040, coroutineContext = coroutineContext) {
        Column(Modifier.fillMaxSize().background(Color.White).padding(24.dp)) {
            StocktakeHumanProofSection(detail = activeStocktake, busy = true)
        }
    }
    captureAndClose(countScene, directory.resolve("stocktake-count-history.png"))

    val discrepancy = InventoryDiscrepancyDto(
        id = discrepancyId,
        sessionId = stocktakeSessionId,
        snapshotLineId = stocktakeSnapshotId,
        latestCountRecordId = recountId,
        organizationId = receivingStockLine.organizationId,
        stockItemId = stockItem.id,
        storageLocationId = bin.storageLocationId,
        expectedQuantity = "6.000000",
        countedQuantity = "5.000000",
        varianceQuantity = "-1.000000",
        snapshotBalanceVersion = 4,
        currentBalanceVersion = 4,
        sourceChangedSinceSnapshot = false,
        state = "ADJUSTMENT_REQUESTED",
        createdBy = "synthetic-counter",
        createdAt = "2026-11-19T08:08:00Z",
        updatedAt = "2026-11-19T08:09:00Z",
        version = 2,
    )
    val adjustment = InventoryAdjustmentRequestDto(
        id = adjustmentId,
        discrepancyId = discrepancyId,
        sessionId = stocktakeSessionId,
        organizationId = receivingStockLine.organizationId,
        stockItemId = stockItem.id,
        storageLocationId = bin.storageLocationId,
        deltaQuantity = "-1.000000",
        unitCode = stockItem.baseUnitCode,
        reason = "Verified physical shortage after recount",
        requestedBy = "synthetic-operator",
        requestedAt = "2026-11-19T08:09:00Z",
        approvalRequestId = approvalId,
        approvalStatus = "PENDING",
        version = 1,
    )
    val submittedStocktake = activeStocktake.copy(
        session = stocktakeSession.copy(state = "SUBMITTED", version = 3),
        discrepancies = listOf(discrepancy),
        adjustmentRequests = listOf(adjustment),
    )
    val varianceState = StocktakeVariancesDto(
        session = submittedStocktake.session,
        discrepancies = listOf(discrepancy),
        adjustmentRequests = listOf(adjustment),
    )
    val adjustmentScene = ImageComposeScene(width = 820, height = 1120, coroutineContext = coroutineContext) {
        Column(Modifier.fillMaxSize().background(Color.White).padding(24.dp)) {
            StocktakeHumanProofSection(
                detail = submittedStocktake,
                variances = varianceState,
                busy = true,
            )
        }
    }
    captureAndClose(adjustmentScene, directory.resolve("stocktake-adjustment-request.png"))

    val appliedAdjustment = adjustment.copy(
        approvalStatus = "APPROVED",
        appliedMovementId = movementId,
        appliedBy = "synthetic-approver",
        appliedAt = "2026-11-19T08:10:00Z",
        version = 2,
    )
    val closedDiscrepancy = discrepancy.copy(
        state = "CLOSED",
        updatedAt = "2026-11-19T08:10:00Z",
        version = 3,
    )
    val closedSession = submittedStocktake.session.copy(state = "CLOSED", version = 4)
    val appliedStocktake = submittedStocktake.copy(
        session = closedSession,
        discrepancies = listOf(closedDiscrepancy),
        adjustmentRequests = listOf(appliedAdjustment),
    )
    val appliedVariances = StocktakeVariancesDto(
        session = closedSession,
        discrepancies = listOf(closedDiscrepancy),
        adjustmentRequests = listOf(appliedAdjustment),
    )
    val appliedScene = ImageComposeScene(width = 820, height = 1120, coroutineContext = coroutineContext) {
        Column(Modifier.fillMaxSize().background(Color.White).padding(24.dp)) {
            StocktakeHumanProofSection(
                detail = appliedStocktake,
                variances = appliedVariances,
                busy = true,
            )
        }
    }
    captureAndClose(appliedScene, directory.resolve("stocktake-adjustment-applied.png"))
}

@OptIn(ExperimentalComposeUiApi::class)
private fun captureAndClose(scene: ImageComposeScene, output: Path) {
    try {
        captureScene(scene, output)
    } finally {
        scene.close()
    }
}

private fun syntheticAsset(warehouse: HiltechWarehouseState): AssetInventoryState {
    val locations = requireNotNull(warehouse.locations).items
    val bin = locations.first()
    val type = AssetTypeDto("synthetic-type", "Synthetic Asset type", 1, true, true, true,
        listOf("QR"), false, inspectionRequiredOnReturn = true)
    val asset = AssetIdentityDto("synthetic-asset", bin.organizationId, warehouse.selectedWarehouseId, "SYNTH-PASSPORT",
        "Synthetic test instrument", "Synthetic manufacturer", "Synthetic model", "SYNTH-SERIAL",
        type.id, type.name, 1, AssetCondition.GOOD, "ACTIVE", true, "synthetic-source-row", 4)
    val opening = "2026-11-13T07:00:00Z"
    val corrected = "2026-11-13T08:00:00Z"
    return AssetInventoryState(
        catalog = AssetCatalogDto(listOf(type), true), items = listOf(asset), locations = locations,
        selected = AssetPassportDto(asset, AssetCustodyDto(bin.storageLocationId, bin.code,
            projectId = "synthetic-project", siteId = "synthetic-site", sourceMovementId = "synthetic-correction", since = corrected),
            listOf(
                AssetTagDto("tag-new", "31ed4783-1cdc-4cc8-beb2-908f7c8d6fad", "QR", true, true, "tag-old", corrected),
                AssetTagDto("tag-old", "57981f24-081b-4c19-8d2e-e336b9576ff2", "QR", false, false, issuedAt = opening, endedAt = corrected),
            ), listOf("QR"), canCorrect = true, returnInspectionType = type),
        movements = listOf(
            AssetMovementDto("synthetic-correction", "CONTROLLED_CORRECTION", toLocationId = bin.storageLocationId,
                correctedMovementId = "synthetic-opening", occurredAt = corrected, reason = "Synthetic label reconciliation"),
            AssetMovementDto("synthetic-opening", "INITIALIZE_OPENING_CUSTODY", toLocationId = bin.storageLocationId,
                sourceImportRowId = asset.sourceImportRowId, occurredAt = opening),
        ),
    )
}

private fun syntheticStock(warehouse: HiltechWarehouseState): StockInventoryState {
    val locations = requireNotNull(warehouse.locations).items
    val source = locations[0]
    val destination = locations[1]
    val item = StockItemDto(
        id = "synthetic-stock-item",
        organizationId = source.organizationId,
        itemCode = "SYNTH-STOCK",
        name = "Synthetic inventory cable",
        description = "Synthetic fixture for the opening and transfer flow",
        brand = "Example Brand",
        partNumber = "SYNTH-PART",
        categoryDefinitionId = "synthetic-category",
        categoryRevision = 1,
        baseUnitCode = "SYNTH-UOM",
        classification = "STOCK_QUANTITY",
        serialized = false,
        lotTracked = false,
        active = true,
        sourceImportRowId = "synthetic-source-row",
        version = 1,
        openingInitialized = true,
    )
    return StockInventoryState(
        catalog = StockCatalogDto(listOf(StockUnitDto(item.baseUnitCode, "Synthetic unit")),
            listOf(StockCategoryDto(item.categoryDefinitionId, "Synthetic category", 1))),
        items = listOf(item),
        locations = locations,
        selected = item,
        balances = listOf(
            StockBalanceDto(source.storageLocationId, source.code, "9.123455", 2),
            StockBalanceDto(destination.storageLocationId, destination.code, "1.000001", 1),
        ),
        movements = listOf(
            StockMovementDto("fixture-transfer", "fixture-transfer-operation", "TRANSFER", "1.000001",
                item.baseUnitCode, source.storageLocationId, destination.storageLocationId,
                occurredAt = "2026-11-12T08:00:00Z", recordedBy = "synthetic-operator"),
            StockMovementDto("fixture-opening", "fixture-opening-operation", "INITIALIZE_OPENING", "10.123456",
                item.baseUnitCode, toStorageLocationId = source.storageLocationId, sourceImportRowId = item.sourceImportRowId,
                occurredAt = "2026-11-12T07:00:00Z", recordedBy = "synthetic-operator"),
        ),
    )
}

@OptIn(ExperimentalComposeUiApi::class)
private fun captureScene(scene: ImageComposeScene, output: Path) {
    scene.render().use { image ->
        requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { encoded ->
            Files.write(output, encoded.bytes)
        }
    }
}

private fun syntheticWarehouse(): HiltechWarehouseState {
    val warehouse = WarehouseDto(
        warehouseId = "warehouse-fixture",
        organizationId = "organization-fixture",
        code = "TEST-WH",
        name = "Synthetic warehouse",
        active = true,
        version = 1,
    )
    val root = StorageLocationDto(
        storageLocationId = "storage-fixture",
        organizationId = warehouse.organizationId,
        code = "TEST-AREA",
        name = "Storage area",
        kind = "WAREHOUSE",
        warehouseId = warehouse.warehouseId,
        temporary = false,
        lifecycleState = "ACTIVE",
        version = 1,
    )
    val child = root.copy(
        storageLocationId = "site-storage-fixture",
        code = "TEST-SITE",
        name = "Temporary storage",
        kind = "SITE_STORAGE",
        parentStorageLocationId = root.storageLocationId,
        projectId = "project-fixture",
        siteId = "site-fixture",
        temporary = true,
    )
    val row = InventoryImportRowDto(
        rowId = "row-fixture",
        batchId = "batch-fixture",
        sourceSheet = "أكسسوارات",
        sourceRowNumber = 2,
        candidateClass = "STOCK_QUANTITY",
        normalizedDescription = "Synthetic accessory",
        candidateQuantity = "0",
        issueCodes = listOf("UNKNOWN_UOM", "LEGACY_COLUMN_SEMANTICS_AMBIGUOUS"),
        reviewState = "NEEDS_REVIEW",
        version = 1,
        sourcePreview = mapOf("اسم الصنف" to "صنف تجريبي", "العدد" to "0", "الرقم التسلسلي" to "LOCAL"),
    )
    return HiltechWarehouseState(
        directory = WarehouseListDto(listOf(warehouse), "warehouse-render-fixture"),
        selectedWarehouseId = warehouse.warehouseId,
        locations = StorageLocationListDto(listOf(root, child), "warehouse-render-fixture"),
        selectedImport = InventoryImportBatchDto(
            batchId = row.batchId,
            organizationId = warehouse.organizationId,
            sourceFileName = "synthetic-accessories.xlsx",
            sourceSha256 = "a".repeat(64),
            sourceType = "LEGACY_EXCEL",
            state = "IN_REVIEW",
            version = 1,
            rows = listOf(row),
            correlationId = "warehouse-render-fixture",
        ),
    )
}

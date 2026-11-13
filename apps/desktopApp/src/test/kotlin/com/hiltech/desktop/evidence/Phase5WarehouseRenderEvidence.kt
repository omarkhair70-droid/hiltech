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
import com.hiltech.shared.core.StockInventoryState
import com.hiltech.shared.core.WarehouseSetupSection
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
            ), listOf("QR"), canCorrect = true),
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

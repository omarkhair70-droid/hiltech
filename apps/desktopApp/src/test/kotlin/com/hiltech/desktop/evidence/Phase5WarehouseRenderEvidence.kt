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
import com.hiltech.shared.core.HiltechWarehouseState
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
fun main() = runBlocking {
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

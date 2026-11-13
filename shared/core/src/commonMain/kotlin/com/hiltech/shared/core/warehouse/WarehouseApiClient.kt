package com.hiltech.shared.core.warehouse

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLQueryComponent
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Same HILTECH authenticated HTTP transport as People/Projects.
 *
 * Phase 5 / Slice 01 is online-only setup and import review. This client
 * intentionally does not enqueue offline inventory or custody commands.
 */
class WarehouseApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {
    suspend fun assetCatalog(warehouseId: String, installationId: String): AssetCatalogDto =
        get("/v1/asset-types?warehouseId=" + uuid(warehouseId), installationId, AssetCatalogDto.serializer())

    suspend fun assets(warehouseId: String, search: String, afterCode: String?, installationId: String): List<AssetIdentityDto> =
        get("/v1/assets?warehouseId=" + uuid(warehouseId) + "&search=" + search.encodeURLQueryComponent() +
            (afterCode?.let { "&afterCode=" + it.encodeURLQueryComponent() } ?: ""),
            installationId, ListSerializer(AssetIdentityDto.serializer()))

    suspend fun assetPassport(id: String, installationId: String): AssetPassportDto =
        get("/v1/assets/" + uuid(id), installationId, AssetPassportDto.serializer())

    suspend fun assetMovements(id: String, installationId: String): List<AssetMovementDto> =
        get("/v1/assets/" + uuid(id) + "/movements", installationId, ListSerializer(AssetMovementDto.serializer()))

    suspend fun lookupAssetTag(code: String, installationId: String): AssetTagLookupDto {
        val opaqueCode = code.trim()
        require(Regex("[A-Za-z0-9_-]{1,128}").matches(opaqueCode))
        return get("/v1/assets/by-tag/" + opaqueCode, installationId, AssetTagLookupDto.serializer())
    }

    suspend fun registerAsset(request: AssetRegistrationDto, installationId: String): AssetPassportDto =
        post("/v1/assets", request.operationId, json.encodeToString(AssetRegistrationDto.serializer(), request),
            installationId, AssetPassportDto.serializer())

    suspend fun tagAsset(id: String, request: AssetTagCommandDto, installationId: String): AssetTagDto {
        val action = if (request.previousTagId == null) "/tags" else "/replace-tag"
        return post("/v1/assets/" + uuid(id) + action, request.operationId,
            json.encodeToString(AssetTagCommandDto.serializer(), request), installationId, AssetTagDto.serializer())
    }

    suspend fun openAssetCustody(id: String, request: OpenAssetCustodyDto, installationId: String): AssetPassportDto =
        post("/v1/assets/" + uuid(id) + "/opening-custody", request.operationId,
            json.encodeToString(OpenAssetCustodyDto.serializer(), request), installationId, AssetPassportDto.serializer())

    suspend fun correctAssetCustody(id: String, request: CorrectAssetCustodyDto, installationId: String): AssetPassportDto =
        post("/v1/assets/" + uuid(id) + "/correct-custody", request.operationId,
            json.encodeToString(CorrectAssetCustodyDto.serializer(), request), installationId, AssetPassportDto.serializer())

    suspend fun approveAssetImport(
        batchId: String, rowId: String, request: ApproveAssetImportDto, installationId: String,
    ): AssetPassportDto =
        post("/v1/inventory-imports/" + uuid(batchId) + "/rows/" + uuid(rowId) + "/approve-asset",
            request.operationId, json.encodeToString(ApproveAssetImportDto.serializer(), request), installationId, AssetPassportDto.serializer())

    suspend fun stockCatalog(organizationId: String, installationId: String): StockCatalogDto =
        get("/v1/stock-catalog?organizationId=" + uuid(organizationId), installationId, StockCatalogDto.serializer())

    suspend fun stockItems(organizationId: String, installationId: String, afterCode: String? = null): List<StockItemDto> =
        get("/v1/stock-items?organizationId=" + uuid(organizationId) +
            (afterCode?.let { "&afterCode=" + it.encodeURLQueryComponent() } ?: ""),
            installationId, ListSerializer(StockItemDto.serializer()))

    suspend fun stockItem(id: String, installationId: String): StockItemDto =
        get("/v1/stock-items/" + uuid(id), installationId, StockItemDto.serializer())

    suspend fun stockBalances(id: String, installationId: String): List<StockBalanceDto> =
        get("/v1/stock-items/" + uuid(id) + "/balances", installationId, ListSerializer(StockBalanceDto.serializer()))

    suspend fun stockMovements(id: String, installationId: String): List<StockMovementDto> =
        get("/v1/stock-items/" + uuid(id) + "/movements", installationId, ListSerializer(StockMovementDto.serializer()))

    suspend fun registerStockUnit(request: RegisterStockUnitDto, installationId: String): StockUnitDto =
        post("/v1/stock-units", request.operationId, json.encodeToString(RegisterStockUnitDto.serializer(), request),
            installationId, StockUnitDto.serializer())

    suspend fun createStockItem(request: CreateStockItemDto, installationId: String): StockItemDto =
        post("/v1/stock-items", request.operationId, json.encodeToString(CreateStockItemDto.serializer(), request),
            installationId, StockItemDto.serializer())

    suspend fun approveStockItem(batchId: String, rowId: String, request: ApproveStockItemDto, installationId: String): StockItemDto =
        post("/v1/inventory-imports/" + uuid(batchId) + "/rows/" + uuid(rowId) + "/approve-stock-item",
            request.operationId, json.encodeToString(ApproveStockItemDto.serializer(), request),
            installationId, StockItemDto.serializer())

    suspend fun initializeStockOpening(id: String, request: InitializeStockOpeningDto, installationId: String): StockMovementReceiptDto =
        post("/v1/stock-items/" + uuid(id) + "/opening-balance", request.operationId,
            json.encodeToString(InitializeStockOpeningDto.serializer(), request), installationId, StockMovementReceiptDto.serializer())

    suspend fun transferStock(id: String, request: TransferStockDto, installationId: String): StockMovementReceiptDto =
        post("/v1/stock-items/" + uuid(id) + "/transfer", request.operationId,
            json.encodeToString(TransferStockDto.serializer(), request), installationId, StockMovementReceiptDto.serializer())

    suspend fun warehouses(
        organizationId: String,
        installationId: String,
    ): WarehouseListDto =
        get(
            "/v1/warehouses?organizationId=" + uuid(organizationId),
            installationId,
            WarehouseListDto.serializer(),
        )

    suspend fun warehouse(
        warehouseId: String,
        installationId: String,
    ): WarehouseDto =
        get(
            "/v1/warehouses/" + uuid(warehouseId),
            installationId,
            WarehouseDto.serializer(),
        )

    suspend fun createWarehouse(
        request: WarehouseCreateRequestDto,
        installationId: String,
    ): WarehouseCreateResponseDto =
        post(
            "/v1/warehouses",
            request.operationId,
            json.encodeToString(WarehouseCreateRequestDto.serializer(), request),
            installationId,
            WarehouseCreateResponseDto.serializer(),
        )

    suspend fun storageLocations(
        organizationId: String,
        installationId: String,
    ): StorageLocationListDto =
        get(
            "/v1/storage-locations?organizationId=" + uuid(organizationId),
            installationId,
            StorageLocationListDto.serializer(),
        )

    suspend fun warehouseStorageLocations(
        warehouseId: String,
        installationId: String,
    ): StorageLocationListDto =
        get(
            "/v1/warehouses/" + uuid(warehouseId) + "/storage-locations",
            installationId,
            StorageLocationListDto.serializer(),
        )

    suspend fun storageLocation(
        storageLocationId: String,
        installationId: String,
    ): StorageLocationDto =
        get(
            "/v1/storage-locations/" + uuid(storageLocationId),
            installationId,
            StorageLocationDto.serializer(),
        )

    suspend fun createStorageLocation(
        request: StorageLocationCreateRequestDto,
        installationId: String,
        warehouseId: String? = null,
    ): StorageLocationCreateResponseDto {
        val path = if (warehouseId == null) {
            "/v1/storage-locations"
        } else {
            "/v1/warehouses/" + uuid(warehouseId) + "/storage-locations"
        }
        return post(
            path,
            request.operationId,
            json.encodeToString(StorageLocationCreateRequestDto.serializer(), request),
            installationId,
            StorageLocationCreateResponseDto.serializer(),
        )
    }

    suspend fun importBatches(
        organizationId: String,
        installationId: String,
    ): InventoryImportBatchDirectoryDto =
        get(
            "/v1/inventory-imports?organizationId=" + uuid(organizationId),
            installationId,
            InventoryImportBatchDirectoryDto.serializer(),
        )

    suspend fun importBatch(
        batchId: String,
        installationId: String,
    ): InventoryImportBatchDto =
        get(
            "/v1/inventory-imports/" + uuid(batchId),
            installationId,
            InventoryImportBatchDto.serializer(),
        )

    suspend fun createImportBatch(
        request: InventoryImportBatchCreateRequestDto,
        installationId: String,
    ): InventoryImportBatchCreateResponseDto =
        post(
            "/v1/inventory-imports",
            request.operationId,
            json.encodeToString(InventoryImportBatchCreateRequestDto.serializer(), request),
            installationId,
            InventoryImportBatchCreateResponseDto.serializer(),
        )

    suspend fun stageImportRow(
        batchId: String,
        request: InventoryImportRowCreateRequestDto,
        installationId: String,
    ): InventoryImportRowCommandResponseDto =
        post(
            "/v1/inventory-imports/" + uuid(batchId) + "/rows",
            request.operationId,
            json.encodeToString(InventoryImportRowCreateRequestDto.serializer(), request),
            installationId,
            InventoryImportRowCommandResponseDto.serializer(),
        )

    suspend fun reviewImportRow(
        batchId: String,
        rowId: String,
        request: InventoryImportRowReviewRequestDto,
        installationId: String,
    ): InventoryImportRowCommandResponseDto =
        post(
            "/v1/inventory-imports/" + uuid(batchId) +
                "/rows/" + uuid(rowId) + "/review",
            request.operationId,
            json.encodeToString(InventoryImportRowReviewRequestDto.serializer(), request),
            installationId,
            InventoryImportRowCommandResponseDto.serializer(),
        )

    suspend fun currentWarehouseAuthority(
        warehouseId: String,
        installationId: String,
    ): WarehouseAuthorityListDto =
        get(
            "/v1/warehouses/" + uuid(warehouseId) + "/authority",
            installationId,
            WarehouseAuthorityListDto.serializer(),
        )

    suspend fun grantWarehouseAuthority(
        warehouseId: String,
        request: WarehouseAuthorityGrantRequestDto,
        installationId: String,
    ): WarehouseAuthorityCommandResponseDto =
        post(
            "/v1/warehouses/" + uuid(warehouseId) + "/authority",
            request.operationId,
            json.encodeToString(WarehouseAuthorityGrantRequestDto.serializer(), request),
            installationId,
            WarehouseAuthorityCommandResponseDto.serializer(),
        )

    suspend fun endWarehouseAuthority(
        warehouseId: String,
        bindingId: String,
        request: AuthorityEndRequestDto,
        installationId: String,
    ): WarehouseAuthorityCommandResponseDto =
        post(
            "/v1/warehouses/" + uuid(warehouseId) +
                "/authority/" + uuid(bindingId) + "/end",
            request.operationId,
            json.encodeToString(AuthorityEndRequestDto.serializer(), request),
            installationId,
            WarehouseAuthorityCommandResponseDto.serializer(),
        )

    suspend fun currentStorageAuthority(
        storageLocationId: String,
        installationId: String,
    ): StorageAuthorityListDto =
        get(
            "/v1/storage-locations/" + uuid(storageLocationId) + "/authority",
            installationId,
            StorageAuthorityListDto.serializer(),
        )

    suspend fun grantStorageAuthority(
        storageLocationId: String,
        request: StorageAuthorityGrantRequestDto,
        installationId: String,
    ): StorageAuthorityCommandResponseDto =
        post(
            "/v1/storage-locations/" + uuid(storageLocationId) + "/authority",
            request.operationId,
            json.encodeToString(StorageAuthorityGrantRequestDto.serializer(), request),
            installationId,
            StorageAuthorityCommandResponseDto.serializer(),
        )

    suspend fun endStorageAuthority(
        storageLocationId: String,
        bindingId: String,
        request: AuthorityEndRequestDto,
        installationId: String,
    ): StorageAuthorityCommandResponseDto =
        post(
            "/v1/storage-locations/" + uuid(storageLocationId) +
                "/authority/" + uuid(bindingId) + "/end",
            request.operationId,
            json.encodeToString(AuthorityEndRequestDto.serializer(), request),
            installationId,
            StorageAuthorityCommandResponseDto.serializer(),
        )

    private suspend fun <T> get(
        path: String,
        installationId: String,
        responseSerializer: KSerializer<T>,
    ): T = api.request(
        method = HttpMethod.Get,
        path = path,
        options = HiltechRequestOptions(installationId = installationId),
        decode = { json.decodeFromString(responseSerializer, it) },
    )

    private suspend fun <T> post(
        path: String,
        operationId: String,
        body: String,
        installationId: String,
        responseSerializer: KSerializer<T>,
    ): T = api.request(
        method = HttpMethod.Post,
        path = path,
        options = HiltechRequestOptions(
            installationId = installationId,
            idempotencyKey = uuid(operationId),
        ),
        requestBody = body,
        decode = { json.decodeFromString(responseSerializer, it) },
    )

    private fun uuid(id: String): String {
        require(id.length == 36 &&
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(id)
        ) { "A valid UUID is required." }
        return id
    }
}

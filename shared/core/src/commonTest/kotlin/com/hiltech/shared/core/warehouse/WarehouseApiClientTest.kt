package com.hiltech.shared.core.warehouse

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechApiException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WarehouseApiClientTest {
    private val org = "11111111-1111-1111-1111-111111111111"
    private val warehouseId = "22222222-2222-2222-2222-222222222222"
    private val batchId = "33333333-3333-3333-3333-333333333333"
    private val operation = "44444444-4444-4444-4444-444444444444"
    private val installation = "55555555-5555-5555-5555-555555555555"

    @Test
    fun physicalStockHistoryUsesWarehouseAndEmployeeScopeAndRetainsReceipt() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertEquals("/v1/stock-items/$batchId/fulfillment-history", request.url.encodedPath)
            assertEquals("GET", request.method.value)
            assertEquals(warehouseId, request.url.parameters["warehouseId"])
            assertEquals("Employee A", request.url.parameters["search"])
            assertEquals("Bearer warehouse-token", request.headers[HttpHeaders.Authorization])
            respond("""{"items":[{"movementId":"receipt","operationId":"$operation","type":"CONSUME",
                "quantity":"6.123456","unitCode":"SYNTH-UOM","employeeCode":"SYNTH-EMP","employeeName":"Employee A",
                "workOrderId":"$org","requirementId":"$batchId","occurredAt":"2026-11-18T12:00:00Z"}],"limited":false}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val result = client(http).stockFulfillmentHistory(batchId, warehouseId, "Employee A", installation)
        assertEquals("receipt", result.items.single().movementId)
        assertEquals("6.123456", result.items.single().quantity)
        assertFalse(result.limited)
    }

    @Test
    fun reservedStockIssuePreservesExactQuantityAndIdempotency() = runBlocking {
        val command = IssueReservedStockDto(operation, org, batchId, warehouseId, org, warehouseId,
            7, 8, 9, 10, "1.123456", "SYNTH-UOM")
        val http = HttpClient(MockEngine { request ->
            assertEquals("/v1/stock-items/$batchId/issue", request.url.encodedPath)
            assertEquals("POST", request.method.value)
            assertEquals(operation, request.headers["Idempotency-Key"])
            assertEquals(installation, request.headers["X-Device-Installation-Id"])
            val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            assertEquals(command, Json.decodeFromString<IssueReservedStockDto>(body))
            respond("""{"movementId":"synthetic-issue","operationId":"$operation","type":"ISSUE","itemId":"$batchId",
                "employeeId":"$warehouseId","workOrderId":"$org","requirementId":"$batchId","quantity":"1.123456",
                "unitCode":"SYNTH-UOM","replayed":false}""", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        assertEquals("1.123456", client(http).issueReservedStock(batchId, command, installation).quantity)
    }

    @Test
    fun issuedDirectoryIsScopedToWarehouseAndPreservesVersion() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertEquals("/v1/stock-items/$batchId/issued-directory", request.url.encodedPath)
            assertEquals(warehouseId, request.url.parameters["warehouseId"])
            assertEquals("Employee A", request.url.parameters["search"])
            assertEquals("Bearer warehouse-token", request.headers[HttpHeaders.Authorization])
            respond("""{"items":[{"context":{"stockItemId":"$batchId","employeeId":"$org","projectId":"$org",
                "siteId":"$org","workOrderId":"$org","requirementId":"$batchId","outstandingQuantity":"2.123456","version":3},
                "employeeCode":"SYNTH-EMP","employeeName":"Employee A","workCode":"WORK",
                "workTitle":"Synthetic task","workVersion":9,"unitCode":"SYNTH-UOM"}],"limited":false}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val result = client(http).issuedStockDirectory(batchId, warehouseId, "Employee A", installation)
        assertEquals("2.123456", result.items.single().context.outstandingQuantity)
        assertEquals(9L, result.items.single().workVersion)
        assertFalse(result.limited)
    }

    @Test
    fun physicalAssetInspectionBindsTheExactReturnAndCommandKey() = runBlocking {
        val command = InspectPhysicalReturnDto(operation, 7, AssetCondition.GOOD, "Synthetic condition check")
        val http = HttpClient(MockEngine { request ->
            assertEquals("/v1/assets/$batchId/returns/$warehouseId/inspect", request.url.encodedPath)
            assertEquals(operation, request.headers["Idempotency-Key"])
            val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            assertEquals(command, Json.decodeFromString<InspectPhysicalReturnDto>(body))
            respond("""{"recordId":"synthetic-inspection","assetId":"$batchId","kind":"INSPECT","replayed":true}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        assertTrue(client(http).inspectPhysicalReturn(batchId, warehouseId, command, installation).replayed)
    }

    @Test
    fun assetLookupUsesOnlyTheOpaqueSegmentAndTheAuthenticatedReadTransport() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertEquals("/v1/assets/by-tag/synthetic-opaque-tag", request.url.encodedPath)
            assertEquals("GET", request.method.value)
            assertEquals("Bearer warehouse-token", request.headers[HttpHeaders.Authorization])
            assertEquals(installation, request.headers["X-Device-Installation-Id"])
            respond(
                """{"assetCode":"SYNTH-PASSPORT","typeName":"Synthetic test instrument","condition":"GOOD",
                    "employeeCode":"SYNTH-CUSTODIAN","acquisitionCost":98765.43,
                    "availability":{"state":"BLOCKED_CALIBRATION","reasonCodes":["CALIBRATION_EXPIRED","MAINTENANCE_IN_PROGRESS"],
                    "calibrationStatus":"EXPIRED","certificateOnFile":true,"sourceId":"synthetic-private-ref"}}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val client = client(http)
        val result = client.lookupAssetTag("  synthetic-opaque-tag  ", installation)
        assertEquals("SYNTH-PASSPORT", result.assetCode)
        assertEquals("SYNTH-CUSTODIAN", result.employeeCode)
        assertEquals(listOf("CALIBRATION_EXPIRED", "MAINTENANCE_IN_PROGRESS"), result.availability?.reasonCodes)
        assertTrue(result.availability?.certificateOnFile == true)
        assertFalse(Json.encodeToString(AssetTagLookupDto.serializer(), result).contains("synthetic-private-ref"))
        assertFalse(Json.encodeToString(AssetTagLookupDto.serializer(), result).contains("98765.43"))
        assertFailsWith<IllegalArgumentException> { client.lookupAssetTag("https://example.invalid/tag", installation) }
        assertFailsWith<IllegalArgumentException> { client.lookupAssetTag("../asset", installation) }
    }

    @Test
    fun tagReplacementUsesTheCurrentVersionAndAStableCommandKey() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertEquals("/v1/assets/$batchId/replace-tag", request.url.encodedPath)
            assertEquals("POST", request.method.value)
            assertEquals(operation, request.headers["Idempotency-Key"])
            val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            assertEquals(AssetTagCommandDto(operation, 8, "QR", warehouseId), Json.decodeFromString<AssetTagCommandDto>(body))
            respond(
                """{"id":"replacement","code":"synthetic-new-tag","type":"QR","active":true,"primary":true,
                    "replacesTagId":"$warehouseId","issuedAt":"2026-11-13T06:00:00Z"}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val result = client(http).tagAsset(batchId, AssetTagCommandDto(operation, 8, "QR", warehouseId), installation)
        assertEquals(warehouseId, result.replacesTagId)
    }

    @Test
    fun stockTransferUsesTheAuthenticatedRouteAndPreservesExactDecimalText() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertEquals("/v1/stock-items/$batchId/transfer", request.url.encodedPath)
            assertEquals("POST", request.method.value)
            assertEquals(installation, request.headers["X-Device-Installation-Id"])
            assertEquals(operation, request.headers["Idempotency-Key"])
            val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            val sent = Json.decodeFromString<TransferStockDto>(body)
            assertEquals("99999999999999.123456", sent.quantity)
            assertEquals("SYNTH-UOM", sent.unitCode)
            respond(
                """{"replayed":true,"movement":{"id":"synthetic-move","operationId":"$operation","type":"TRANSFER","quantity":"99999999999999.123456","unitCode":"SYNTH-UOM","occurredAt":"2026-11-12T08:00:00Z","recordedBy":"synthetic-actor"}}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val receipt = client(http).transferStock(batchId, TransferStockDto(
            operation, 1, org, warehouseId, "99999999999999.123456", "SYNTH-UOM",
        ), installation)
        assertEquals("99999999999999.123456", receipt.movement.quantity)
        assertTrue(receipt.replayed)
    }

    @Test
    fun warehouseDirectoryAndCreateUseExistingAuthenticatedTransport() = runBlocking {
        val visited = mutableListOf<Pair<String, String?>>()
        val http = HttpClient(MockEngine { request ->
            assertEquals("Bearer warehouse-token", request.headers[HttpHeaders.Authorization])
            assertEquals(installation, request.headers["X-Device-Installation-Id"])
            visited += request.url.encodedPath to request.headers["Idempotency-Key"]
            val body = when (request.method.value) {
                "GET" -> """
                    {
                      "items":[{"warehouseId":"$warehouseId","organizationId":"$org",
                        "code":"WH-1","name":"Main","active":true,"version":1}],
                      "correlationId":"corr-list"
                    }
                """.trimIndent()
                "POST" -> """
                    {"warehouseId":"$warehouseId","organizationId":"$org",
                      "code":"WH-1","name":"Main","active":true,"version":1,
                      "replayed":false,"correlationId":"corr-create"}
                """.trimIndent()
                else -> error("Unexpected request")
            }
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val client = client(http)
        val directory = client.warehouses(org, installation)
        assertEquals(1, directory.items.size)
        assertEquals("WH-1", directory.items.single().code)

        val created = client.createWarehouse(
            WarehouseCreateRequestDto(
                operationId = operation, organizationId = org,
                code = "WH-1", name = "Main",
                clientOccurredAt = "2026-11-07T12:00:00Z",
            ),
            installation,
        )
        assertEquals(warehouseId, created.warehouseId)
        assertEquals(
            listOf(
                "/v1/warehouses" to null,
                "/v1/warehouses" to operation,
            ),
            visited,
        )
    }

    @Test
    fun importReviewResponseDoesNotSurfaceRawOrFinancialColumnsInSharedDto() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertEquals("/v1/inventory-imports/$batchId", request.url.encodedPath)
            respond(
                content = """
                    {
                      "batchId":"$batchId","organizationId":"$org",
                      "sourceFileName":"legacy.xlsx","sourceSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                      "sourceType":"LEGACY_EXCEL","cutoverDate":null,
                      "state":"IN_REVIEW","version":2,
                      "rows":[{
                        "rowId":"66666666-6666-6666-6666-666666666666",
                        "batchId":"$batchId","sourceSheet":"Accessories",
                        "sourceRowNumber":2,"candidateClass":"UNCLASSIFIED",
                        "normalizedBrand":null,"normalizedPartNumber":null,
                        "normalizedDescription":null,"candidateUom":null,
                        "candidateQuantity":null,
                        "issueCodes":["LEGACY_COLUMN_SEMANTICS_AMBIGUOUS"],
                        "reviewState":"PENDING","version":1
                      }],
                      "correlationId":"corr-import"
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val imported = client(http).importBatch(batchId, installation)
        assertEquals("Accessories", imported.rows.single().sourceSheet)
        assertTrue(imported.rows.single().issueCodes.contains("LEGACY_COLUMN_SEMANTICS_AMBIGUOUS"))
        val exported = Json.encodeToString(InventoryImportBatchDto.serializer(), imported)
        assertFalse(exported.contains("rawPayload"))
        assertFalse(exported.contains("candidateValue"))
        assertFalse(exported.contains("candidateCurrencyCode"))
    }

    @Test
    fun importDirectoryIsBrowsableWithoutRawRows() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertEquals("/v1/inventory-imports", request.url.encodedPath)
            assertEquals("organizationId=$org", request.url.encodedQuery)
            respond(
                content = """
                    {
                      "items":[{
                        "batchId":"$batchId","organizationId":"$org",
                        "sourceFileName":"stock.xlsx","sourceSha256":"${"a".repeat(64)}",
                        "sourceType":"LEGACY_EXCEL","cutoverDate":null,
                        "state":"IN_REVIEW","version":2
                      }],
                      "correlationId":"corr-import-directory"
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val items = client(http).importBatches(org, installation).items
        assertEquals(1, items.size)
        assertEquals("stock.xlsx", items.single().sourceFileName)
    }

    @Test
    fun invalidIdentifiersCannotBecomeRequests() = runBlocking {
        var called = false
        val http = HttpClient(MockEngine {
            called = true
            error("Unexpected network request")
        })
        val client = client(http)
        assertFailsWith<IllegalArgumentException> {
            client.warehouse("../../other-resource", installation)
        }
        assertFalse(called)
    }

    @Test
    fun deniedWarehouseScopePropagatesAccessError() = runBlocking {
        val http = HttpClient(MockEngine {
            respond(
                content = """
                    {"code":"OBJECT_NOT_VISIBLE","message":"Not available",
                      "correlationId":"corr-guard","retryable":false}
                """.trimIndent(),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val error = assertFailsWith<HiltechApiException> {
            client(http).currentWarehouseAuthority(warehouseId, installation)
        }
        assertEquals("OBJECT_NOT_VISIBLE", error.code)
        assertEquals("corr-guard", error.correlationId)
    }

    private fun client(http: HttpClient) = WarehouseApiClient(
        HiltechApiClient(
            client = http,
            baseUrl = "https://api.hiltech.test",
            accessTokenProvider = { "warehouse-token" },
            correlationIdProvider = { "corr-client" },
        ),
    )
}

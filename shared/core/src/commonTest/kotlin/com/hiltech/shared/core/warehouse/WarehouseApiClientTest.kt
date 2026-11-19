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
    fun passportCarriesAssignedInspectionRevisionWithoutCatalogLookup() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertEquals("/v1/assets/$batchId", request.url.encodedPath)
            respond("""{"identity":{"id":"$batchId","organizationId":"$org","warehouseId":"$warehouseId",
                "assetCode":"SYNTH-TOOL","typeId":"$operation","typeName":"Synthetic type","typeRevision":3,
                "condition":"GOOD","lifecycle":"ACTIVE","duplicateSerial":false,"version":7},
                "tags":[],"canOperatePhysical":true,"returnInspectionType":{"id":"$operation","name":"Synthetic type",
                "revision":3,"serialRequired":false,"calibrationRequired":false,"highValueOrRestricted":false,
                "allowedTagTypes":[],"operatorMayTag":false,"inspectionRequiredOnReturn":true}}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val passport = client(http).assetPassport(batchId, installation)
        assertEquals(passport.identity.typeId, passport.returnInspectionType?.id)
        assertEquals(passport.identity.typeRevision, passport.returnInspectionType?.revision)
        assertTrue(passport.returnInspectionType?.inspectionRequiredOnReturn == true)
    }

    @Test
    fun dueReturnSnapshotUsesWarehouseAndServerTime() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertEquals("/v1/warehouses/$warehouseId/due-asset-returns", request.url.encodedPath)
            assertEquals("GET", request.method.value)
            assertEquals("Bearer warehouse-token", request.headers[HttpHeaders.Authorization])
            respond("""{"items":[{"assetId":"$batchId","assetCode":"SYNTH-TOOL","employeeCode":"SYNTH-EMP",
                "employeeName":"Synthetic recipient","sourceMovementId":"$operation",
                "expectedReturnAt":"2026-11-18T12:00:00Z","overdue":true}],"limited":false,
                "asOf":"2026-11-18T13:00:00Z"}""", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val result = client(http).dueAssetReturns(warehouseId, installation)
        assertEquals("2026-11-18T13:00:00Z", result.asOf)
        assertTrue(result.items.single().overdue)
        assertEquals(operation, result.items.single().sourceMovementId)
        assertFalse(result.limited)
    }

    @Test
    fun dueReturnCursorIsStableAndEncoded() = runBlocking {
        val afterDue = "2026-11-18T12:00:00Z"
        val afterCode = "SYNTH TOOL/02"
        val afterId = operation
        val http = HttpClient(MockEngine { request ->
            assertEquals("/v1/warehouses/$warehouseId/due-asset-returns", request.url.encodedPath)
            assertEquals(afterDue, request.url.parameters["afterExpectedReturnAt"])
            assertEquals(afterCode, request.url.parameters["afterAssetCode"])
            assertEquals(afterId, request.url.parameters["afterAssetId"])
            respond("""{"items":[],"limited":false,"asOf":"2026-11-18T13:00:00Z"}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val page = client(http).dueAssetReturns(warehouseId, installation, afterDue, afterCode, afterId)
        assertTrue(page.items.isEmpty())
        assertFalse(page.limited)
    }

    @Test
    fun storageTransferPreservesIdempotencyQuantityAndReceipt() = runBlocking {
        val command = TransferStockDto(operation, 7, warehouseId, org, "1.123456", "SYNTH-UOM")
        val http = HttpClient(MockEngine { request ->
            assertEquals("/v1/stock-items/$batchId/transfer", request.url.encodedPath)
            assertEquals("POST", request.method.value)
            assertEquals(operation, request.headers["Idempotency-Key"])
            assertEquals(installation, request.headers["X-Device-Installation-Id"])
            val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            assertEquals(command, Json.decodeFromString<TransferStockDto>(body))
            respond("""{"movement":{"id":"transfer-receipt","operationId":"$operation","type":"TRANSFER",
                "quantity":"1.123456","unitCode":"SYNTH-UOM","fromStorageLocationId":"$warehouseId",
                "toStorageLocationId":"$org","occurredAt":"2026-11-18T12:00:00Z","recordedBy":"$org"},"replayed":true}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val receipt = client(http).transferStock(batchId, command, installation)
        assertEquals("transfer-receipt", receipt.movement.id)
        assertEquals("1.123456", receipt.movement.quantity)
        assertTrue(receipt.replayed)
    }

    @Test
    fun physicalStockHistoryCursorIsEncoded() = runBlocking {
        val after = "2026-11-18T12:34:56Z"
        val http = HttpClient(MockEngine { request ->
            assertEquals("/v1/stock-items/$batchId/fulfillment-history", request.url.encodedPath)
            assertEquals(warehouseId, request.url.parameters["warehouseId"])
            assertEquals("Employee A", request.url.parameters["search"])
            assertEquals(after, request.url.parameters["afterRecordedAt"])
            assertEquals(operation, request.url.parameters["afterMovementId"])
            respond("""{"items":[],"limited":false}""", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val result = client(http).stockFulfillmentHistory(
            batchId, warehouseId, "Employee A", installation, after, operation,
        )
        assertTrue(result.items.isEmpty())
        assertFalse(result.limited)
    }

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
    fun assetReceivingRoutesPreserveExplicitTypeAndAssetIdentity() = runBlocking {
        var call = 0
        val lineId = "66666666-6666-6666-6666-666666666666"
        val assetTypeId = "77777777-7777-7777-7777-777777777777"
        val assetId = "88888888-8888-8888-8888-888888888888"
        val http = HttpClient(MockEngine { request ->
            call++
            assertEquals(operation, request.headers["Idempotency-Key"])
            assertEquals(installation, request.headers["X-Device-Installation-Id"])
            when (call) {
                1 -> {
                    assertEquals("/v1/receiving/$batchId/asset-lines", request.url.encodedPath)
                    val body=(request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    val sent=Json.decodeFromString<AddReceivingAssetLineDto>(body)
                    assertEquals(assetTypeId, sent.assetTypeId)
                    respond("""{"line":{"id":"$lineId","receivingRecordId":"$batchId","organizationId":"$org",
                        "candidateClass":"COMPANY_ASSET","expectedAssetTypeDefinitionId":"$assetTypeId",
                        "expectedQuantity":"1.000000","serials":[],"version":1},"replayed":false}""",
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                2 -> {
                    assertEquals("/v1/receiving/$batchId/receive-asset-line", request.url.encodedPath)
                    val body=(request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    val sent=Json.decodeFromString<ReceiveAssetLineDto>(body)
                    assertEquals(lineId, sent.lineId)
                    assertEquals("SYNTH-RX-ASSET", sent.assetCode)
                    assertEquals("RX-SER-001", sent.serialNumber)
                    respond("""{"line":{"id":"$lineId","receivingRecordId":"$batchId","organizationId":"$org",
                        "candidateClass":"COMPANY_ASSET","expectedAssetTypeDefinitionId":"$assetTypeId",
                        "expectedQuantity":"1.000000","receivedQuantity":"1.000000","condition":"GOOD",
                        "serials":["RX-SER-001"],"receivedAssetId":"$assetId","receivedBy":"$org",
                        "receivedAt":"2026-11-19T04:00:00Z","version":2},
                        "asset":{"id":"$assetId","organizationId":"$org","warehouseId":"$warehouseId",
                        "assetCode":"SYNTH-RX-ASSET","name":"Synthetic received tool","serialNumber":"RX-SER-001",
                        "typeId":"$assetTypeId","typeName":"Synthetic received Asset type","typeRevision":1,
                        "condition":"GOOD","lifecycle":"ACTIVE","duplicateSerial":false,"version":2},
                        "movementId":"$operation","replayed":false}""",
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                else -> error("Unexpected request")
            }
        })
        val client=client(http)
        val line=client.addReceivingAssetLine(batchId,
            AddReceivingAssetLineDto(operation, 3, assetTypeId, "ASSET-LINE"), installation)
        assertEquals("COMPANY_ASSET", line.line.candidateClass)
        val receipt=client.receiveAssetLine(batchId,
            ReceiveAssetLineDto(operation, lineId, 4, 1, "SYNTH-RX-ASSET", "Synthetic received tool",
                serialNumber="RX-SER-001", ownershipType="COMPANY", condition=AssetCondition.GOOD), installation)
        assertEquals(assetId, receipt.line.receivedAssetId)
        assertEquals(assetId, receipt.asset.id)
        assertFalse(receipt.replayed)
    }

    @Test
    fun receivingRoutesPreserveExpectedSourceAndIdempotencyKey() = runBlocking {
        var call = 0
        val lineId = "66666666-6666-6666-6666-666666666666"
        val http = HttpClient(MockEngine { request ->
            call++
            assertEquals("Bearer warehouse-token", request.headers[HttpHeaders.Authorization])
            assertEquals(installation, request.headers["X-Device-Installation-Id"])
            when (call) {
                1 -> {
                    assertEquals("/v1/receiving", request.url.encodedPath)
                    assertEquals(warehouseId, request.url.parameters["warehouseId"])
                    respond("""{"items":[{"id":"$batchId","organizationId":"$org",
                        "destinationStorageLocationId":"$warehouseId","sourceType":"OTHER","state":"IN_PROGRESS",
                        "createdBy":"$org","createdAt":"2026-11-19T03:00:00Z","updatedAt":"2026-11-19T03:01:00Z",
                        "correlationId":"corr","version":3}],"limited":false}""",
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                2 -> {
                    assertEquals("/v1/receiving/$batchId/discrepancies", request.url.encodedPath)
                    assertEquals(operation, request.headers["Idempotency-Key"])
                    val body=(request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    val sent=Json.decodeFromString<AddReceivingDiscrepancyDto>(body)
                    assertEquals("SHORT_QTY", sent.type)
                    assertEquals(lineId, sent.lineId)
                    respond("""{"discrepancy":{"id":"$operation","receivingRecordId":"$batchId",
                        "receivingLineId":"$lineId","organizationId":"$org","type":"SHORT_QTY",
                        "description":"One missing","createdBy":"$org","createdAt":"2026-11-19T03:02:00Z","version":1},
                        "record":{"id":"$batchId","organizationId":"$org","destinationStorageLocationId":"$warehouseId",
                        "sourceType":"OTHER","state":"IN_PROGRESS","createdBy":"$org",
                        "createdAt":"2026-11-19T03:00:00Z","updatedAt":"2026-11-19T03:02:00Z",
                        "correlationId":"corr","version":4},"replayed":false}""",
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                else -> error("Unexpected request")
            }
        })
        val client=client(http)
        assertEquals(batchId, client.receivingDirectory(warehouseId, installation).items.single().id)
        val discrepancy=client.addReceivingDiscrepancy(batchId,
            AddReceivingDiscrepancyDto(operation, 3, lineId, "SHORT_QTY", "One missing"), installation)
        assertEquals("SHORT_QTY", discrepancy.discrepancy.type)
        assertFalse(discrepancy.replayed)
    }

    @Test
    fun stocktakeRoutesUseAuthenticatedTransportExactVersionsAndCommandKeys() = runBlocking {
        val sessionId = batchId
        val snapshotId = "66666666-6666-6666-6666-666666666666"
        val discrepancyId = "77777777-7777-7777-7777-777777777777"
        val adjustmentId = "88888888-8888-4888-8888-888888888888"
        val approvalId = "99999999-9999-4999-8999-999999999999"
        val movementId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val applyOperation = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        var call = 0
        val http = HttpClient(MockEngine { request ->
            call++
            assertEquals("Bearer warehouse-token", request.headers[HttpHeaders.Authorization])
            assertEquals(installation, request.headers["X-Device-Installation-Id"])
            when (call) {
                1 -> {
                    assertEquals("/v1/stocktakes", request.url.encodedPath)
                    assertEquals(operation, request.headers["Idempotency-Key"])
                    val sent = Json.decodeFromString<CreateStocktakeDto>(
                        (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
                    assertEquals(listOf(batchId), sent.stockItemIds)
                    respond("""{"session":{"id":"$sessionId","organizationId":"$org","warehouseId":"$warehouseId",
                        "rootStorageLocationId":"$warehouseId","state":"DRAFT","categoryIds":[],"stockItemIds":["$batchId"],
                        "createdBy":"$org","createdAt":"2026-11-19T18:00:00Z","correlationId":"corr","version":1},
                        "snapshotLines":[],"replayed":false}""",
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                2 -> {
                    assertEquals("/v1/stocktakes/$sessionId", request.url.encodedPath)
                    assertEquals("GET", request.method.value)
                    respond("""{"session":{"id":"$sessionId","organizationId":"$org","warehouseId":"$warehouseId",
                        "rootStorageLocationId":"$warehouseId","state":"DRAFT","categoryIds":[],"stockItemIds":["$batchId"],
                        "createdBy":"$org","createdAt":"2026-11-19T18:00:00Z","correlationId":"corr","version":1},
                        "snapshotLines":[],"countRecords":[],"discrepancies":[],"adjustmentRequests":[]}""",
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                3 -> {
                    assertEquals("/v1/stocktakes/$sessionId/start", request.url.encodedPath)
                    assertEquals(operation, request.headers["Idempotency-Key"])
                    respond("""{"session":{"id":"$sessionId","organizationId":"$org","warehouseId":"$warehouseId",
                        "rootStorageLocationId":"$warehouseId","state":"ACTIVE","categoryIds":[],"stockItemIds":["$batchId"],
                        "snapshotAt":"2026-11-19T18:01:00Z","createdBy":"$org","createdAt":"2026-11-19T18:00:00Z",
                        "correlationId":"corr","version":2},"snapshotLines":[{"id":"$snapshotId","sessionId":"$sessionId",
                        "organizationId":"$org","stockItemId":"$batchId","itemCode":"SYNTH-STOCK",
                        "storageLocationId":"$warehouseId","locationCode":"BIN-1","expectedOnHand":"6.000000",
                        "expectedReserved":"0.000000","expectedDamaged":"0.000000","expectedQuarantine":"0.000000",
                        "unitCode":"EA","balanceVersion":2,"itemVersion":1,"snapshotAt":"2026-11-19T18:01:00Z"}],
                        "replayed":false}""",
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                4 -> {
                    assertEquals("/v1/stocktakes/$sessionId/counts", request.url.encodedPath)
                    assertEquals(operation, request.headers["Idempotency-Key"])
                    val sent = Json.decodeFromString<RecordStockCountDto>(
                        (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
                    assertEquals("5.000000", sent.countedQuantity)
                    respond("""{"count":{"id":"$operation","sessionId":"$sessionId","snapshotLineId":"$snapshotId",
                        "organizationId":"$org","stockItemId":"$batchId","storageLocationId":"$warehouseId",
                        "countedQuantity":"5.000000","unitCode":"EA","countedBy":"$org",
                        "countedAt":"2026-11-19T18:02:00Z","operationId":"$operation","correlationId":"corr"},
                        "replayed":false}""",
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                5 -> {
                    assertEquals("/v1/stocktakes/$sessionId/submit", request.url.encodedPath)
                    assertEquals(operation, request.headers["Idempotency-Key"])
                    respond("""{"session":{"id":"$sessionId","organizationId":"$org","warehouseId":"$warehouseId",
                        "rootStorageLocationId":"$warehouseId","state":"SUBMITTED","categoryIds":[],"stockItemIds":["$batchId"],
                        "snapshotAt":"2026-11-19T18:01:00Z","createdBy":"$org","createdAt":"2026-11-19T18:00:00Z",
                        "correlationId":"corr","version":3},"discrepancies":[{"id":"$discrepancyId","sessionId":"$sessionId",
                        "snapshotLineId":"$snapshotId","latestCountRecordId":"$operation","organizationId":"$org",
                        "stockItemId":"$batchId","storageLocationId":"$warehouseId","expectedQuantity":"6.000000",
                        "countedQuantity":"5.000000","varianceQuantity":"-1.000000","snapshotBalanceVersion":2,
                        "currentBalanceVersion":2,"sourceChangedSinceSnapshot":false,"state":"OPEN","createdBy":"$org",
                        "createdAt":"2026-11-19T18:03:00Z","updatedAt":"2026-11-19T18:03:00Z","version":1}],
                        "replayed":false}""",
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                6 -> {
                    assertEquals("/v1/stocktakes/$sessionId/variances", request.url.encodedPath)
                    assertEquals("GET", request.method.value)
                    respond("""{"session":{"id":"$sessionId","organizationId":"$org","warehouseId":"$warehouseId",
                        "rootStorageLocationId":"$warehouseId","state":"SUBMITTED","categoryIds":[],"stockItemIds":["$batchId"],
                        "snapshotAt":"2026-11-19T18:01:00Z","createdBy":"$org","createdAt":"2026-11-19T18:00:00Z",
                        "correlationId":"corr","version":3},"discrepancies":[{"id":"$discrepancyId","sessionId":"$sessionId",
                        "snapshotLineId":"$snapshotId","latestCountRecordId":"$operation","organizationId":"$org",
                        "stockItemId":"$batchId","storageLocationId":"$warehouseId","expectedQuantity":"6.000000",
                        "countedQuantity":"5.000000","varianceQuantity":"-1.000000","snapshotBalanceVersion":2,
                        "currentBalanceVersion":2,"sourceChangedSinceSnapshot":false,"state":"OPEN","createdBy":"$org",
                        "createdAt":"2026-11-19T18:03:00Z","updatedAt":"2026-11-19T18:03:00Z","version":1}],
                        "adjustmentRequests":[]}""",
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                7 -> {
                    assertEquals("/v1/inventory-discrepancies/$discrepancyId/request-adjustment", request.url.encodedPath)
                    assertEquals(operation, request.headers["Idempotency-Key"])
                    val sent = Json.decodeFromString<RequestInventoryAdjustmentDto>(
                        (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
                    assertEquals(1L, sent.discrepancyVersion)
                    assertEquals("Confirmed physical shortage", sent.reason)
                    respond("""{"request":{"id":"$adjustmentId","discrepancyId":"$discrepancyId","sessionId":"$sessionId",
                        "organizationId":"$org","stockItemId":"$batchId","storageLocationId":"$warehouseId",
                        "deltaQuantity":"-1.000000","unitCode":"EA","reason":"Confirmed physical shortage",
                        "requestedBy":"$org","requestedAt":"2026-11-19T18:04:00Z",
                        "approvalRequestId":"$approvalId","approvalStatus":"PENDING","version":1},"replayed":false}""",
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                8 -> {
                    assertEquals("/v1/inventory-adjustments/$adjustmentId/apply", request.url.encodedPath)
                    assertEquals(applyOperation, request.headers["Idempotency-Key"])
                    val sent = Json.decodeFromString<ApplyInventoryAdjustmentDto>(
                        (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
                    assertEquals(1L, sent.requestVersion)
                    respond("""{"request":{"id":"$adjustmentId","discrepancyId":"$discrepancyId","sessionId":"$sessionId",
                        "organizationId":"$org","stockItemId":"$batchId","storageLocationId":"$warehouseId",
                        "deltaQuantity":"-1.000000","unitCode":"EA","reason":"Confirmed physical shortage",
                        "requestedBy":"$org","requestedAt":"2026-11-19T18:04:00Z",
                        "approvalRequestId":"$approvalId","approvalStatus":"APPROVED",
                        "appliedMovementId":"$movementId","appliedBy":"$org","appliedAt":"2026-11-19T18:05:00Z","version":2},
                        "movement":{"id":"$movementId","operationId":"$applyOperation","type":"ADJUSTMENT",
                        "quantity":"1.000000","unitCode":"EA","fromStorageLocationId":"$warehouseId",
                        "occurredAt":"2026-11-19T18:05:00Z","recordedBy":"$org"},
                        "session":{"id":"$sessionId","organizationId":"$org","warehouseId":"$warehouseId",
                        "rootStorageLocationId":"$warehouseId","state":"CLOSED","categoryIds":[],"stockItemIds":["$batchId"],
                        "snapshotAt":"2026-11-19T18:01:00Z","createdBy":"$org","createdAt":"2026-11-19T18:00:00Z",
                        "correlationId":"corr","version":4},"replayed":false}""",
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                else -> error("Unexpected request")
            }
        })

        val client = client(http)
        val created = client.createStocktake(
            CreateStocktakeDto(operation, org, warehouseId, warehouseId, stockItemIds = listOf(batchId)),
            installation,
        )
        assertEquals("DRAFT", created.session.state)
        assertTrue(client.stocktake(sessionId, installation).countRecords.isEmpty())
        val started = client.startStocktake(sessionId, StartStocktakeDto(operation, 1), installation)
        assertEquals(snapshotId, started.snapshotLines.single().id)
        val counted = client.recordStockCount(
            sessionId, RecordStockCountDto(operation, 2, snapshotId, "5.000000", "EA"), installation)
        assertEquals("5.000000", counted.count.countedQuantity)
        val submitted = client.submitStocktake(sessionId, SubmitStocktakeDto(operation, 2), installation)
        assertEquals("-1.000000", submitted.discrepancies.single().varianceQuantity)
        val variances = client.stocktakeVariances(sessionId, installation)
        assertFalse(variances.discrepancies.single().sourceChangedSinceSnapshot)
        val adjustment = client.requestInventoryAdjustment(
            discrepancyId, RequestInventoryAdjustmentDto(operation, 1, "Confirmed physical shortage"), installation)
        assertEquals("-1.000000", adjustment.request.deltaQuantity)
        assertEquals(approvalId, adjustment.request.approvalRequestId)
        assertEquals("PENDING", adjustment.request.approvalStatus)
        val applied = client.applyInventoryAdjustment(
            adjustmentId, ApplyInventoryAdjustmentDto(applyOperation, 1), installation)
        assertEquals("ADJUSTMENT", applied.movement.type)
        assertEquals("1.000000", applied.movement.quantity)
        assertEquals(warehouseId, applied.movement.fromStorageLocationId)
        assertEquals(movementId, applied.request.appliedMovementId)
        assertEquals("APPROVED", applied.request.approvalStatus)
        assertEquals("CLOSED", applied.session.state)
        assertFalse(applied.replayed)
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

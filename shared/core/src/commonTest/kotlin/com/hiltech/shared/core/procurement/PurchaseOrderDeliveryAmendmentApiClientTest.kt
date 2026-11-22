package com.hiltech.shared.core.procurement

import com.hiltech.shared.core.network.HiltechApiClient
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
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PurchaseOrderDeliveryAmendmentApiClientTest {
    private val po = "11111111-1111-1111-1111-111111111111"
    private val proposal = "22222222-2222-2222-2222-222222222222"
    private val op = "33333333-3333-3333-3333-333333333333"
    private val destination = "44444444-4444-4444-4444-444444444444"
    private val installation = "55555555-5555-5555-5555-555555555555"

    private fun client(http: HttpClient) = PurchaseOrderDeliveryAmendmentApiClient(
        HiltechApiClient(
            client = http, baseUrl = "https://api.hiltech.test",
            accessTokenProvider = { "issuer-token" },
            correlationIdProvider = { "po-amendment" },
        ),
    )

    @Test fun onlyExactScopedDestinationsAndCurrentProposalAreRead() = runBlocking {
        val http = HttpClient(MockEngine { req ->
            assertEquals("GET", req.method.value)
            assertEquals("Bearer issuer-token", req.headers[HttpHeaders.Authorization])
            val body = when (req.url.encodedPath) {
                "/v1/procurement/purchase-orders/$po/amendment-destinations" ->
                    """[{"storageLocationId":"$destination","code":"W02",
                    "name":"Site warehouse","kind":"SITE_STORAGE"}]"""
                "/v1/procurement/purchase-orders/$po/current-delivery-amendment" ->
                    """{"proposal":null}"""
                else -> error("Unexpected issuer route " + req.url)
            }
            respond(body, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val api = client(http)
        assertEquals(destination,
            api.destinations(po, installation).single().storageLocationId)
        assertNull(api.current(po, installation))
    }

    @Test fun proposeApproveAndApplyAreThreeExplicitVersionBoundCommands() = runBlocking {
        val propose = ProposePurchaseOrderDeliveryAmendmentDto(
            op, 1L, destination, "Authorized destination change before dispatch",
        )
        val http = HttpClient(MockEngine { req ->
            assertEquals("POST", req.method.value)
            assertEquals(op, req.headers["Idempotency-Key"])
            assertEquals("Bearer issuer-token", req.headers[HttpHeaders.Authorization])
            val body = (req.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            val response = when (req.url.encodedPath) {
                "/v1/procurement/purchase-orders/$po/delivery-amendments" -> {
                    assertEquals(propose,
                        Json.decodeFromString<ProposePurchaseOrderDeliveryAmendmentDto>(body))
                    """{"proposalId":"$proposal","purchaseOrderId":"$po",
                    "expectedOrderVersion":1,"proposedStorageLocationId":"$destination",
                    "state":"DRAFT_FOR_APPROVAL","replayed":false}"""
                }
                "/v1/procurement/po-delivery-amendments/$proposal/request-approval" -> {
                    assertEquals(
                        RequestPurchaseOrderDeliveryAmendmentApprovalDto(op),
                        Json.decodeFromString<RequestPurchaseOrderDeliveryAmendmentApprovalDto>(body),
                    )
                    """{"proposalId":"$proposal","approvalRequestId":"$op",
                    "policyKey":"PO_DELIVERY_POLICY","policyVersion":5,"approvalState":"PENDING",
                    "approvalRequired":true,"replayed":false}"""
                }
                "/v1/procurement/po-delivery-amendments/$proposal/apply" -> {
                    assertEquals(
                        ApplyPurchaseOrderDeliveryAmendmentDto(op, 1L),
                        Json.decodeFromString<ApplyPurchaseOrderDeliveryAmendmentDto>(body),
                    )
                    """{"purchaseOrderId":"$po","code":"PO-000093","eventId":"$op",
                    "orderVersion":2,"effectiveDestinationId":"$destination",
                    "approvalRequestId":"$op","state":"ISSUED","replayed":false,
                    "supplierNotified":false,"financeChanged":false,"inventoryChanged":false}"""
                }
                else -> error("Unexpected command path " + req.url)
            }
            respond(response, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val api = client(http)
        assertEquals("DRAFT_FOR_APPROVAL", api.propose(po, propose, installation).state)
        val approval = api.requestApproval(
            proposal, RequestPurchaseOrderDeliveryAmendmentApprovalDto(op), installation,
        )
        assertEquals("PENDING", approval.approvalState)
        assertEquals(5, approval.policyVersion)
        val applied = api.apply(
            proposal, ApplyPurchaseOrderDeliveryAmendmentDto(op, 1L), installation,
        )
        assertEquals(2L, applied.orderVersion)
        assertEquals(destination, applied.effectiveDestinationId)
        assertFalse(applied.supplierNotified)
        assertFalse(applied.financeChanged)
        assertFalse(applied.inventoryChanged)
    }
}

package com.hiltech.server.procurement

import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.warehouse.AssetPassportPersistence
import com.hiltech.server.warehouse.ReservationPersistence
import com.hiltech.server.warehouse.StockLedgerPersistence
import com.hiltech.server.warehouse.WarehouseAuthorizationPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class AssetPurchaseNeedServiceTest {
    private val roots = Mockito.mock(PurchaseRequirementDraftPersistence::class.java)
    private val sources = Mockito.mock(AssetPurchaseSource::class.java)
    private val assets = Mockito.mock(AssetPassportPersistence::class.java)
    private val stock = Mockito.mock(StockLedgerPersistence::class.java)
    private val reservations = Mockito.mock(ReservationPersistence::class.java)
    private val authority = Mockito.mock(WarehouseAuthorizationPort::class.java)
    private val verifier = Mockito.mock(ProcurementDemandSourceVerifier::class.java)
    private val commands = Mockito.mock(IdempotentCommandExecutor::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)
    private val clock = Clock.fixed(Instant.parse("2026-11-22T20:00:00Z"), ZoneOffset.UTC)
    private val actor = UUID.randomUUID()
    private val op = UUID.randomUUID()
    private val ctx = ProcurementDraftContext(op, actor, "asset-parts-test")
    private val asset = UUID.randomUUID()
    private val incident = UUID.randomUUID()
    private val maintenance = UUID.randomUUID()

    private fun service(requirements: Boolean, parts: Boolean) =
        AssetPurchaseNeedService(roots, sources, assets, stock, reservations,
            authority, verifier, commands, audit, clock, requirements, parts)

    private fun request(incidentId: UUID? = incident, maintenanceId: UUID? = null) =
        CreateAssetPurchaseNeedRequest(
            op, 1, incidentId, maintenanceId, 1, UUID.randomUUID(), 1,
            UUID.randomUUID(), 1, "2", "PCS", "Genuine spare part needed for equipment.",
        )

    @Test fun assetProcurementFlagIsIndependentAndOffByDefault() {
        assertEquals("ASSET_PURCHASE_NEED_UNAVAILABLE",
            assertThrows<ProductApiException> {
                service(true, false).create(ctx, asset, request())
            }.code)
        Mockito.verifyNoInteractions(roots, sources, assets, stock, reservations,
            authority, verifier, commands, audit)
    }

    @Test fun existingProcurementFlagStillBlocksTheAssetAdapter() {
        assertEquals("ASSET_PURCHASE_NEED_UNAVAILABLE",
            assertThrows<ProductApiException> {
                service(false, true).list(actor, asset)
            }.code)
        Mockito.verifyNoInteractions(roots, sources, assets, stock, reservations,
            authority, verifier, commands, audit)
    }

    @Test fun cannotClaimAnAssetIssueWithoutExactlyOneRealIncidentOrMaintenance() {
        assertEquals("ASSET_PURCHASE_NEED_INVALID",
            assertThrows<ProductApiException> {
                service(true, true).create(ctx, asset, request(null, null))
            }.code)
        assertEquals("ASSET_PURCHASE_NEED_INVALID",
            assertThrows<ProductApiException> {
                service(true, true).create(ctx, asset, request(incident, maintenance))
            }.code)
        Mockito.verifyNoInteractions(roots, sources, assets, stock, reservations,
            authority, verifier, commands, audit)
    }

    @Test fun unknownIssueOrForeignAssetDoesNotBecomePurchasingSource() {
        val input = request()
        assertEquals("ASSET_PURCHASE_NEED_UNAVAILABLE",
            assertThrows<ProductApiException> {
                service(true, true).create(ctx, asset, input)
            }.code)
        Mockito.verify(sources).resolve(
            asset, input.incidentId, input.maintenanceId, input.deliveryStorageLocationId,
        )
        Mockito.verifyNoInteractions(roots, assets, stock, reservations,
            authority, verifier, commands, audit)
    }

    @Test fun invalidPolymorphicTicketFailsBeforeReadingAnyAsset() {
        val warehouse = Mockito.mock(com.hiltech.server.warehouse.WarehousePersistencePort::class.java)
        val issues = Mockito.mock(com.hiltech.server.warehouse.AssetServicePersistence::class.java)
        val source = AssetPurchaseSource(assets, issues, warehouse, authority, clock)
        assertNull(source.resolve(asset, incident, maintenance, UUID.randomUUID()))
        assertNull(source.resolve(asset, null, null, UUID.randomUUID()))
        Mockito.verifyNoInteractions(assets, issues, warehouse, authority)
    }
}

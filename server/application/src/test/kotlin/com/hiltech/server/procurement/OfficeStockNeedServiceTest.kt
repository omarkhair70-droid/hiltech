package com.hiltech.server.procurement

import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.warehouse.ReservationPersistence
import com.hiltech.server.warehouse.StockLedgerPersistence
import com.hiltech.server.warehouse.WarehouseAuthorizationPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class OfficeStockNeedServiceTest {
    private val jdbc = Mockito.mock(JdbcTemplate::class.java)
    private val drafts = Mockito.mock(PurchaseRequirementDraftPersistence::class.java)
    private val office = Mockito.mock(OfficeStockSource::class.java)
    private val verifier = Mockito.mock(ProcurementDemandSourceVerifier::class.java)
    private val stock = Mockito.mock(StockLedgerPersistence::class.java)
    private val authority = Mockito.mock(WarehouseAuthorizationPort::class.java)
    private val reservations = Mockito.mock(ReservationPersistence::class.java)
    private val commands = Mockito.mock(IdempotentCommandExecutor::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)
    private val clock=Clock.fixed(Instant.parse("2026-11-22T20:00:00Z"),ZoneOffset.UTC)
    private val org=UUID.randomUUID()
    private val team=UUID.randomUUID()
    private val actor=UUID.randomUUID()
    private val operation=UUID.randomUUID()
    private val ctx=ProcurementDraftContext(operation,actor,"office-team-test")

    private fun service(reqEnabled:Boolean,officeEnabled:Boolean)=OfficeStockNeedService(
        jdbc,drafts,office,verifier,stock,authority,reservations,
        commands,audit,clock,reqEnabled,officeEnabled,
    )
    private fun request()=CreateOfficeStockNeedRequest(
        operation,1,UUID.randomUUID(),1,UUID.randomUUID(),1,
        "2","PCS","Official office consumable stock demand.",
    )

    @Test fun officeFeatureRequiresBothIndependentFlags() {
        assertEquals("OFFICE_STOCK_NEED_UNAVAILABLE",
            assertThrows<ProductApiException> {
                service(false,true).teams(actor,org)
            }.code)
        assertEquals("OFFICE_STOCK_NEED_UNAVAILABLE",
            assertThrows<ProductApiException> {
                service(true,false).teams(actor,org)
            }.code)
        Mockito.verifyNoInteractions(office,jdbc,drafts,verifier,stock,authority,
            reservations,commands,audit)
    }

    @Test fun noRegisteredCompanyTeamSourceMeansNoPurchaseNeed() {
        assertEquals("OFFICE_STOCK_NEED_UNAVAILABLE",
            assertThrows<ProductApiException> {
                service(true,true).create(ctx,team,request())
            }.code)
        Mockito.verifyNoInteractions(jdbc,drafts,verifier,stock,authority,
            reservations,commands,audit)
    }

    @Test fun wrongIdempotencyOperationIsInvalidBeforeAnyCompanyLookup() {
        val req=request().copy(operationId=UUID.randomUUID())
        assertEquals("OFFICE_STOCK_NEED_INVALID",
            assertThrows<ProductApiException> {
                service(true,true).create(ctx,team,req)
            }.code)
        Mockito.verifyNoInteractions(office,jdbc,drafts,verifier,stock,authority,
            reservations,commands,audit)
    }
}

class OfficeStockSourceDisabledTest {
    private val jdbc=Mockito.mock(JdbcTemplate::class.java)
    private val warehouse=Mockito.mock(com.hiltech.server.warehouse.WarehousePersistencePort::class.java)
    private val approvals=Mockito.mock(com.hiltech.server.approval.ApprovalPersistencePort::class.java)
    private val clock=Clock.fixed(Instant.parse("2026-11-22T20:00:00Z"),ZoneOffset.UTC)

    @Test fun disabledSourceCannotLookUpTeamOrLeakCurrentAuthorities() {
        val source=OfficeStockSource(jdbc,warehouse,approvals,clock,false,"","")
        assertNull(source.load(UUID.randomUUID(),UUID.randomUUID()))
        assertEquals(emptyList<OfficeTeamOption>(),source.teams(UUID.randomUUID(),UUID.randomUUID()))
        Mockito.verifyNoInteractions(jdbc,warehouse,approvals)
    }
}

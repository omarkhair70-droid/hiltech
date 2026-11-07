package com.hiltech.server.warehouse

import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.time.Clock
import java.time.Instant
import java.util.UUID

class WarehouseCreateServiceTest {
    private val persistence = Mockito.mock(WarehousePersistencePort::class.java)
    private val authorization = Mockito.mock(WarehouseAuthorizationPort::class.java)
    private val idempotency = Mockito.mock(IdempotentCommandExecutor::class.java)
    private val projection = Mockito.mock(WarehouseAuthorizationProjectionBridge::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)
    private val service = WarehouseCreateService(
        persistence,
        authorization,
        idempotency,
        projection,
        audit,
        Clock.systemUTC(),
    )
    private val actor = UUID.randomUUID()
    private val organization = UUID.randomUUID()

    @Test
    fun createWarehouseFailsClosedBeforeAnyWriteWithoutManagerAuthority() {
        val error = assertThrows<ProductApiException> {
            service.create(command())
        }
        assertEquals("WAREHOUSE_MANAGEMENT_FORBIDDEN", error.code)
        assertEquals(403, error.status.value())
        Mockito.verifyNoInteractions(persistence, idempotency, projection, audit)
    }

    @Test
    fun invalidWarehouseCodeIsRejectedBeforeIdempotencyAndPersistence() {
        Mockito.`when`(authorization.canManageInventoryOrganization(actor, organization))
            .thenReturn(true)
        val error = assertThrows<ProductApiException> {
            service.create(command(code = "   "))
        }
        assertEquals("INVALID_WAREHOUSE_CODE", error.code)
        Mockito.verifyNoInteractions(persistence, idempotency, projection, audit)
    }

    private fun command(code: String = "WH-001") = CreateWarehouseCommand(
        operationId = UUID.randomUUID(),
        organizationId = organization,
        code = code,
        name = "Main Warehouse",
        facilityRef = null,
        actorUserId = actor,
        clientOccurredAt = Instant.parse("2026-11-07T12:00:00Z"),
        correlationId = "slice01-unit-test",
    )
}

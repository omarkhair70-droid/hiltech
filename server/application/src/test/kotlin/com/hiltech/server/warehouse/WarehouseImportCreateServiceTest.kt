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

class WarehouseImportCreateServiceTest {
    private val persistence = Mockito.mock(WarehousePersistencePort::class.java)
    private val authorization = Mockito.mock(WarehouseAuthorizationPort::class.java)
    private val idempotency = Mockito.mock(IdempotentCommandExecutor::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)
    private val service = WarehouseImportCreateService(
        persistence, authorization, idempotency, audit, Clock.systemUTC(),
    )
    private val actor = UUID.randomUUID()
    private val organization = UUID.randomUUID()

    @Test
    fun noImportAuthorityMeansNoPersistenceOrIdempotencyWrites() {
        val error = assertThrows<ProductApiException> {
            service.create(command())
        }
        assertEquals("INVENTORY_IMPORT_FORBIDDEN", error.code)
        Mockito.verifyNoInteractions(persistence, idempotency, audit)
    }

    @Test
    fun invalidSourceDigestIsRejectedBeforeAnyWrite() {
        Mockito.`when`(authorization.canManageImport(actor, organization)).thenReturn(true)
        val error = assertThrows<ProductApiException> {
            service.create(command(sha256 = "not-a-real-hash"))
        }
        assertEquals("INVALID_SOURCE_SHA256", error.code)
        Mockito.verifyNoInteractions(persistence, idempotency, audit)
    }

    @Test
    fun filenameCannotBeBlank() {
        Mockito.`when`(authorization.canManageImport(actor, organization)).thenReturn(true)
        val error = assertThrows<ProductApiException> {
            service.create(command(fileName = "   "))
        }
        assertEquals("INVALID_SOURCE_FILENAME", error.code)
        Mockito.verifyNoInteractions(persistence, idempotency, audit)
    }

    private fun command(
        sha256: String = "a".repeat(64),
        fileName: String = "inventory.xlsx",
    ) = CreateInventoryImportBatchCommand(
        operationId = UUID.randomUUID(),
        organizationId = organization,
        sourceFileName = fileName,
        sourceSha256 = sha256,
        sourceType = InventoryImportSourceType.LEGACY_EXCEL,
        cutoverDate = null,
        actorUserId = actor,
        clientOccurredAt = Instant.parse("2026-11-07T12:00:00Z"),
        correlationId = "slice01-import-create-unit",
    )
}

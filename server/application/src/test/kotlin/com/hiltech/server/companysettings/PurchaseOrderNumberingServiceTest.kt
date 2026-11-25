package com.hiltech.server.companysettings

import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecution
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class PurchaseOrderNumberingServiceTest {
    private val at = Instant.parse("2026-11-25T12:00:00Z")
    private val actor = UUID.randomUUID()
    private val organization = UUID.randomUUID()
    private val authority = Mockito.mock(CompanySettingsAuthorityPort::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)
    private val persistence = MemoryNumbering(organization)
    private val commands = RecordingCommands()
    private val service = PurchaseOrderNumberingService(authority, persistence, commands, audit, Clock.fixed(at, ZoneOffset.UTC))

    @Test fun ordinaryOperatingAccessDoesNotConferCompanySettingsAuthority() {
        assertEquals("COMPANY_SETTINGS_DENIED", assertThrows<ProductApiException> { service.configure(command()) }.code)
        assertEquals("COMPANY_SETTINGS_DENIED", assertThrows<ProductApiException> { service.current(actor, organization) }.code)
        assertEquals(0, persistence.reads)
        assertEquals(0, commands.calls)
    }

    @Test fun authorityIsRecheckedInsideTheCompanyLock() {
        Mockito.`when`(authority.canManageCompanySettings(actor, organization)).thenReturn(true, false)
        assertEquals("COMPANY_SETTINGS_DENIED", assertThrows<ProductApiException> { service.configure(command()) }.code)
        assertEquals(1, persistence.locks)
        assertTrue(persistence.rows.isEmpty())
        Mockito.verifyNoInteractions(audit)
    }

    @Test fun firstExplicitSetupCreatesARevisionWithoutAssigningLegalInvoiceNumbering() {
        allow()
        val result = service.configure(command())
        assertEquals("HT-PO", result.prefix)
        assertEquals(1L, result.version)
        assertTrue(result.current)
        val current = service.current(actor, organization)
        assertEquals("COMPANY", current.source)
        assertEquals("HT-PO-000001", current.exampleCode)
        assertEquals(1, current.history.size)
    }

    @Test fun existingDeploymentPrefixIsVisibleAndCanBeAdoptedWithoutChangingItsFormat() {
        allow()
        persistence.inherited = "OLD-PO"
        val snapshot = service.current(actor, organization)
        assertEquals("DEPLOYMENT", snapshot.source)
        assertEquals(0L, snapshot.version)
        assertTrue(snapshot.ready)
        val result = service.configure(command().copy(expectedSource = PurchaseOrderNumberingSource.DEPLOYMENT,
            expectedPrefix = "OLD-PO", prefix = "OLD-PO"))
        assertEquals("OLD-PO", result.prefix)
        assertEquals(1L, result.version)
    }

    @Test fun explicitRevisionAndFallbackSnapshotMustBothStillMatch() {
        allow()
        persistence.inherited = "OLD-PO"
        assertEquals("COMPANY_NUMBERING_CHANGED", assertThrows<ProductApiException> { service.configure(command()) }.code)
        assertTrue(persistence.rows.isEmpty())
        assertEquals("COMPANY_NUMBERING_CHANGED", assertThrows<ProductApiException> {
            service.configure(command().copy(expectedSource = PurchaseOrderNumberingSource.DEPLOYMENT, expectedPrefix = "OTHER"))
        }.code)
    }

    @Test fun invalidPrefixOrReasonCannotWriteASourceRevision() {
        allow()
        for (prefix in listOf("", "P", "PO--X", "PO-", "PO/2026", "A".repeat(25), "فاتورة")) {
            assertEquals("COMPANY_NUMBERING_INVALID", assertThrows<ProductApiException> { service.configure(command().copy(prefix = prefix)) }.code)
        }
        assertEquals("COMPANY_NUMBERING_INVALID", assertThrows<ProductApiException> { service.configure(command().copy(reason = " ")) }.code)
        assertTrue(persistence.rows.isEmpty())
    }

    @Test fun successfulRetryDoesNotCreateAnotherRevisionAndOlderReceiptReportsCurrentnessHonestly() {
        allow()
        val first = command()
        val issued = service.configure(first)
        assertTrue(service.configure(first).replayed)
        assertEquals(1, persistence.rows.size)
        val second = command().copy(expectedVersion = 1, expectedSource = PurchaseOrderNumberingSource.COMPANY,
            expectedPrefix = issued.prefix, prefix = "HT-PUR")
        service.configure(second)
        val replay = service.configure(first)
        assertTrue(replay.replayed)
        assertFalse(replay.current)
        assertEquals("HT-PO", replay.prefix)
        assertEquals(2, persistence.rows.size)
        assertEquals(2, Mockito.mockingDetails(audit).invocations.size)
    }

    @Test fun revokedSettingsAuthorityCannotReplayPreviouslySavedConfiguration() {
        allow()
        val input = command()
        service.configure(input)
        Mockito.`when`(authority.canManageCompanySettings(actor, organization)).thenReturn(false)
        assertEquals("COMPANY_SETTINGS_DENIED", assertThrows<ProductApiException> { service.configure(input) }.code)
        assertEquals(1, commands.calls)
    }

    @Test fun unchangedCompanyPrefixDoesNotManufactureHistory() {
        allow()
        service.configure(command())
        assertEquals("COMPANY_NUMBERING_UNCHANGED", assertThrows<ProductApiException> {
            service.configure(command().copy(expectedVersion = 1, expectedSource = PurchaseOrderNumberingSource.COMPANY, expectedPrefix = "HT-PO"))
        }.code)
        assertEquals(1, persistence.rows.size)
    }

    @Test fun generatedCodesPreserveTheFullSerialBeyondSixDigits() {
        val source = PurchaseOrderNumberingSnapshot(organization, PurchaseOrderNumberingSource.COMPANY, "HT-PO", 7, UUID.randomUUID(), at)
        assertEquals("HT-PO-000026", source.code(26))
        assertEquals("HT-PO-1234567", source.code(1234567))
    }

    private fun allow() { Mockito.`when`(authority.canManageCompanySettings(actor, organization)).thenReturn(true) }
    private fun command() = ConfigurePurchaseOrderNumberingCommand(UUID.randomUUID(), organization, 0,
        PurchaseOrderNumberingSource.NOT_CONFIGURED, null, "HT-PO", "Synthetic company configuration", actor, "synthetic-numbering")

    private class MemoryNumbering(private val organization: UUID) : PurchaseOrderNumberingPersistencePort {
        var inherited: String? = null
        val rows = mutableListOf<PurchaseOrderNumberingRevision>()
        var reads = 0
        var locks = 0
        override fun current(organizationId: UUID): PurchaseOrderNumberingSnapshot {
            reads += 1
            val last = rows.lastOrNull()
            return if (last == null) PurchaseOrderNumberingSnapshot(organization,
                if (inherited == null) PurchaseOrderNumberingSource.NOT_CONFIGURED else PurchaseOrderNumberingSource.DEPLOYMENT, inherited, 0)
            else PurchaseOrderNumberingSnapshot(organization, PurchaseOrderNumberingSource.COMPANY, last.prefix, last.version, last.id, last.changedAt)
        }
        override fun lockOrganization(organizationId: UUID) { locks += 1 }
        override fun revision(id: UUID) = rows.singleOrNull { it.id == id }
        override fun history(organizationId: UUID, limit: Int) = rows.reversed().take(limit)
        override fun append(revision: PurchaseOrderNumberingRevision) { rows += revision }
    }
    private class RecordingCommands : IdempotentCommandExecutor {
        var calls = 0
        private val receipts = mutableMapOf<UUID, Pair<IdempotentCommandSpec, IdempotentCommandOutcome>>()
        override fun execute(spec: IdempotentCommandSpec, action: () -> IdempotentCommandOutcome): IdempotentCommandExecution {
            calls += 1
            receipts[spec.operationId]?.let { require(it.first == spec); return IdempotentCommandExecution(it.second, true) }
            return action().let { receipts[spec.operationId] = spec to it; IdempotentCommandExecution(it, false) }
        }
    }
}

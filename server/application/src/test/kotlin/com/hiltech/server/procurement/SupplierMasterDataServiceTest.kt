package com.hiltech.server.procurement

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecution
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class SupplierMasterDataServiceTest {
    private val org = UUID.randomUUID()
    private val supplier = UUID.randomUUID()
    private val actor = UUID.randomUUID()
    private val operation = UUID.randomUUID()
    private val at = Instant.parse("2026-11-22T15:00:00Z")
    private val context = ProcurementDraftContext(operation, actor, "supplier-test")
    private val repo = Mockito.mock(SupplierSourcingPersistence::class.java)
    private val authority = Mockito.mock(SupplierMasterDataAuthorizationPort::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)
    private val commands = object : IdempotentCommandExecutor {
        override fun execute(spec: IdempotentCommandSpec, action: () -> IdempotentCommandOutcome) =
            IdempotentCommandExecution(action(), false)
    }

    private fun service(
        sourcingEnabled: Boolean = true,
        masterDataEnabled: Boolean = true,
    ) = SupplierMasterDataService(
        repo, authority, commands, audit, Clock.fixed(at, ZoneOffset.UTC),
        sourcingEnabled, masterDataEnabled,
    )

    private fun request(
        state: String = "ACTIVE",
        revision: Int = 1,
    ) = ChangeSupplierLinkRequest(
        operationId = operation,
        supplierOrganizationId = supplier,
        expectedRevisionNumber = revision,
        state = state,
        sourceReference = "REGISTERED-SUPPLIER-FILE-2026-001",
    )

    private fun allow() {
        Mockito.`when`(authority.canMaintainSupplierLinks(actor, org)).thenReturn(true)
        Mockito.`when`(repo.lockActiveOrganizationForSupplierLinks(org)).thenReturn(true)
        Mockito.`when`(repo.supplierTypeAndStatus(supplier))
            .thenReturn("SUPPLIER" to "ACTIVE")
    }

    @Test fun noAuthorityOrDisabledFeatureCanGrantSupplierRegistration() {
        assertEquals("SUPPLIER_MASTER_DATA_UNAVAILABLE", assertThrows<ProductApiException> {
            service(false, false).changeSupplierLink(context, org, request())
        }.code)
        assertEquals("SUPPLIER_MASTER_DATA_UNAVAILABLE", assertThrows<ProductApiException> {
            service(true, false).changeSupplierLink(context, org, request())
        }.code)
        assertEquals("SUPPLIER_MASTER_DATA_UNAVAILABLE", assertThrows<ProductApiException> {
            service(true, true).changeSupplierLink(context, org, request())
        }.code)
        Mockito.verifyNoInteractions(repo)
    }

    @Test fun authorizedSupplierActivationAppendsFirstSourceReferencedEvent() {
        allow()
        val receipt = service().changeSupplierLink(context, org, request())
        assertEquals(1, receipt.revisionNumber)
        assertEquals("ACTIVE", receipt.state)
        assertEquals(org, receipt.organizationId)
        assertEquals(supplier, receipt.supplierOrganizationId)
        assertFalse(receipt.replayed)
        val writes = Mockito.mockingDetails(repo).invocations
            .filter { it.method.name == "insertSupplierLinkEvent" }
            .map { it.arguments.single() as SupplierLinkEventRecord }
        assertEquals(1, writes.size)
        val event = writes.single()
        assertEquals(receipt.eventId, event.id)
        assertEquals("REGISTERED-SUPPLIER-FILE-2026-001", event.sourceReference)
        assertEquals(actor, event.actor)
        assertEquals(at, event.at)
        val events = Mockito.mockingDetails(audit).invocations
            .filter { it.method.name == "append" }
            .map { it.arguments.single() as AuditEventRecord }
        assertEquals(1, events.size)
        assertEquals("SUPPLIER_LINK_ACTIVE", events.single().reason)
    }

    @Test fun revocationCanRecordInactiveSupplierWithoutOverwritingEarlierLink() {
        allow()
        Mockito.`when`(repo.supplierTypeAndStatus(supplier))
            .thenReturn("SUPPLIER" to "INACTIVE")
        Mockito.`when`(repo.currentSupplierLink(org, supplier))
            .thenReturn(SupplierLinkRevision(1, "ACTIVE"))
        val receipt = service().changeSupplierLink(context, org, request("INACTIVE", 2))
        assertEquals(2, receipt.revisionNumber)
        assertEquals("INACTIVE", receipt.state)
        val writes = Mockito.mockingDetails(repo).invocations
            .filter { it.method.name == "insertSupplierLinkEvent" }
        assertEquals(1, writes.size)
        val event = writes.single().arguments.single() as SupplierLinkEventRecord
        assertEquals(2, event.revisionNumber)
        assertEquals("INACTIVE", event.state)
    }

    @Test fun duplicateActivationAndStaleRevisionCannotCreateEvents() {
        allow()
        Mockito.`when`(repo.currentSupplierLink(org, supplier))
            .thenReturn(SupplierLinkRevision(1, "ACTIVE"))
        assertEquals("SUPPLIER_LINK_REVISION_CONFLICT", assertThrows<ProductApiException> {
            service().changeSupplierLink(context, org, request("ACTIVE", 2))
        }.code)
        assertEquals("SUPPLIER_LINK_REVISION_CONFLICT", assertThrows<ProductApiException> {
            service().changeSupplierLink(context, org, request("INACTIVE", 5))
        }.code)
        Mockito.verify(repo, Mockito.never()).insertSupplierLinkEvent(
            Mockito.any(SupplierLinkEventRecord::class.java)
                ?: SupplierLinkEventRecord(
                    UUID.randomUUID(), operation, org, supplier, 1,
                    "ACTIVE", "fixture-source", actor, at, "supplier-test",
                ),
        )
    }

    @Test fun deactivationWithoutCurrentLinkAndActivationOfInactiveSupplierAreDenied() {
        allow()
        assertEquals("SUPPLIER_LINK_REVISION_CONFLICT", assertThrows<ProductApiException> {
            service().changeSupplierLink(context, org, request("INACTIVE", 1))
        }.code)
        Mockito.`when`(repo.supplierTypeAndStatus(supplier))
            .thenReturn("SUPPLIER" to "INACTIVE")
        assertEquals("SUPPLIER_MASTER_DATA_UNAVAILABLE", assertThrows<ProductApiException> {
            service().changeSupplierLink(context, org, request())
        }.code)
    }
}

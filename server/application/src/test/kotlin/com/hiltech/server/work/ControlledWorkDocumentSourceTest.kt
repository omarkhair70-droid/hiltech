package com.hiltech.server.work

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Instant
import java.util.UUID

class ControlledWorkDocumentSourceTest {
    private val org = UUID.randomUUID()
    private val project = UUID.randomUUID()
    private val site = UUID.randomUUID()
    private val workId = UUID.randomUUID()
    private val requirementId = UUID.randomUUID()
    private val documentId = UUID.randomUUID()
    private val revisionA = UUID.randomUUID()
    private val now = Instant.parse("2026-11-22T00:30:00Z")
    private val read = FakeRead()
    private val source = ControlledWorkDocumentSource(read)
    private val order = Mockito.mock(WorkOrderSnapshot::class.java)

    private class FakeRead : ControlledDocumentReadPort {
        var value: ControlledDocumentBindingSnapshot? = null
        override fun activeBinding(
            workOrderId: UUID,
            requirementId: UUID,
        ): ControlledDocumentBindingSnapshot? = value
    }

    private val requirement =
        ReadinessRequirementExecutionSnapshot(
            requirementId = requirementId,
            family = "DOCUMENT",
            key = "approved_drawing",
            typeCode = "DRAWING_REVISION",
            label = "Approved drawing",
            required = true,
            satisfactionState = "PENDING",
            evaluationReasonCode = null,
            sourceAsOf = null,
            evaluatedAt = null,
            waiverAllowed = false,
            waiverReasonRequired = false,
            waived = false,
            waiverRef = null,
            version = 3,
        )

    init {
        Mockito.`when`(order.workOrderId).thenReturn(workId)
        Mockito.`when`(order.organizationId).thenReturn(org)
        Mockito.`when`(order.projectId).thenReturn(project)
        Mockito.`when`(order.siteId).thenReturn(site)
    }

    private fun binding(
        currentRevisionId: UUID? = revisionA,
        currentRevisionCode: String? = "B",
        currentRevisionVersion: Long? = 7,
        boundRevisionState: String = "APPROVED",
        documentState: String = "ACTIVE",
        organizationId: UUID = org,
        projectId: UUID = project,
        siteId: UUID? = site,
    ) = ControlledDocumentBindingSnapshot(
        bindingId = UUID.randomUUID(),
        bindingVersion = 2,
        organizationId = organizationId,
        projectId = projectId,
        siteId = siteId,
        documentId = documentId,
        documentCode = "DRW-001",
        title = "Fiber route",
        documentState = documentState,
        boundRevisionId = revisionA,
        boundRevisionCode = "A",
        boundRevisionState = boundRevisionState,
        boundRevisionVersion = 4,
        currentRevisionId = currentRevisionId,
        currentRevisionCode = currentRevisionCode,
        currentRevisionVersion = currentRevisionVersion,
        contentSourceType = "EXTERNAL_REF",
        boundAt = now.minusSeconds(60),
    )

    @Test
    fun `missing binding blocks without pretending the source is absent`() {
        val fact = source.evaluate(order, requirement, now)
        assertFalse(fact.satisfied)
        assertEquals("DOCUMENT_REVISION_NOT_BOUND", fact.reason)
        assertEquals(requirementId, fact.sourceId)
    }

    @Test
    fun `exact current approved revision satisfies readiness`() {
        read.value = binding(currentRevisionCode = "A")
        val fact = source.evaluate(order, requirement, now)
        assertTrue(fact.satisfied)
        assertEquals("DOCUMENT_REVISION_CURRENT", fact.reason)
        assertEquals(revisionA, fact.sourceId)
        assertEquals(4, fact.sourceVersion)
    }

    @Test
    fun `new current revision makes pinned WorkOrder stale without implicit overwrite`() {
        val revisionB = UUID.randomUUID()
        read.value = binding(
            currentRevisionId = revisionB,
            currentRevisionCode = "B",
            currentRevisionVersion = 9,
        )
        val fact = source.evaluate(order, requirement, now)
        assertFalse(fact.satisfied)
        assertEquals("DOCUMENT_REVISION_STALE", fact.reason)
        assertEquals(revisionB, fact.sourceId)
        assertEquals(9, fact.sourceVersion)
    }

    @Test
    fun `cross project document binding fails closed`() {
        read.value = binding(projectId = UUID.randomUUID(), currentRevisionCode = "A")
        val fact = source.evaluate(order, requirement, now)
        assertFalse(fact.satisfied)
        assertEquals("DOCUMENT_SCOPE_INVALID", fact.reason)
    }

    @Test
    fun `withdrawn revision blocks readiness`() {
        read.value = binding(
            currentRevisionId = revisionA,
            currentRevisionCode = "A",
            boundRevisionState = "WITHDRAWN",
        )
        val fact = source.evaluate(order, requirement, now)
        assertFalse(fact.satisfied)
        assertEquals("DOCUMENT_REVISION_WITHDRAWN", fact.reason)
    }
}

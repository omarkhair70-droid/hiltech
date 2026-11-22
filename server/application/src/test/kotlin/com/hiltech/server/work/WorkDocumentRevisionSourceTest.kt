package com.hiltech.server.work

import com.hiltech.server.approval.ApprovalSubjectRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class WorkDocumentRevisionSourceTest {
    private val persistence =
        Mockito.mock(
            WorkDocumentRevisionPersistence::class.java,
        )

    @Test
    fun approvalSourceAcceptsOnlyLatestMatchingRevision() {
        val org = UUID.randomUUID()
        val work = UUID.randomUUID()
        val document = UUID.randomUUID()
        val revision = UUID.randomUUID()
        val record =
            WorkDocumentRevisionRecord(
                revisionId = revision,
                organizationId = org,
                workOrderId = work,
                documentId = document,
                revisionSequence = 2,
                revisionLabel = "B",
                evidenceId = UUID.randomUUID(),
                supersedesRevisionId = UUID.randomUUID(),
                createdByUserId = UUID.randomUUID(),
                createdAt = Instant.parse("2026-11-22T00:00:00Z"),
                version = 1,
            )
        Mockito.`when`(
            persistence.revision(revision),
        ).thenReturn(record)
        Mockito.`when`(
            persistence.isLatestRevision(
                revision,
                document,
            ),
        ).thenReturn(true)

        val source =
            WorkDocumentApprovalSubjectVersionSource(
                persistence,
                enabled = true,
            )
        assertTrue(
            source.isCurrent(
                ApprovalSubjectRef(
                    organizationId = org,
                    subjectType =
                        WORK_DOCUMENT_REVISION_SUBJECT,
                    subjectId = revision,
                    subjectVersion = 1,
                ),
            ),
        )

        Mockito.`when`(
            persistence.isLatestRevision(
                revision,
                document,
            ),
        ).thenReturn(false)
        assertFalse(
            source.isCurrent(
                ApprovalSubjectRef(
                    organizationId = org,
                    subjectType =
                        WORK_DOCUMENT_REVISION_SUBJECT,
                    subjectId = revision,
                    subjectVersion = 1,
                ),
            ),
        )
        assertFalse(
            source.isCurrent(
                ApprovalSubjectRef(
                    organizationId = org,
                    subjectType =
                        WORK_DOCUMENT_REVISION_SUBJECT,
                    subjectId = revision,
                    subjectVersion = 2,
                ),
            ),
        )
    }

    @Test
    fun readinessUsesApprovedCurrentRevisionFromBoundRequirement() {
        val at =
            Instant.parse(
                "2026-11-22T00:00:00Z",
            )
        val workId = UUID.randomUUID()
        val revision = UUID.randomUUID()
        val order =
            WorkOrderSnapshot(
                workOrderId = workId,
                organizationId = UUID.randomUUID(),
                workOrderCode = "WO-1",
                projectId = UUID.randomUUID(),
                siteId = UUID.randomUUID(),
                projectSiteId = UUID.randomUUID(),
                areaId = null,
                workPackageId = null,
                title = "Work",
                description = null,
                lifecycleState =
                    WorkOrderLifecycle.ASSIGNED,
                readinessState =
                    WorkReadiness.BLOCKED,
                plannedStart = null,
                plannedEnd = null,
                priorityCode = "NORMAL",
                countsTowardProjectProgress = true,
                progressWeight = BigDecimal.ONE,
                baselineVersion = 1,
                createdAt = at,
                updatedAt = at,
                version = 4,
                binding = null,
                instruction = null,
                checklist = emptyList(),
                requirements = emptyList(),
                tasks = emptyList(),
                dependencies = emptyList(),
            )
        val requirement =
            ReadinessRequirementExecutionSnapshot(
                requirementId = UUID.randomUUID(),
                family = "DOCUMENT",
                key = "drawing",
                typeCode = "DRAWING_REVISION",
                label = "Drawing",
                required = true,
                satisfactionState = "BLOCKED",
                evaluationReasonCode = null,
                sourceAsOf = null,
                evaluatedAt = null,
                waiverAllowed = false,
                waiverReasonRequired = false,
                waived = false,
                waiverRef = null,
                version = 1,
            )
        Mockito.`when`(
            persistence.requirement(
                workId,
                "drawing",
            ),
        ).thenReturn(
            WorkDocumentRequirementSource(
                requirementKey = "drawing",
                requirementType =
                    "DRAWING_REVISION",
                sourceTemplateId =
                    UUID.randomUUID(),
                sourceTemplateRevision = 3,
                approvalPolicyKey =
                    "DRAWING_RELEASE",
                configuredDocumentCode =
                    "DWG-001",
            ),
        )
        Mockito.`when`(
            persistence.currentApproved(
                workId,
                "drawing",
                "DWG-001",
            ),
        ).thenReturn(
            listOf(
                WorkDocumentCurrentFact(
                    documentId =
                        UUID.randomUUID(),
                    revisionId = revision,
                    documentCode =
                        "DWG-001",
                    title = "Route",
                    revisionLabel = "B",
                    revisionSequence = 2,
                    evidenceId =
                        UUID.randomUUID(),
                    approvedAt = at,
                ),
            ),
        )

        val source =
            JdbcWorkDocumentReadinessSource(
                persistence,
                enabled = true,
            )
        val fact =
            source.evaluate(
                order,
                requirement,
                at,
            )!!

        assertTrue(fact.satisfied)
        assertEquals(
            "APPROVED_DOCUMENT_REVISION_CURRENT",
            fact.reason,
        )
        assertEquals(
            revision,
            fact.sourceId,
        )
        assertEquals(
            2L,
            fact.sourceVersion,
        )
    }

    @Test
    fun disabledSourceRemainsFailClosedAndDoesNotQueryPersistence() {
        val source =
            JdbcWorkDocumentReadinessSource(
                persistence,
                enabled = false,
            )
        val order =
            Mockito.mock(
                WorkOrderSnapshot::class.java,
            )
        val requirement =
            Mockito.mock(
                ReadinessRequirementExecutionSnapshot::class.java,
            )
        assertEquals(
            null,
            source.evaluate(
                order,
                requirement,
                Instant.EPOCH,
            ),
        )
        Mockito.verifyNoInteractions(
            persistence,
        )
    }
}

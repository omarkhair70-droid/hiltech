package com.hiltech.server.work

import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecution
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class FieldEngineeringTestServiceTest {
    private val actor = UUID.randomUUID()
    private val device = UUID.randomUUID()
    private val org = UUID.randomUUID()
    private val project = UUID.randomUUID()
    private val site = UUID.randomUUID()
    private val projectSite = UUID.randomUUID()
    private val workId = UUID.randomUUID()
    private val assignmentId = UUID.randomUUID()
    private val evidencePolicyId = UUID.randomUUID()
    private val templateId = UUID.randomUUID()
    private val now = Instant.parse("2026-11-22T00:00:00Z")

    private val work = Mockito.mock(WorkPersistencePort::class.java)
    private val readiness = Mockito.mock(ReadinessAssignmentPersistencePort::class.java)
    private val sourceAuthority = Mockito.mock(WorkAssignmentSourceAuthorityPort::class.java)
    private val authorization = Mockito.mock(WorkAssignmentAuthorizationPort::class.java)
    private val instruments = FakeInstrumentSource()
    private val persistence = FakePersistence()
    private val audit = AuditEventWriter { }
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private class ImmediateCommands : IdempotentCommandExecutor {
        override fun execute(
            spec: IdempotentCommandSpec,
            action: () -> IdempotentCommandOutcome,
        ): IdempotentCommandExecution =
            IdempotentCommandExecution(action(), replayed = false)
    }

    private class FakeInstrumentSource : FieldEngineeringInstrumentSourcePort {
        val states = mutableMapOf<UUID, FieldEngineeringInstrumentState>()
        override fun state(assetId: UUID, at: Instant): FieldEngineeringInstrumentState? = states[assetId]
    }

    private inner class FakePersistence : FieldEngineeringTestPersistencePort {
        var sourceValue: FieldEngineeringTestSource? = null
        val results = linkedMapOf<UUID, FieldEngineeringStoredResult>()
        var evidenceReady = true

        override fun source(workOrderId: UUID, requirementKey: String): FieldEngineeringTestSource? =
            sourceValue?.takeIf { it.workOrderId == workOrderId && it.requirementKey == requirementKey }

        override fun readyEvidence(
            evidenceId: UUID,
            source: FieldEngineeringTestSource,
            actor: UUID,
        ): Boolean = evidenceReady

        override fun result(operationId: UUID): FieldEngineeringStoredResult? = results[operationId]

        override fun hasSuccessor(resultId: UUID): Boolean =
            results.values.any { it.supersedesResultId == resultId }

        override fun insert(
            request: FieldEngineeringTestCaptureRequest,
            source: FieldEngineeringTestSource,
            assignment: WorkAssignmentRecord,
            actor: UUID,
            deviceInstallationId: UUID,
            valuesJson: String,
            status: EngineeringEvaluationStatus,
            evaluationJson: String,
            deviceStatus: String?,
            at: Instant,
            correlationId: String,
        ) {
            check(results[request.operationId] == null)
            results[request.operationId] = FieldEngineeringStoredResult(
                operationId = request.operationId,
                organizationId = source.organizationId,
                workOrderId = source.workOrderId,
                requirementKey = source.requirementKey,
                schemaTemplateId = source.templateId,
                schemaTemplateRevision = source.templateRevision,
                testSubjectRef = request.testSubjectRef.trim(),
                evaluatedStatus = status,
                deviceReportedStatus = deviceStatus,
                supersedesResultId = request.supersedesResultId,
            )
        }
    }

    private fun service(enabled: Boolean = true) = FieldEngineeringTestService(
        persistence, work, readiness, sourceAuthority, authorization,
        instruments, ImmediateCommands(), audit, clock, enabled,
    )

    private fun source(schema: String): FieldEngineeringTestSource =
        FieldEngineeringTestSource(
            organizationId = org,
            workOrderId = workId,
            workOrderVersion = 7,
            lifecycleState = WorkOrderLifecycle.IN_PROGRESS,
            evidencePolicyId = evidencePolicyId,
            evidencePolicyRevision = 3,
            requirementKey = "fiber_acceptance",
            evidenceTypeCode = "ENGINEERING_TEST",
            reviewerRelationshipCode = "FIELD_ENGINEER",
            templateId = templateId,
            templateCode = "FIBER_ACCEPTANCE",
            templateName = "Fiber acceptance",
            templateRevision = 2,
            schemaVersion = 1,
            schemaJson = schema,
        )

    private fun order(version: Long = 7): WorkOrderSnapshot =
        WorkOrderSnapshot(
            workOrderId = workId,
            organizationId = org,
            workOrderCode = "WO-001",
            projectId = project,
            siteId = site,
            projectSiteId = projectSite,
            areaId = null,
            workPackageId = null,
            title = "Fiber link",
            description = null,
            lifecycleState = WorkOrderLifecycle.IN_PROGRESS,
            readinessState = WorkReadiness.READY,
            plannedStart = null,
            plannedEnd = null,
            priorityCode = "NORMAL",
            countsTowardProjectProgress = true,
            progressWeight = BigDecimal.ONE,
            baselineVersion = 1,
            createdAt = now,
            updatedAt = now,
            version = version,
            binding = null,
            instruction = null,
            checklist = emptyList(),
            requirements = emptyList(),
            tasks = emptyList(),
            dependencies = emptyList(),
        )

    private fun assignment(version: Long = 4) =
        WorkAssignmentRecord(
            assignmentId, workId, AssignmentTargetType.USER, actor, "Technician",
            lead = false, state = "ACTIVE", assignedAt = now, assignedBy = actor,
            validFrom = now.minusSeconds(3600), validUntil = null,
            supersedesAssignmentId = null, reason = null, version = version,
        )

    private fun allowCurrent() {
        Mockito.`when`(sourceAuthority.isCurrentAssignedUser(actor, workId, now)).thenReturn(true)
        Mockito.`when`(authorization.canUseAssignedWork(actor, workId, "can_submit_completion")).thenReturn(true)
        Mockito.`when`(work.workOrder(workId)).thenReturn(order())
        Mockito.`when`(readiness.activeAssignment(workId)).thenReturn(assignment())
    }

    private fun request(
        operationId: UUID = UUID.randomUUID(),
        values: Map<String, String>,
        instrumentAssetId: UUID? = null,
        rawEvidenceId: UUID? = null,
        supersedes: UUID? = null,
    ) = FieldEngineeringTestCaptureRequest(
        operationId = operationId,
        assignmentId = assignmentId,
        assignmentVersion = 4,
        workOrderVersion = 7,
        evidencePolicyId = evidencePolicyId,
        evidencePolicyRevision = 3,
        requirementKey = "fiber_acceptance",
        schemaTemplateId = templateId,
        schemaTemplateRevision = 2,
        schemaVersion = 1,
        testSubjectRef = "LINK-A01",
        values = values,
        instrumentAssetId = instrumentAssetId,
        rawEvidenceId = rawEvidenceId,
        deviceReportedStatus = null,
        capturedAt = now.minusSeconds(30),
        clientOccurredAt = now.minusSeconds(25),
        supersedesResultId = supersedes,
    )

    @Test
    fun `feature is unavailable when rollout is off`() {
        persistence.sourceValue = source(
            """{"metrics":[{"key":"loss","valueType":"DECIMAL"}]}""",
        )
        val error = assertThrows<ProductApiException> {
            service(false).capture(actor, device, workId, request(values = mapOf("loss" to "0.2")), "test")
        }
        assertEquals("ENGINEERING_TEST_UNAVAILABLE", error.code)
        assertEquals(0, persistence.results.size)
    }

    @Test
    fun `numeric limits are configuration driven and produce pass or fail`() {
        persistence.sourceValue = source(
            """{
              "label":"Configured loss test",
              "subjectLabel":"Link ID",
              "metrics":[
                {"key":"loss","label":"Loss","valueType":"DECIMAL","unitCode":"dB","precision":3,"min":"0","max":"0.35"}
              ]
            }""".trimIndent(),
        )
        allowCurrent()

        val pass = service().capture(
            actor, device, workId, request(values = mapOf("loss" to "0.20")), "pass",
        )
        assertEquals(EngineeringEvaluationStatus.PASS, pass.evaluatedStatus)

        persistence.results.clear()
        val fail = service().capture(
            actor, device, workId, request(values = mapOf("loss" to "0.50")), "fail",
        )
        assertEquals(EngineeringEvaluationStatus.FAIL, fail.evaluatedStatus)
    }

    @Test
    fun `schema without thresholds preserves unknown instead of inventing pass fail`() {
        persistence.sourceValue = source(
            """{"metrics":[{"key":"length","label":"Length","valueType":"DECIMAL","unitCode":"m","precision":2}]}""",
        )
        allowCurrent()
        val result = service().capture(
            actor, device, workId, request(values = mapOf("length" to "150.25")), "unknown",
        )
        assertEquals(EngineeringEvaluationStatus.UNKNOWN, result.evaluatedStatus)
    }

    @Test
    fun `enum options and accepted values are independent configuration`() {
        persistence.sourceValue = source(
            """{
              "metrics":[{
                "key":"device_result",
                "valueType":"ENUM",
                "options":["PASS","FAIL","N-A"],
                "acceptedValues":["PASS","N-A"]
              }]
            }""".trimIndent(),
        )
        allowCurrent()

        val accepted = service().capture(
            actor, device, workId,
            request(values = mapOf("device_result" to "N-A")),
            "enum-accepted",
        )
        assertEquals(EngineeringEvaluationStatus.PASS, accepted.evaluatedStatus)

        persistence.results.clear()
        val rejected = service().capture(
            actor, device, workId,
            request(values = mapOf("device_result" to "FAIL")),
            "enum-fail",
        )
        assertEquals(EngineeringEvaluationStatus.FAIL, rejected.evaluatedStatus)
    }

    @Test
    fun `calibration policy is configuration driven and blocks expired instrument`() {
        persistence.sourceValue = source(
            """{
              "instrumentAssetRequired":true,
              "calibrationRequired":true,
              "metrics":[{"key":"loss","valueType":"DECIMAL"}]
            }""".trimIndent(),
        )
        allowCurrent()
        val instrument = UUID.randomUUID()
        instruments.states[instrument] = FieldEngineeringInstrumentState(org, "EXPIRED")
        val error = assertThrows<ProductApiException> {
            service().capture(
                actor, device, workId,
                request(values = mapOf("loss" to "0.2"), instrumentAssetId = instrument),
                "expired",
            )
        }
        assertEquals("ENGINEERING_TEST_CALIBRATION_INVALID", error.code)
    }

    @Test
    fun `retest is append only supersession not overwrite`() {
        persistence.sourceValue = source(
            """{"metrics":[{"key":"loss","valueType":"DECIMAL","max":"0.35"}]}""",
        )
        allowCurrent()
        val first = service().capture(
            actor, device, workId, request(values = mapOf("loss" to "0.5")), "first",
        )
        assertEquals(EngineeringEvaluationStatus.FAIL, first.evaluatedStatus)

        val second = service().capture(
            actor, device, workId,
            request(values = mapOf("loss" to "0.2"), supersedes = first.resultId),
            "second",
        )
        assertEquals(EngineeringEvaluationStatus.PASS, second.evaluatedStatus)
        assertEquals(first.resultId, second.supersedesResultId)
        assertEquals(2, persistence.results.size)
        assertNull(persistence.results[first.resultId]!!.supersedesResultId)
    }
}

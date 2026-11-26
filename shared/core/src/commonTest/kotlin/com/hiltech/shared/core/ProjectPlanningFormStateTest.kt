package com.hiltech.shared.core

import com.hiltech.shared.core.network.HiltechApiException
import com.hiltech.shared.core.projects.PlanningMutationResponseDto
import com.hiltech.shared.core.projects.ProjectPlanDto
import com.hiltech.shared.core.projects.ProjectPlanProjectDto
import com.hiltech.shared.core.projects.AreaDto
import com.hiltech.shared.core.projects.MilestoneDto
import com.hiltech.shared.core.projects.ProjectPlanSiteDto
import com.hiltech.shared.core.projects.WorkPackageDto
import com.hiltech.shared.core.projects.PlanDependencyDto
import com.hiltech.shared.core.projects.PlanNodeDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProjectPlanningFormStateTest {
    private val baseline = PlanningFormBaseline(7, 1, 3)
    private val operation = PlanningFormOperation("22222222-2222-4222-8222-222222222222", "2026-11-25T10:00:00Z")
    private val action = PlanningUiAction.UpdateArea(
        projectId = "11111111-1111-4111-8111-111111111111",
        areaId = "33333333-3333-4333-8333-333333333333",
        parentAreaId = null, typeCode = "ZONE", code = "Z-01", name = "Edited draft",
        sequence = 0, restrictedAccess = false, baseProjectVersion = 7, baseObjectVersion = 3,
        expectedBaselineVersion = 1,
    )

    @Test
    fun optionalAbsenceAndIntentionalZeroRemainDifferent() {
        assertEquals(PlanningIntegerInput(null, null), parsePlanningSequence("  "))
        for (zero in listOf("0", "٠", "۰", "000", " ٠ ")) {
            assertEquals(0L, parsePlanningSequence(zero).value)
            assertTrue(parsePlanningSequence(zero).valid)
            assertEquals(0L, parsePlanningLag(zero).value)
        }
        assertEquals(PlanningIntegerProblem.REQUIRED, parsePlanningLag("").problem)
        assertEquals(PlanningIntegerProblem.REQUIRED, parsePlanningLag("  ").problem)
    }

    @Test
    fun latinAndBothArabicDigitSetsHaveTheSameMeaning() {
        for (text in listOf("1234567890", "١٢٣٤٥٦٧٨٩٠", "۱۲۳۴۵۶۷۸۹۰", "1٢۳4٥۶7٨۹0")) {
            assertEquals(1_234_567_890L, parsePlanningSequence(text).value, text)
            assertEquals(1_234_567_890L, parsePlanningLag(text).value, text)
        }
    }

    @Test
    fun invalidNumbersNeverFallBackToNullOrZeroAsValidInput() {
        for (text in listOf("abc", "1.5", "١٫٥", "1,000", "١٬٠٠٠", "1 2", "-1", "−١", "+2", "12x", "1e3")) {
            assertEquals(PlanningIntegerProblem.WHOLE_NUMBER_REQUIRED, parsePlanningSequence(text).problem, text)
            assertEquals(PlanningIntegerProblem.WHOLE_NUMBER_REQUIRED, parsePlanningLag(text).problem, text)
            assertFalse(parsePlanningSequence(text).valid, text)
            assertFalse(parsePlanningLag(text).valid, text)
        }
    }

    @Test
    fun integerBoundsMatchThePlanningWireTypesWithoutOverflow() {
        assertEquals(Int.MAX_VALUE.toLong(), parsePlanningSequence("٢١٤٧٤٨٣٦٤٧").value)
        assertEquals(PlanningIntegerProblem.OUT_OF_RANGE, parsePlanningSequence("2147483648").problem)
        assertEquals(Long.MAX_VALUE, parsePlanningLag("۹۲۲۳۳۷۲۰۳۶۸۵۴۷۷۵۸۰۷").value)
        assertEquals(PlanningIntegerProblem.OUT_OF_RANGE, parsePlanningLag("9223372036854775808").problem)
        assertEquals(PlanningIntegerProblem.OUT_OF_RANGE, parsePlanningLag("9".repeat(80)).problem)
        assertEquals(1L, parsePlanningLag("0".repeat(80) + "1").value)
    }

    @Test
    fun parentChoicesExcludeOtherSitesSelfAndEveryDescendant() {
        fun area(id: String, parent: String? = null, site: String = "site-one") =
            AreaDto(id, site, parent, "ZONE", id, id, restrictedAccess = false, version = 1)
        val areas = listOf(area("root"), area("edited", "root"), area("child", "edited"),
            area("grandchild", "child"), area("sibling", "root"), area("other", site = "site-two"))
        assertEquals(listOf("root", "sibling"), planningParentAreas(areas, "site-one", "edited").map { it.areaId })
        assertEquals(listOf("other"), planningParentAreas(areas, "site-two", null).map { it.areaId })
        assertTrue(planningParentAreas(areas, null, null).isEmpty())
    }

    @Test
    fun anExistingParentCycleDoesNotLoopOrOfferAnUnsafeParent() {
        val areas = listOf(
            AreaDto("one", "site", "two", "ZONE", "ONE", "One", restrictedAccess = false, version = 1),
            AreaDto("two", "site", "one", "ZONE", "TWO", "Two", restrictedAccess = false, version = 1),
        )
        assertTrue(planningParentAreas(areas, "site", "one").isEmpty())
    }

    @Test
    fun aProjectSiteChoiceDerivesItsPhysicalSiteAndCannotUseAClosedRelation() {
        val plan = success().plan.copy(projectSites = listOf(
            ProjectPlanSiteDto("relation-one", "site-one", "SITE-1", "First site", "ACTIVE"),
            ProjectPlanSiteDto("relation-two", "site-two", "SITE-2", "Closed site", "CLOSED"),
        ))
        assertEquals("site-one", planningPackageSite(plan, "relation-one")?.siteId)
        assertNull(planningPackageSite(plan, "relation-two"))
        assertNull(planningPackageSite(plan, "site-one"))
        assertNull(planningPackageSite(plan, ""))
    }

    @Test
    fun dependencyChoicesKeepTypedIdentityAndExcludeCancelledOrOlderBaselineNodes() {
        fun milestone(id: String, state: String = "PLANNED", baseline: Int = 1) =
            MilestoneDto(id, id, "Milestone $id", state, clientVisible = false, baselineVersion = baseline, version = 1)
        val plan = success().plan.copy(
            milestones = listOf(milestone("same-id"), milestone("cancelled", "CANCELLED"), milestone("old", baseline = 2)),
            workPackages = listOf(
                WorkPackageDto("same-id", code = "WP-01", name = "A work package", state = "PLANNED", baselineVersion = 1, version = 1),
                WorkPackageDto("cancelled", code = "WP-02", name = "Cancelled", state = "CANCELLED", baselineVersion = 1, version = 1),
            ),
        )
        val choices = planningDependencyNodes(plan)
        assertEquals(listOf("MILESTONE:same-id", "WORK_PACKAGE:same-id"), choices.map { it.key })
        assertEquals(listOf("Milestone same-id", "A work package"), choices.map { it.name })
    }

    @Test
    fun dispatchDoesNotCloseOrClearTheDraftAndOnlyItsSuccessCanCloseIt() {
        val session = draft()
        val request = assertNotNull(session.begin(action) { operation })
        assertTrue(session.open)
        assertTrue(session.locked)
        assertEquals("Edited draft", session.value("name"))
        assertFalse(session.receive("unrelated-operation", PlanningFormResult.Succeeded(success())))
        assertTrue(session.open)
        assertTrue(session.receive(request.operation.operationId, PlanningFormResult.Succeeded(success())))
        assertEquals(PlanningFormPhase.SUCCEEDED, session.phase)
        assertFalse(session.open)
        assertFalse(session.receive(request.operation.operationId, denied(403)))
    }

    @Test
    fun permissionAndValidationRejectionsRetainAnEditableDraft() {
        for (status in listOf(403, 422)) {
            val session = draft()
            session.begin(action) { operation }
            session.receive(operation.operationId, denied(status))
            assertTrue(session.open)
            assertFalse(session.locked)
            assertEquals("Edited draft", session.value("name"))
            session.update("name", "Corrected draft")
            assertEquals("Corrected draft", session.value("name"))
            assertNotNull(session.begin(action.copy(name = "Corrected draft")) {
                operation.copy(operationId = "44444444-4444-4444-8444-444444444444")
            })
        }
    }

    @Test
    fun conflictRetainsTheOriginalVersionsUntilTheUserReviewsAFreshSource() {
        val session = draft()
        session.begin(action) { operation }
        session.receive(operation.operationId, denied(409))
        assertEquals("Edited draft", session.value("name"))
        assertEquals(baseline, session.baseline)
        assertTrue(session.requiresSourceReview)
        assertNull(session.begin(action) { error("Must not allocate a new operation before source review") })
        assertFalse(session.adoptReviewedSource(baseline))
        val fresh = PlanningFormBaseline(8, 1, 4)
        assertTrue(session.adoptReviewedSource(fresh, mapOf("restrictedAccess" to "true")))
        assertEquals(fresh, session.baseline)
        assertEquals("Edited draft", session.value("name"))
        assertEquals("true", session.value("restrictedAccess"))
        assertNotNull(session.begin(action.copy(baseProjectVersion = 8, baseObjectVersion = 4)) {
            operation.copy(operationId = "44444444-4444-4444-8444-444444444444")
        })
    }

    @Test
    fun anInputConflictCanBeCorrectedWithoutWaitingForAnUnchangedServerVersion() {
        val session = draft()
        session.begin(action) { operation }
        session.receive(operation.operationId, PlanningFormResult.Rejected("AREA_CYCLE", 409, "corr"))
        assertFalse(session.requiresSourceReview)
        session.update("parent", "another-parent")
        assertNotNull(session.begin(action.copy(parentAreaId = "another-parent")) {
            operation.copy(operationId = "44444444-4444-4444-8444-444444444444")
        })
        assertEquals(baseline, session.baseline)
    }

    @Test
    fun timeoutLocksThePayloadAndRetriesTheExactOriginalRequest() {
        val session = draft()
        var allocations = 0
        val request = assertNotNull(session.begin(action) { allocations++; operation })
        session.receive(operation.operationId, PlanningFormResult.Unknown("NETWORK_UNAVAILABLE", "corr"))
        session.update("name", "Must not replace submitted payload")
        assertEquals("Edited draft", session.value("name"))
        assertFalse(session.discard())
        assertNull(session.begin(action.copy(name = "Different")) { error("Must retain the original operation") })
        val retry = assertNotNull(session.retry())
        assertSame(request, retry)
        assertSame(action, retry.action)
        assertSame(operation, retry.operation)
        assertEquals(1, allocations)
        assertNull(session.retry())
        assertTrue(session.receive(operation.operationId, PlanningFormResult.Succeeded(success(replayed = true))))
        assertFalse(session.open)
    }

    @Test
    fun denialOfAnUncertainRetryDoesNotProveThatTheOriginalWriteFailed() {
        for (status in listOf(403, 409, 422)) {
            val session = draft()
            session.begin(action) { operation }
            session.receive(operation.operationId, PlanningFormResult.Unknown("NETWORK_UNAVAILABLE", "corr"))
            session.retry()
            session.receive(operation.operationId, denied(status))
            assertEquals(PlanningFormPhase.UNKNOWN, session.phase, status.toString())
            assertTrue(session.locked)
            assertEquals("Edited draft", session.value("name"))
            assertFalse(session.discard())
        }
    }

    @Test
    fun anExplicitTerminalOperationFailureCanResolveUncertainty() {
        val session = draft()
        session.begin(action) { operation }
        session.receive(operation.operationId, PlanningFormResult.Unknown("NETWORK_UNAVAILABLE", "corr"))
        session.retry()
        session.receive(operation.operationId, PlanningFormResult.Rejected("OPERATION_PREVIOUSLY_FAILED", 409, "corr"))
        assertEquals(PlanningFormPhase.REJECTED, session.phase)
        assertFalse(session.locked)
        assertTrue(session.open)
        assertEquals("Edited draft", session.value("name"))
    }

    @Test
    fun pendingAndUncertainTransportFailuresAreDistinctFromConfirmedRejections() {
        assertIs<PlanningFormResult.Rejected>(planningFormFailure(apiFailure(403, "PERMISSION_DENIED")))
        assertIs<PlanningFormResult.Rejected>(planningFormFailure(apiFailure(409, "VERSION_CONFLICT")))
        assertIs<PlanningFormResult.Rejected>(planningFormFailure(apiFailure(422, "REJECTED_VALIDATION")))
        assertIs<PlanningFormResult.Unknown>(planningFormFailure(apiFailure(409, "OPERATION_IN_PROGRESS")))
        assertIs<PlanningFormResult.Unknown>(planningFormFailure(apiFailure(409, "IDEMPOTENCY_KEY_REUSED")))
        assertIs<PlanningFormResult.Unknown>(planningFormFailure(apiFailure(503, "IDEMPOTENCY_STATE_UNAVAILABLE")))
        assertIs<PlanningFormResult.Unknown>(planningFormFailure(apiFailure(408, "HTTP_ERROR")))
        assertIs<PlanningFormResult.Unknown>(planningFormFailure(apiFailure(null, "NETWORK_UNAVAILABLE")))
        assertIs<PlanningFormResult.Unknown>(planningFormFailure(IllegalStateException("Response could not be decoded")))
    }

    @Test
    fun aSuccessForAnotherProjectOrTargetCannotCloseThisEditor() {
        val responses = listOf(
            success().let { it.copy(plan = it.plan.copy(project = it.plan.project.copy(projectId = "other-project"))) },
            success().copy(targetId = "other-area"),
            success().copy(targetType = "MILESTONE"),
        )
        for (response in responses) {
            val session = draft()
            session.begin(action) { operation }
            session.receive(operation.operationId, PlanningFormResult.Succeeded(response))
            assertTrue(session.open)
            assertEquals(PlanningFormPhase.UNKNOWN, session.phase)
            assertEquals("Edited draft", session.value("name"))
        }
    }

    @Test
    fun sourceRefreshAndReturningToTheSameFormDoNotReplaceDraftValues() {
        val forms = PlanningFormRegistry("actor/org/project")
        forms.open("area", baseline, mapOf("name" to "Original"))
        val session = assertNotNull(forms.current("area"))
        session.update("name", "Edited draft")
        forms.open("area", PlanningFormBaseline(99, 2, 40), mapOf("name" to "Updated source"))
        assertSame(session, forms.current("area"))
        assertEquals("Edited draft", session.value("name"))
        assertEquals(baseline, session.baseline)
    }

    @Test
    fun scopeDisposalIgnoresLateCallbacksAndDoesNotMutateTheNewEditors() {
        val forms = PlanningFormRegistry("actor/org/project")
        forms.open("area", baseline, mapOf("name" to "Original"))
        val session = assertNotNull(forms.current("area"))
        var completion: ((PlanningFormResult) -> Unit)? = null
        val host = PlanningFormHost("actor/org/project", { operation }) { _, callback -> completion = callback }
        forms.submit(session, action, host)
        assertTrue(forms.hasUnresolvedRequest)
        forms.invalidate()
        completion?.invoke(PlanningFormResult.Succeeded(success()))
        assertFalse(session.open)
        assertEquals(PlanningFormPhase.SUBMITTING, session.phase)
        assertNull(forms.current("area"))
        assertFalse(forms.hasUnresolvedRequest)
    }

    @Test
    fun anotherActorAndADuplicateSubmitCannotSendACommand() {
        val forms = PlanningFormRegistry("actor/org/project")
        forms.open("area", baseline, mapOf("name" to "Original"))
        val session = assertNotNull(forms.current("area"))
        var sends = 0
        val host = PlanningFormHost("actor/org/project", { operation }) { _, _ -> sends++ }
        forms.submit(session, action, host.copy(scopeKey = "different-actor/org/project"))
        assertEquals(0, sends)
        forms.submit(session, action, host)
        forms.submit(session, action, host)
        assertEquals(1, sends)
    }

    @Test
    fun aRefreshedProjectionCannotHideRecoveryForAnUncertainRemovedDependency() {
        val forms = PlanningFormRegistry("actor/org/project")
        val dependencyId = "dependency"
        val command = PlanningUiAction.RemoveDependency(action.projectId, dependencyId, 7, 3, 1)
        val before = success().plan.copy(dependencies = listOf(PlanDependencyDto(
            dependencyId, PlanNodeDto("MILESTONE", "first"), PlanNodeDto("WORK_PACKAGE", "second"),
            "FINISH_TO_START", 0, 3,
        )))
        forms.open("remove:$dependencyId", baseline, emptyMap())
        val session = assertNotNull(forms.current("remove:$dependencyId"))
        val original = assertNotNull(session.begin(command) { operation })
        session.receive(operation.operationId, PlanningFormResult.Unknown("NETWORK_UNAVAILABLE", "corr"))
        assertTrue(forms.detachedSessions(before).isEmpty())
        val after = before.copy(dependencies = emptyList())
        assertSame(session, forms.detachedSessions(after).single())
        assertTrue(forms.hasUnresolvedRequest)
        assertSame(original, session.retry())
    }

    private fun draft() = PlanningFormSession(baseline, mapOf("name" to "Original")).apply {
        update("name", "Edited draft")
    }

    private fun denied(status: Int) = PlanningFormResult.Rejected(
        if (status == 403) "PERMISSION_DENIED" else if (status == 409) "VERSION_CONFLICT" else "REJECTED_VALIDATION",
        status, "corr",
    )

    private fun apiFailure(status: Int?, code: String) = HiltechApiException(
        code, "corr", status, retryable = status == null || status >= 500, envelope = null, message = "Synthetic test failure",
    )

    private fun success(replayed: Boolean = false) = PlanningMutationResponseDto(
        plan = ProjectPlanDto(
            project = ProjectPlanProjectDto(action.projectId, "PRJ-001", "Test project", "PLANNING", 8, 1),
            projectSites = emptyList(), areas = emptyList(), milestones = emptyList(), workPackages = emptyList(),
            dependencies = emptyList(), validationValid = true, validationReasonCodes = emptyList(),
            canEditPlan = true, readyGateReady = true, readyGateReasonCodes = emptyList(), correlationId = "corr",
        ),
        targetType = "AREA", targetId = action.areaId, replayed = replayed, correlationId = "corr",
    )
}

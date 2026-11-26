package com.hiltech.shared.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hiltech.shared.core.network.HiltechApiException
import com.hiltech.shared.core.projects.PlanningMutationResponseDto
import com.hiltech.shared.core.projects.AreaDto
import com.hiltech.shared.core.projects.ProjectPlanDto
import com.hiltech.shared.core.projects.ProjectPlanSiteDto

/** Both values belong to the first attempt and must be reused without change on an uncertain retry. */
data class PlanningFormOperation(val operationId: String, val clientOccurredAt: String)

data class PlanningFormRequest(val action: PlanningUiAction, val operation: PlanningFormOperation)

sealed interface PlanningFormResult {
    data class Succeeded(val response: PlanningMutationResponseDto) : PlanningFormResult
    data class Rejected(val code: String?, val httpStatus: Int?, val correlationId: String?) : PlanningFormResult
    data class Unknown(val code: String?, val correlationId: String?) : PlanningFormResult
}

/** scopeKey identifies an actor/organization/route session, never a bearer token. */
data class PlanningFormHost(
    val scopeKey: String,
    val newOperation: () -> PlanningFormOperation,
    val submit: (PlanningFormRequest, (PlanningFormResult) -> Unit) -> Unit,
)

fun planningFormResponse(request: PlanningFormRequest, response: PlanningMutationResponseDto): PlanningFormResult =
    if (request.matches(response)) PlanningFormResult.Succeeded(response)
    else PlanningFormResult.Unknown("UNEXPECTED_PLANNING_RESPONSE", response.correlationId)

fun planningFormFailure(failure: Throwable): PlanningFormResult {
    val api = failure as? HiltechApiException
    return when {
        api == null -> PlanningFormResult.Unknown(null, null)
        api.code in setOf("OPERATION_IN_PROGRESS", "IDEMPOTENCY_STATE_UNAVAILABLE", "IDEMPOTENCY_KEY_REUSED") ->
            PlanningFormResult.Unknown(api.code, api.correlationId)
        api.httpStatus?.let { it in 400..499 && it != 408 } == true ->
            PlanningFormResult.Rejected(api.code, api.httpStatus, api.correlationId)
        api.code == "REAUTH_REQUIRED" -> PlanningFormResult.Rejected(api.code, api.httpStatus, api.correlationId)
        else -> PlanningFormResult.Unknown(api.code, api.correlationId)
    }
}

internal enum class PlanningIntegerProblem { REQUIRED, WHOLE_NUMBER_REQUIRED, OUT_OF_RANGE }

internal data class PlanningIntegerInput(val value: Long?, val problem: PlanningIntegerProblem?) {
    val valid: Boolean get() = problem == null
}

/** Planning's server contract permits non-negative integers; blank and an intentional zero differ. */
internal fun parsePlanningInteger(
    input: String,
    optional: Boolean,
    maximum: Long,
): PlanningIntegerInput {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return PlanningIntegerInput(null, if (optional) null else PlanningIntegerProblem.REQUIRED)
    val digits = StringBuilder(trimmed.length)
    for (character in trimmed) {
        val digit = when (character) {
            in '0'..'9' -> character
            in '\u0660'..'\u0669' -> '0' + (character - '\u0660')
            in '\u06f0'..'\u06f9' -> '0' + (character - '\u06f0')
            else -> return PlanningIntegerInput(null, PlanningIntegerProblem.WHOLE_NUMBER_REQUIRED)
        }
        digits.append(digit)
    }
    val value = digits.toString().toLongOrNull()
    return if (value == null || value > maximum) {
        PlanningIntegerInput(null, PlanningIntegerProblem.OUT_OF_RANGE)
    } else PlanningIntegerInput(value, null)
}

internal fun parsePlanningSequence(input: String): PlanningIntegerInput =
    parsePlanningInteger(input, optional = true, maximum = Int.MAX_VALUE.toLong())

internal fun parsePlanningLag(input: String): PlanningIntegerInput =
    parsePlanningInteger(input, optional = false, maximum = Long.MAX_VALUE)

/** A parent must stay in the same site and cannot be the edited area or one of its descendants. */
internal fun planningParentAreas(areas: List<AreaDto>, siteId: String?, areaId: String?): List<AreaDto> {
    if (siteId == null) return emptyList()
    val excluded = mutableSetOf<String>()
    areaId?.let(excluded::add)
    var previousSize: Int
    do {
        previousSize = excluded.size
        areas.filter { area -> area.parentAreaId?.let { it in excluded } == true }.forEach { excluded.add(it.areaId) }
    } while (excluded.size != previousSize)
    return areas.filter { it.siteId == siteId && it.areaId !in excluded }
}

/** The physical site is derived from the selected project/site relation, never separately typed. */
internal fun planningPackageSite(plan: ProjectPlanDto, projectSiteId: String): ProjectPlanSiteDto? =
    plan.projectSites.firstOrNull { it.projectSiteId == projectSiteId && it.lifecycleState != "CLOSED" }

internal data class PlanningNodeChoice(val type: String, val id: String, val name: String, val code: String) {
    val key: String get() = "$type:$id"
}

internal fun planningDependencyNodes(plan: ProjectPlanDto): List<PlanningNodeChoice> =
    plan.milestones.filter { it.baselineVersion == plan.project.baselineVersion && it.state != "CANCELLED" }
        .map { PlanningNodeChoice("MILESTONE", it.milestoneId, it.name, it.code) } +
        plan.workPackages.filter { it.baselineVersion == plan.project.baselineVersion && it.state != "CANCELLED" }
            .map { PlanningNodeChoice("WORK_PACKAGE", it.workPackageId, it.name, it.code) }

internal data class PlanningFormBaseline(
    val projectVersion: Long,
    val baselineVersion: Int,
    val objectVersion: Long? = null,
)

internal enum class PlanningFormPhase { EDITING, SUBMITTING, REJECTED, UNKNOWN, SUCCEEDED, DISCARDED }

/** In-memory draft for one editor. Refreshing source records never rewrites the user's values. */
internal class PlanningFormSession(
    baseline: PlanningFormBaseline,
    initialValues: Map<String, String>,
) {
    private val originalValues = initialValues.toMap()
    private val fields = mutableStateMapOf<String, String>().apply { putAll(initialValues) }
    private var active = true
    private var uncertain = false
    var baseline by mutableStateOf(baseline)
        private set
    var phase by mutableStateOf(PlanningFormPhase.EDITING)
        private set
    var request by mutableStateOf<PlanningFormRequest?>(null)
        private set
    var result by mutableStateOf<PlanningFormResult?>(null)
        private set

    val open: Boolean get() = active && phase != PlanningFormPhase.SUCCEEDED && phase != PlanningFormPhase.DISCARDED
    val locked: Boolean get() = phase == PlanningFormPhase.SUBMITTING || phase == PlanningFormPhase.UNKNOWN
    val dirty: Boolean get() = fields != originalValues
    val requiresSourceReview: Boolean
        get() = phase == PlanningFormPhase.REJECTED &&
            (result as? PlanningFormResult.Rejected)?.code in setOf(
                "VERSION_CONFLICT", "PROJECT_VERSION_CONFLICT", "STATE_CONFLICT", "PLAN_FROZEN", "REJECTED_STATE",
            )

    fun value(key: String): String = fields[key].orEmpty()

    fun update(key: String, value: String) {
        if (open && !locked) fields[key] = value
    }

    fun begin(action: PlanningUiAction, operation: () -> PlanningFormOperation): PlanningFormRequest? {
        if (!open || locked || requiresSourceReview) return null
        return PlanningFormRequest(action, operation()).also {
            request = it
            result = null
            phase = PlanningFormPhase.SUBMITTING
        }
    }

    fun retry(): PlanningFormRequest? {
        if (!open || phase != PlanningFormPhase.UNKNOWN) return null
        return request?.also { phase = PlanningFormPhase.SUBMITTING }
    }

    /** A callback can finish only its own current request while its actor/route scope is still alive. */
    fun receive(operationId: String, response: PlanningFormResult): Boolean {
        val submitted = request ?: return false
        if (!active || phase != PlanningFormPhase.SUBMITTING || submitted.operation.operationId != operationId) return false
        val checked = if (response is PlanningFormResult.Succeeded) planningFormResponse(submitted, response.response) else response
        when (checked) {
            is PlanningFormResult.Succeeded -> {
                uncertain = false
                phase = PlanningFormPhase.SUCCEEDED
            }
            is PlanningFormResult.Unknown -> {
                uncertain = true
                phase = PlanningFormPhase.UNKNOWN
            }
            is PlanningFormResult.Rejected -> {
                // A denial of a retry cannot establish whether the earlier timed-out attempt committed.
                if (uncertain && checked.code != "OPERATION_PREVIOUSLY_FAILED") {
                    result = PlanningFormResult.Unknown(checked.code, checked.correlationId)
                    phase = PlanningFormPhase.UNKNOWN
                    return true
                }
                uncertain = false
                phase = PlanningFormPhase.REJECTED
            }
        }
        result = checked
        return true
    }

    /** Explicit review is required before using fresh versions; the draft text remains untouched. */
    fun adoptReviewedSource(updated: PlanningFormBaseline, uneditedValues: Map<String, String> = emptyMap()): Boolean {
        if (!open || locked || !requiresSourceReview || updated == baseline) return false
        baseline = updated
        fields.putAll(uneditedValues)
        request = null
        result = null
        phase = PlanningFormPhase.EDITING
        return true
    }

    fun discard(): Boolean {
        if (!open || locked) return false
        phase = PlanningFormPhase.DISCARDED
        fields.clear()
        return true
    }

    fun invalidate() {
        active = false
        fields.clear()
    }
}

internal class PlanningFormRegistry(private val scopeKey: String) {
    private val sessions = mutableStateMapOf<String, PlanningFormSession>()
    private var active = true
    val hasUnresolvedRequest: Boolean get() = sessions.values.any { it.open && it.locked }

    fun current(key: String): PlanningFormSession? = sessions[key]?.takeIf { it.open }

    /** A refreshed projection may omit a removed target while its command result is still unknown. */
    fun detachedSessions(plan: ProjectPlanDto): List<PlanningFormSession> =
        sessions.filter { (key, session) ->
            session.open && when {
                key.startsWith("remove:") -> plan.dependencies.none { it.dependencyId == key.removePrefix("remove:") }
                key.startsWith("area:") -> plan.areas.none { area ->
                    area.areaId == key.removePrefix("area:") && plan.projectSites.any { it.siteId == area.siteId }
                }
                key.startsWith("milestone:") -> plan.milestones.none { it.milestoneId == key.removePrefix("milestone:") }
                key.startsWith("package:") -> plan.workPackages.none { it.workPackageId == key.removePrefix("package:") }
                else -> false
            }
        }.values.toList()

    fun open(key: String, baseline: PlanningFormBaseline, initialValues: Map<String, String>) {
        if (active && !hasUnresolvedRequest && current(key) == null) sessions[key] = PlanningFormSession(baseline, initialValues)
    }

    fun submit(session: PlanningFormSession, action: PlanningUiAction, host: PlanningFormHost) {
        if (!active || scopeKey != host.scopeKey || hasUnresolvedRequest) return
        session.begin(action, host.newOperation)?.let { dispatch(session, it, host) }
    }

    fun retry(session: PlanningFormSession, host: PlanningFormHost) {
        if (!active || scopeKey != host.scopeKey) return
        session.retry()?.let { dispatch(session, it, host) }
    }

    private fun dispatch(session: PlanningFormSession, request: PlanningFormRequest, host: PlanningFormHost) {
        try {
            host.submit(request) { result ->
                if (active && scopeKey == host.scopeKey) session.receive(request.operation.operationId, result)
            }
        } catch (failure: Exception) {
            session.receive(request.operation.operationId, planningFormFailure(failure))
        }
    }

    fun invalidate() {
        active = false
        sessions.values.forEach(PlanningFormSession::invalidate)
        sessions.clear()
    }
}

private fun PlanningFormRequest.matches(response: PlanningMutationResponseDto): Boolean {
    val projectId: String
    val type: String
    val targetId: String?
    when (val command = action) {
        is PlanningUiAction.CreateArea -> { projectId = command.projectId; type = "AREA"; targetId = null }
        is PlanningUiAction.UpdateArea -> { projectId = command.projectId; type = "AREA"; targetId = command.areaId }
        is PlanningUiAction.CreateMilestone -> { projectId = command.projectId; type = "MILESTONE"; targetId = null }
        is PlanningUiAction.UpdateMilestone -> { projectId = command.projectId; type = "MILESTONE"; targetId = command.milestoneId }
        is PlanningUiAction.CreateWorkPackage -> { projectId = command.projectId; type = "WORK_PACKAGE"; targetId = null }
        is PlanningUiAction.UpdateWorkPackage -> { projectId = command.projectId; type = "WORK_PACKAGE"; targetId = command.workPackageId }
        is PlanningUiAction.AddDependency -> { projectId = command.projectId; type = "PROJECT_PLAN_DEPENDENCY"; targetId = null }
        is PlanningUiAction.RemoveDependency -> { projectId = command.projectId; type = "PROJECT_PLAN_DEPENDENCY"; targetId = command.dependencyId }
        is PlanningUiAction.MarkReady -> { projectId = command.projectId; type = "PROJECT"; targetId = command.projectId }
    }
    return response.plan.project.projectId == projectId && response.targetType == type &&
        response.targetId.isNotBlank() && (targetId == null || response.targetId == targetId)
}

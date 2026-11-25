package com.hiltech.shared.core.projects

import com.hiltech.shared.core.PlanningFormRequest
import com.hiltech.shared.core.PlanningUiAction
import com.hiltech.shared.core.network.HiltechApiClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException

/** A delayed command must never pick up a different actor's newly installed access token. */
suspend fun submitPlanningFormInSession(
    httpClient: HttpClient,
    baseUrl: String,
    installationId: String,
    request: PlanningFormRequest,
    sessionCurrent: () -> Boolean,
    accessToken: suspend () -> String?,
    correlationId: () -> String,
): PlanningMutationResponseDto {
    fun ensureSession() {
        if (!sessionCurrent()) throw CancellationException("Planning session changed before dispatch")
    }
    ensureSession()
    val initiatingToken = accessToken()
    ensureSession()
    val client = PlanningApiClient(HiltechApiClient(
        client = httpClient,
        baseUrl = baseUrl,
        accessTokenProvider = { ensureSession(); initiatingToken },
        correlationIdProvider = correlationId,
    ))
    return client.submitForm(request, installationId)
}

/** The form owns operation identity. Retrying a lost response must replay the same command. */
suspend fun PlanningApiClient.submitForm(
    request: PlanningFormRequest,
    installationId: String,
): PlanningMutationResponseDto {
    val operation = request.operation
    return when (val action = request.action) {
        is PlanningUiAction.CreateArea -> createArea(action.projectId, CreateAreaRequestDto(
            operation.operationId, action.projectSiteId, action.parentAreaId, action.typeCode,
            action.code, action.name, action.sequence, action.restrictedAccess,
            action.baseProjectVersion, action.expectedBaselineVersion, operation.clientOccurredAt,
        ), installationId)
        is PlanningUiAction.UpdateArea -> updateArea(action.areaId, UpdateAreaRequestDto(
            operation.operationId, action.projectId, action.parentAreaId, action.typeCode,
            action.code, action.name, action.sequence, action.restrictedAccess,
            action.baseProjectVersion, action.baseObjectVersion, action.expectedBaselineVersion,
            operation.clientOccurredAt,
        ), installationId)
        is PlanningUiAction.CreateMilestone -> createMilestone(action.projectId, CreateMilestoneRequestDto(
            operation.operationId, action.code, action.name, action.plannedDate, action.sequence,
            action.clientVisible, action.acceptanceRequirement, action.baseProjectVersion,
            action.expectedBaselineVersion, operation.clientOccurredAt,
        ), installationId)
        is PlanningUiAction.UpdateMilestone -> updateMilestone(action.milestoneId, UpdateMilestoneRequestDto(
            operation.operationId, action.code, action.name, action.plannedDate, action.sequence,
            action.clientVisible, action.acceptanceRequirement, action.baseProjectVersion,
            action.baseObjectVersion, action.expectedBaselineVersion, operation.clientOccurredAt,
        ), installationId)
        is PlanningUiAction.CreateWorkPackage -> createWorkPackage(action.projectId, CreateWorkPackageRequestDto(
            operation.operationId, action.projectSiteId, action.siteId, action.milestoneId,
            action.code, action.name, action.description, planningFormOwner(action.ownerType, action.ownerId),
            action.plannedStart, action.plannedEnd, action.sequence, action.baseProjectVersion,
            action.expectedBaselineVersion, operation.clientOccurredAt,
        ), installationId)
        is PlanningUiAction.UpdateWorkPackage -> updateWorkPackage(action.workPackageId, UpdateWorkPackageRequestDto(
            operation.operationId, action.projectSiteId, action.siteId, action.milestoneId,
            action.code, action.name, action.description, planningFormOwner(action.ownerType, action.ownerId),
            action.plannedStart, action.plannedEnd, action.sequence, action.baseProjectVersion,
            action.baseObjectVersion, action.expectedBaselineVersion, operation.clientOccurredAt,
        ), installationId)
        is PlanningUiAction.AddDependency -> addDependency(action.projectId, AddPlanDependencyRequestDto(
            operation.operationId, PlanNodeDto(action.predecessorType, action.predecessorId),
            PlanNodeDto(action.successorType, action.successorId), action.dependencyType,
            action.lagMinutes, action.baseProjectVersion, action.expectedBaselineVersion,
            operation.clientOccurredAt,
        ), installationId)
        is PlanningUiAction.RemoveDependency -> removeDependency(action.projectId, action.dependencyId,
            RemovePlanDependencyRequestDto(operation.operationId, action.baseProjectVersion,
                action.baseObjectVersion, action.expectedBaselineVersion, operation.clientOccurredAt),
            installationId)
        is PlanningUiAction.MarkReady -> markReady(action.projectId,
            MarkProjectReadyRequestDto(operation.operationId, action.baseProjectVersion,
                action.expectedBaselineVersion, operation.clientOccurredAt), installationId)
    }
}

private fun planningFormOwner(type: String?, id: String?): PlanningOwnerDto? {
    if (type.isNullOrBlank() && id.isNullOrBlank()) return null
    require(!type.isNullOrBlank() && !id.isNullOrBlank()) { "Planning owner type and identity are required together." }
    return PlanningOwnerDto(type, id)
}

fun PlanningUiAction.projectId(): String = when (this) {
    is PlanningUiAction.CreateArea -> projectId
    is PlanningUiAction.UpdateArea -> projectId
    is PlanningUiAction.CreateMilestone -> projectId
    is PlanningUiAction.UpdateMilestone -> projectId
    is PlanningUiAction.CreateWorkPackage -> projectId
    is PlanningUiAction.UpdateWorkPackage -> projectId
    is PlanningUiAction.AddDependency -> projectId
    is PlanningUiAction.RemoveDependency -> projectId
    is PlanningUiAction.MarkReady -> projectId
}

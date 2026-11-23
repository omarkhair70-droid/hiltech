package com.hiltech.shared.core.service

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class MaintenanceAgendaItemDto(
    val visitId: String,
    val workOrderId: String,
    val siteId: String,
    val plannedFor: String,
    val workOrderTitle: String,
    val sourceWorkState: String,
    val sourceWorkVersion: Long,
    val reviewState: String,
    val reviewEventNumber: Int,
    val visitWindowState: String,
    val originalPlanState: String = "DRAFT",
    val coverageState: String = "UNVERIFIED",
    val contractScheduleApproved: Boolean = false,
)

@Serializable
data class MaintenanceAgendaDto(
    val projectId: String,
    val from: String,
    val to: String,
    val items: List<MaintenanceAgendaItemDto>,
    val truncated: Boolean,
    val clientVisibility: String = "INTERNAL_ONLY",
)

/**
 * PM-only, source-backed planning window. These are actual internal DRAFT
 * proposals, not contract-authorized preventive visits or SLA deadlines.
 */
class MaintenanceAgendaApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    suspend fun agenda(
        projectId: String, fromInclusiveUtc: String,
        toExclusiveUtc: String, installationId: String,
    ): MaintenanceAgendaDto {
        require(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
            .matches(projectId)) { "Canonical project UUID required." }
        val safeInstant = Regex("^[0-9TZ:.-]+$")
        require(safeInstant.matches(fromInclusiveUtc) && safeInstant.matches(toExclusiveUtc)) {
            "UTC ISO time window required."
        }
        return api.request(
            method = HttpMethod.Get,
            path = "/v1/projects/" + projectId + "/maintenance-visits/agenda" +
                "?from=" + fromInclusiveUtc + "&to=" + toExclusiveUtc,
            options = HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(MaintenanceAgendaDto.serializer(), it) },
        )
    }
}

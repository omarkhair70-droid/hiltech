package com.hiltech.shared.core.service

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import com.hiltech.shared.core.design.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.NativeOperationalWorkspace
import com.hiltech.shared.core.projects.ProjectSummaryDto
import com.hiltech.shared.core.projects.ProjectsApiClient
import kotlinx.coroutines.CancellationException

/**
 * Internal service native workbench:
 * tickets, warranty readiness, observed internal response time and proposed
 * maintenance agenda. No ticket edits, client sends, contractual SLA decisions,
 * service billing, approved warranty claims or visit dispatch.
 * Private note recording uses the original project-scoped Support Desk API.
 *
 * Each actual view is rendered by its Phase11 source-owned Compose Section;
 * the native host merely selects the scoped original API and bounded pane.
 */
fun serviceOperationsReadNativeWorkspace(
    projects: ProjectsApiClient,
    ticketsApi: SupportDeskApiClient,
    warrantyApi: WarrantyCoverageReadinessApiClient,
    timeApi: SupportTimeApiClient,
    agendaApi: MaintenanceAgendaApiClient,
    identityId: String,
    organizationId: String,
    installationId: String,
    newOperationId: () -> String,
    onReauthenticate: () -> Unit,
): NativeOperationalWorkspace = NativeOperationalWorkspace(
    id = "service-operations-read",
    label = "الدعم والصيانة",
    canReadSource = {
        val source = projects.list(organizationId, installationId, limit = 40)
        var allowed = false
        for (project in source.items.filter { it.organizationId == organizationId }) {
            val result = try {
                ticketsApi.list(project.projectId, installationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (result != null && result.all { it.projectId == project.projectId }) {
                allowed = true
                break
            }
        }
        allowed
    },
    hostOwnsScroll = false,
    content = { _ ->
        var allowedProjects by remember(identityId, organizationId, installationId) {
            mutableStateOf<List<ProjectSummaryDto>>(emptyList())
        }
        var selectedProjectId by remember(identityId, organizationId, installationId) {
            mutableStateOf<String?>(null)
        }
        var projectRefresh by remember(identityId, organizationId, installationId) {
            mutableIntStateOf(0)
        }
        var projectsLoading by remember(identityId, organizationId, installationId) {
            mutableStateOf(true)
        }
        var projectsError by remember(identityId, organizationId, installationId) {
            mutableStateOf<String?>(null)
        }

        LaunchedEffect(projects, ticketsApi, identityId, organizationId, installationId, projectRefresh) {
            projectsLoading = true
            allowedProjects = emptyList()
            projectsError = null
            try {
                val source = projects.list(organizationId, installationId, limit = 40)
                val accepted = mutableListOf<ProjectSummaryDto>()
                for (project in source.items.filter { it.organizationId == organizationId }) {
                    val ticketRecords = try {
                        ticketsApi.list(project.projectId, installationId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    if (ticketRecords != null &&
                        ticketRecords.all { it.projectId == project.projectId }
                    ) accepted += project
                }
                allowedProjects = accepted
                if (accepted.none { it.projectId == selectedProjectId }) selectedProjectId = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                selectedProjectId = null
                projectsError = "SERVICE_PROJECT_SOURCE_DENIED_OR_UNAVAILABLE"
            } finally {
                projectsLoading = false
            }
        }

        if (selectedProjectId == null) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val colors = LocalHiltechOperationalColors.current
                var query by remember { mutableStateOf("") }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        HiltechSectionHeading("SERVICE", hiltechText("الدعم والصيانة", "Support & maintenance"),
                            hiltechText("البلاغات والضمان والمواعيد وزيارات الصيانة لكل مشروع.", "Tickets, warranty, timing and maintenance visits by project."))
                    }
                    TextButton(enabled = !projectsLoading, onClick = { projectRefresh++ }) {
                        Text(hiltechText("تحديث", "Refresh"))
                    }
                }
                OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(hiltechText("اسم المشروع أو الكود", "Project name or code")) })
                if (projectsLoading) Text(hiltechText("جارٍ تحميل المشاريع…", "Loading projects…"))
                projectsError?.let { Text(hiltechText("المصدر غير متاح: ", "Source unavailable: ") + it) }
                val matching = allowedProjects.filter { project -> query.isBlank() ||
                    listOf(project.name, project.projectCode, project.clientDisplayName).any { it.contains(query.trim(), true) } }
                if (!projectsLoading && projectsError == null && matching.isEmpty()) Text(hiltechText("لا توجد مشاريع مطابقة.", "No matching projects."))
                ticketsApi.noteSession.pending?.target?.takeIf { it.identityId == identityId }?.let { pending ->
                    val source = allowedProjects.firstOrNull { it.projectId == pending.projectId }
                    if (source != null && !projectsLoading) {
                        OutlinedButton(onClick = { selectedProjectId = source.projectId }) {
                            Text(hiltechText("متابعة ملاحظة معلّقة", "Resume pending note") + " · " + source.name)
                        }
                    }
                }
                matching.forEach { project ->
                    Surface(onClick = { if (allowedProjects.any { it.projectId == project.projectId }) selectedProjectId = project.projectId },
                        enabled = !projectsLoading, color = colors.surface, contentColor = colors.primaryText,
                        shape = HiltechShape.medium, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(project.projectCode, style = MaterialTheme.typography.labelMedium, color = colors.secondaryText)
                                Text(project.name, style = MaterialTheme.typography.titleMedium)
                                Text(project.clientDisplayName, color = colors.secondaryText)
                                HiltechStateTag(project.lifecycleState, HiltechStateTone.NEUTRAL)
                            }
                            Text(hiltechText("فتح", "Open"), color = colors.accent)
                        }
                    }
                }
            }
        } else {
            val project = allowedProjects.firstOrNull { it.projectId == selectedProjectId }
            val projectId = project?.projectId
            if (projectId == null) {
                Text("Project access changed — refresh current source.")
            } else {
                var tab by remember(identityId, organizationId, projectId) {
                    mutableStateOf("TICKETS")
                }
                var ticketRecords by remember(identityId, organizationId, projectId) {
                    mutableStateOf<List<InternalSupportTicketDto>>(emptyList())
                }
                var selectedTicketId by remember(identityId, organizationId, projectId) {
                    mutableStateOf(ticketsApi.noteSession.pending?.target?.takeIf {
                        it.projectId == projectId && it.identityId == identityId
                    }?.ticketId)
                }
                var ticketsLoading by remember(identityId, organizationId, projectId) {
                    mutableStateOf(true)
                }
                var ticketError by remember(identityId, organizationId, projectId) {
                    mutableStateOf<String?>(null)
                }
                var ticketRefresh by remember(identityId, organizationId, projectId) {
                    mutableIntStateOf(0)
                }
                LaunchedEffect(ticketsApi, identityId, organizationId, projectId, installationId, ticketRefresh) {
                    ticketsLoading = true
                    ticketRecords = emptyList()
                    ticketError = null
                    try {
                        val current = ticketsApi.list(projectId, installationId)
                        if (current.any { it.projectId != projectId }) {
                            selectedTicketId = null
                            ticketError = "SERVICE_TICKET_SCOPE_CHANGED"
                        } else {
                            ticketRecords = current
                            if (current.none { it.ticketId == selectedTicketId }) selectedTicketId = null
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        selectedTicketId = null
                        ticketError = "SERVICE_TICKET_SOURCE_DENIED_OR_UNAVAILABLE"
                    } finally {
                        ticketsLoading = false
                    }
                }

                val selectedTicket = ticketRecords.firstOrNull { it.ticketId == selectedTicketId }
                var coverage by remember(identityId, organizationId, projectId, selectedTicketId) {
                    mutableStateOf<WarrantyCoverageReadinessDto?>(null)
                }
                var coverageError by remember(identityId, organizationId, projectId, selectedTicketId) {
                    mutableStateOf<String?>(null)
                }
                var coverageLoading by remember(identityId, organizationId, projectId, selectedTicketId) {
                    mutableStateOf(false)
                }
                var coverageRefresh by remember(identityId, organizationId, projectId, selectedTicketId) {
                    mutableIntStateOf(0)
                }
                LaunchedEffect(warrantyApi, projectId, installationId, selectedTicketId, tab, coverageRefresh) {
                    val current = selectedTicket ?: return@LaunchedEffect
                    if (tab != "COVERAGE") return@LaunchedEffect
                    coverageLoading = true
                    coverage = null
                    coverageError = null
                    try {
                        val result = warrantyApi.read(projectId, current.ticketId, installationId)
                        if (result.projectId == projectId && result.ticketId == current.ticketId) {
                            coverage = result
                        } else coverageError = "WARRANTY_SOURCE_SCOPE_CHANGED"
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        coverageError = "WARRANTY_READ_DENIED_OR_NOT_INTEGRATED"
                    } finally {
                        coverageLoading = false
                    }
                }

                var observation by remember(identityId, organizationId, projectId, selectedTicketId) {
                    mutableStateOf<SupportTimeObservationDto?>(null)
                }
                var timeHistory by remember(identityId, organizationId, projectId, selectedTicketId) {
                    mutableStateOf<List<SupportTimeEventDto>>(emptyList())
                }
                var timeLoading by remember(identityId, organizationId, projectId, selectedTicketId) {
                    mutableStateOf(false)
                }
                var timeError by remember(identityId, organizationId, projectId, selectedTicketId) {
                    mutableStateOf<String?>(null)
                }
                var timeRefresh by remember(identityId, organizationId, projectId, selectedTicketId) {
                    mutableIntStateOf(0)
                }
                LaunchedEffect(timeApi, projectId, installationId, selectedTicketId, tab, timeRefresh) {
                    val current = selectedTicket ?: return@LaunchedEffect
                    if (tab != "TIME") return@LaunchedEffect
                    timeLoading = true
                    observation = null
                    timeHistory = emptyList()
                    timeError = null
                    try {
                        val source = timeApi.observation(projectId, current.ticketId, installationId)
                        val events = timeApi.events(projectId, current.ticketId, installationId)
                        if (source.ticketId == current.ticketId &&
                            events.all { it.ticketId == current.ticketId }
                        ) {
                            observation = source
                            timeHistory = events
                        } else timeError = "SUPPORT_TIMING_SOURCE_SCOPE_CHANGED"
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        timeError = "SUPPORT_TIME_READ_DENIED_OR_NOT_INTEGRATED"
                    } finally {
                        timeLoading = false
                    }
                }

                var agendaWindow by remember(identityId, organizationId, projectId) {
                    mutableStateOf<Pair<String, String>?>(null)
                }
                var agendaData by remember(identityId, organizationId, projectId) {
                    mutableStateOf<MaintenanceAgendaDto?>(null)
                }
                var agendaLoading by remember(identityId, organizationId, projectId) {
                    mutableStateOf(false)
                }
                var agendaError by remember(identityId, organizationId, projectId) {
                    mutableStateOf<String?>(null)
                }
                var agendaRefresh by remember(identityId, organizationId, projectId) {
                    mutableIntStateOf(0)
                }
                LaunchedEffect(agendaApi, projectId, installationId, agendaWindow, agendaRefresh) {
                    val window = agendaWindow ?: return@LaunchedEffect
                    agendaLoading = true
                    agendaData = null
                    agendaError = null
                    try {
                        val source = agendaApi.agenda(projectId, window.first, window.second, installationId)
                        if (source.projectId == projectId) agendaData = source
                        else agendaError = "MAINTENANCE_AGENDA_SCOPE_CHANGED"
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        agendaError = "MAINTENANCE_AGENDA_READ_DENIED_OR_NOT_INTEGRATED"
                    } finally {
                        agendaLoading = false
                    }
                }

                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { selectedProjectId = null }) {
                        Text(hiltechText("الرجوع لمشاريع الخدمة", "Back to service projects"))
                    }
                    Text(project.name)
                    ticketsApi.noteSession.pending?.target?.takeIf {
                        it.identityId == identityId && it.projectId == projectId && it.ticketId != selectedTicketId
                    }?.let { pending ->
                        if (!ticketsLoading && ticketRecords.any { it.ticketId == pending.ticketId }) {
                            OutlinedButton(onClick = { selectedTicketId = pending.ticketId; tab = "TICKETS" }) {
                                Text(hiltechText("متابعة ملاحظة معلّقة", "Resume pending note"))
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for ((key, title) in listOf(
                            "TICKETS" to hiltechText("البلاغات", "Tickets"),
                            "COVERAGE" to hiltechText("استحقاق الضمان", "Warranty coverage"),
                            "TIME" to hiltechText("التوقيت الداخلي", "Internal timing"),
                            "AGENDA" to hiltechText("مقترحات الزيارات", "Visit agenda"),
                        )) {
                            OutlinedButton(
                                enabled = key != tab,
                                onClick = { tab = key },
                            ) { Text(title) }
                        }
                    }
                    if (tab in setOf("COVERAGE", "TIME") && selectedTicket == null) {
                        Text(hiltechText("اختَر بلاغًا من شاشة البلاغات لعرض هذا المصدر.", "Select a support ticket to view this source."))
                    } else {
                        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                            when (tab) {
                                "TICKETS" -> if (selectedTicket != null) {
                                    SupportTicketNotes(selectedTicket, identityId, installationId, ticketsApi,
                                        newOperationId, onReauthenticate, onBack = { selectedTicketId = null })
                                } else InternalSupportDeskSection(
                                    state = SupportDeskUiState(
                                        loading = ticketsLoading,
                                        projectName = project.name,
                                        tickets = ticketRecords,
                                        selectedTicket = selectedTicket,
                                        errorMessage = ticketError,
                                    ),
                                    onRefresh = { ticketRefresh++ },
                                    onCreateTicket = { _, _, _, _ -> },
                                    onSelectTicket = { id ->
                                        if (!ticketsLoading && ticketRecords.any { it.ticketId == id }) {
                                            selectedTicketId = id
                                        }
                                    },
                                    onOpenCase = {},
                                    onOpenTriage = {},
                                    onAddInternalNote = { _, _ -> },
                                    readOnly = true,
                                )
                                "COVERAGE" -> WarrantyCoverageReadinessSection(
                                    state = WarrantyCoverageReadinessUiState(
                                        ticketTitle = selectedTicket?.title.orEmpty(),
                                        assessment = coverage,
                                        loading = coverageLoading,
                                        error = coverageError,
                                    ),
                                    onRefresh = { coverageRefresh++ },
                                )
                                "TIME" -> SupportInternalTimeSection(
                                    state = SupportTimeUiState(
                                        ticketTitle = selectedTicket?.title.orEmpty(),
                                        observation = observation,
                                        history = timeHistory,
                                        loading = timeLoading,
                                        error = timeError,
                                    ),
                                    onRefresh = { timeRefresh++ },
                                    onRecord = { _, _, _ -> },
                                    readOnly = true,
                                )
                                "AGENDA" -> MaintenancePlanningAgendaSection(
                                    state = MaintenanceAgendaUiState(
                                        projectId = projectId,
                                        projectName = project.name,
                                        agenda = agendaData,
                                        loading = agendaLoading,
                                        error = agendaError,
                                    ),
                                    onQuery = { from, to ->
                                        agendaWindow = from to to
                                        agendaRefresh++
                                    },
                                    onOpenVisit = {},
                                    canOpenVisit = false,
                                )
                            }
                        }
                    }
                }
            }
        }
    },
)

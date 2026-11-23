package com.hiltech.shared.core.service

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
 * Authenticated Phase 11 source read for a bounded, independently scrolling
 * native workspace. This integration performs NO handover snapshot, client
 * acceptance, external sharing, contract activation or warranty mutation.
 *
 * The original project API supplies only current org projects; the original
 * Handover source grants each detail separately. Never invent a project,
 * visibility right, SLA, approval or a client signature.
 */
fun handoverReadNativeWorkspace(
    projects: ProjectsApiClient,
    handover: HandoverApiClient,
    identityId: String,
    organizationId: String,
    installationId: String,
): NativeOperationalWorkspace = NativeOperationalWorkspace(
    id = "handover-read",
    label = "تسليم المشاريع",
    canReadSource = {
        val list = projects.list(organizationId, installationId, limit = 40)
        var allowed = false
        for (project in list.items) {
            if (project.organizationId != organizationId) continue
            val readiness = try {
                handover.readiness(project.projectId, installationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (readiness?.organizationId == organizationId &&
                readiness.projectId == project.projectId
            ) {
                allowed = true
                break
            }
        }
        allowed
    },
    hostOwnsScroll = false,
    content = { _ ->
        var eligible by remember(identityId, organizationId, installationId) {
            mutableStateOf<List<ProjectSummaryDto>>(emptyList())
        }
        var selectedId by remember(identityId, organizationId, installationId) {
            mutableStateOf<String?>(null)
        }
        var readiness by remember(identityId, organizationId, installationId) {
            mutableStateOf<HandoverReadinessDto?>(null)
        }
        var latest by remember(identityId, organizationId, installationId) {
            mutableStateOf<HandoverPackageVersionDto?>(null)
        }
        var loading by remember(identityId, organizationId, installationId) {
            mutableStateOf(true)
        }
        var error by remember(identityId, organizationId, installationId) {
            mutableStateOf<String?>(null)
        }
        var refresh by remember(identityId, organizationId, installationId) {
            mutableIntStateOf(0)
        }

        LaunchedEffect(projects, handover, identityId, organizationId, installationId, refresh) {
            loading = true
            eligible = emptyList()
            selectedId = null
            readiness = null
            latest = null
            error = null
            try {
                val response = projects.list(organizationId, installationId, limit = 40)
                for (project in response.items) {
                    if (project.organizationId != organizationId) continue
                    val source = try {
                        handover.readiness(project.projectId, installationId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    if (source?.organizationId == organizationId &&
                        source.projectId == project.projectId
                    ) eligible = eligible + project
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                eligible = emptyList()
                error = "HANDOVER_SOURCE_DENIED_OR_UNAVAILABLE"
            } finally {
                loading = false
            }
        }

        LaunchedEffect(handover, identityId, organizationId, installationId, selectedId) {
            val id = selectedId ?: return@LaunchedEffect
            if (eligible.none { it.projectId == id }) return@LaunchedEffect
            loading = true
            readiness = null
            latest = null
            error = null
            try {
                val result = handover.readiness(id, installationId)
                if (result.projectId != id || result.organizationId != organizationId) {
                    error = "HANDOVER_PROJECT_SCOPE_CHANGED"
                    selectedId = null
                } else {
                    readiness = result
                    latest = try {
                        handover.latest(id, installationId)
                            .takeIf { it.projectId == id }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // No current package or an unavailable package read
                        // is not proof of a signed client handover.
                        null
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                selectedId = null
                error = "HANDOVER_READ_DENIED_OR_UNAVAILABLE"
            } finally {
                loading = false
            }
        }

        if (selectedId == null) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("تسليم المشاريع / Handover — قراءة من المصدر، بدون إجراءات خارجية")
                OutlinedButton(enabled = !loading, onClick = { refresh++ }) {
                    Text("تحديث صلاحيات ومشاريع التسليم")
                }
                if (loading) Text("جارٍ التحقق من المصادر والصلاحيات…")
                error?.let { Text("المصدر غير متاح: " + it) }
                if (!loading && error == null && eligible.isEmpty()) {
                    Text("لا توجد مشاريع مخوّل لك عرض تسليمها في نافذة القراءة الحالية.")
                }
                eligible.forEach { project ->
                    OutlinedButton(enabled = !loading, onClick = {
                        if (eligible.any { it.projectId == project.projectId }) {
                            selectedId = project.projectId
                        }
                    }) { Text(project.projectCode + " · " + project.name) }
                }
            }
        } else {
            val current = eligible.firstOrNull { it.projectId == selectedId }
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    selectedId = null
                    readiness = null
                    latest = null
                }) { Text("الرجوع لمشاريع التسليم") }
                Box(Modifier.weight(1f)) {
                    HandoverSection(
                        state = HandoverUiState(
                            loading = loading,
                            projectName = current?.name.orEmpty(),
                            readiness = readiness,
                            latestDraft = latest,
                            errorMessage = error,
                        ),
                        onRefresh = { refresh++ },
                        onCreateInternalDraft = {},
                        onOpenInternalReview = {},
                        onOpenServiceContractSource = {},
                        readOnly = true,
                    )
                }
            }
        }
    },
)

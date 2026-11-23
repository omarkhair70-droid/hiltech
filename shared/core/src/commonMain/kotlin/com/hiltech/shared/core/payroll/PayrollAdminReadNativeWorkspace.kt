package com.hiltech.shared.core.payroll

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.hiltech.shared.core.NativeOperationalWorkspace
import kotlinx.coroutines.CancellationException

/**
 * Source-authorized, read-only Payroll Admin review for both native platforms.
 * Distinct from /v1/me/payroll/payslips: a worker's own pay access does NOT
 * imply authority to list colleagues, payroll inputs or exceptions.
 *
 * The existing Payroll9aAdminSection owns its bounded vertical scroll and
 * all admin commands are hidden using canPrepare=false/canReview=false.
 * Neither approving, releasing, calculating nor paying is available here.
 */
fun payrollAdminReadNativeWorkspace(
    client: Payroll9aApiClient,
    identityId: String,
    organizationId: String,
    installationId: String,
): NativeOperationalWorkspace = NativeOperationalWorkspace(
    id = "payroll-admin-read",
    label = "مراجعة فترات المرتبات",
    canReadSource = {
        client.list(organizationId, installationId)
        true
    },
    hostOwnsScroll = false,
    content = { _ ->
        var refresh by remember(identityId, organizationId, installationId) {
            mutableIntStateOf(0)
        }
        var periods by remember(identityId, organizationId, installationId) {
            mutableStateOf<PayrollPeriodListDto?>(null)
        }
        var selectedId by remember(identityId, organizationId, installationId) {
            mutableStateOf<String?>(null)
        }
        var detail by remember(identityId, organizationId, installationId) {
            mutableStateOf<PayrollPeriodDetailDto?>(null)
        }
        var loading by remember(identityId, organizationId, installationId) {
            mutableStateOf(true)
        }
        var error by remember(identityId, organizationId, installationId) {
            mutableStateOf<String?>(null)
        }
        LaunchedEffect(client, identityId, organizationId, installationId, refresh) {
            loading = true
            error = null
            periods = null
            detail = null
            try {
                val response = client.list(organizationId, installationId)
                periods = response
                if (response.items.none { it.periodId == selectedId }) selectedId = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                selectedId = null
                error = "PAYROLL_SOURCE_DENIED_OR_UNAVAILABLE"
            } finally {
                loading = false
            }
        }
        LaunchedEffect(client, identityId, organizationId, installationId, selectedId, refresh, periods) {
            val id = selectedId ?: return@LaunchedEffect
            if (periods?.items?.none { it.periodId == id } != false) return@LaunchedEffect
            loading = true
            detail = null
            error = null
            try {
                detail = client.detail(id, installationId).takeIf { it.period.periodId == id }
                if (detail == null) error = "PAYROLL_PERIOD_SCOPE_CHANGED"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                selectedId = null
                error = "PAYROLL_PERIOD_DENIED_OR_UNAVAILABLE"
            } finally {
                loading = false
            }
        }
        Payroll9aAdminSection(
            state = Payroll9aUiState(
                loading = loading,
                periods = periods,
                selected = detail,
                errorCode = error,
                canPrepare = false,
                canReview = false,
            ),
            onRefresh = { refresh += 1 },
            onSelect = { id ->
                if (!loading && periods?.items?.any { it.periodId == id } == true) {
                    selectedId = id
                }
            },
            onCreatePeriod = { _, _, _, _, _ -> },
            onCaptureInputs = {},
            onReviewException = { _, _, _ -> },
        )
    },
)

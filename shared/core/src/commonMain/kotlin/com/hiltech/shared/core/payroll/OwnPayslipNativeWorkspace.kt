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
 * Native Phase 9 employee self-service, not a payroll editor or payment panel.
 * Only the server's /v1/me/payroll/payslips for the currently signed-in
 * identity can return records. No cross-account cache or broad HR permissions.
 */
fun ownPayslipNativeWorkspace(
    client: Payroll9b9cApiClient,
    identityId: String,
    organizationId: String,
    installationId: String,
): NativeOperationalWorkspace = NativeOperationalWorkspace(
    id = "my-payslips",
    label = "كشوف مرتباتي",
    canReadSource = {
        client.ownPayslips(organizationId, installationId)
        true
    },
    hostOwnsScroll = false,
    content = { _ ->
        var refreshVersion by remember(identityId, organizationId, installationId) {
            mutableIntStateOf(0)
        }
        var records by remember(identityId, organizationId, installationId) {
            mutableStateOf<OwnPayslipsDto?>(null)
        }
        var loading by remember(identityId, organizationId, installationId) {
            mutableStateOf(true)
        }
        var error by remember(identityId, organizationId, installationId) {
            mutableStateOf<String?>(null)
        }
        LaunchedEffect(client, identityId, organizationId, installationId, refreshVersion) {
            loading = true
            records = null
            error = null
            try {
                records = client.ownPayslips(organizationId, installationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "SOURCE_READ_DENIED_OR_UNAVAILABLE"
            } finally {
                loading = false
            }
        }
        OwnPayslipSection(
            state = OwnPayslipUiState(
                loading = loading, payslips = records, errorCode = error,
            ),
            onRefresh = { refreshVersion++ },
        )
    },
)

package com.hiltech.shared.core.executive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.hiltech.shared.core.ExecutiveHomeSection
import kotlinx.coroutines.CancellationException

/**
 * A native, online-only route controller for Windows and Android.
 *
 * This component deliberately does not own authentication, choose a company,
 * open arbitrary URLs, persist an executive snapshot, or modify global shell
 * navigation. Those are responsibilities of the authenticated host.
 *
 * Changing identity, company or installation creates new state and cancels
 * the old read. The API client also verifies the echoed actor/organization.
 */
@Composable
fun ExecutiveHomeRoute(
    client: ExecutiveApiClient,
    currentIdentityId: String,
    currentOrganizationId: String,
    installationId: String,
    // The authenticated shell can bump this after a membership/role/session
    // authority update even when identity and company IDs do not change.
    authorityRevisionKey: String = "",
    onOpenSource: (ExecutiveDestination) -> Unit,
    canOpenDestination: (ExecutiveDestination) -> Boolean = { true },
) {
    var refreshVersion by remember(
        client,
        currentIdentityId,
        currentOrganizationId,
        installationId,
        authorityRevisionKey,
    ) { mutableIntStateOf(0) }

    var result by remember(
        client,
        currentIdentityId,
        currentOrganizationId,
        installationId,
        authorityRevisionKey,
    ) { mutableStateOf<ExecutiveHomeDto?>(null) }

    var loading by remember(
        client,
        currentIdentityId,
        currentOrganizationId,
        installationId,
        authorityRevisionKey,
    ) { mutableStateOf(true) }

    var loadError by remember(
        client,
        currentIdentityId,
        currentOrganizationId,
        installationId,
        authorityRevisionKey,
    ) { mutableStateOf<String?>(null) }

    LaunchedEffect(
        client,
        currentIdentityId,
        currentOrganizationId,
        installationId,
        authorityRevisionKey,
        refreshVersion,
    ) {
        loading = true
        loadError = null
        result = null
        try {
            result = client.home(
                organizationId = currentOrganizationId,
                installationId = installationId,
                expectedIdentityId = currentIdentityId,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Never render raw server exceptions or stale executive data.
            loadError = "SOURCE_READ_FAILED"
        } finally {
            loading = false
        }
    }

    ExecutiveHomeSection(
        home = result,
        expectedOrganizationId = currentOrganizationId,
        expectedIdentityId = currentIdentityId,
        loading = loading,
        loadError = loadError,
        onRefresh = { refreshVersion += 1 },
        onOpenSource = onOpenSource,
        canOpenDestination = canOpenDestination,
    )
}

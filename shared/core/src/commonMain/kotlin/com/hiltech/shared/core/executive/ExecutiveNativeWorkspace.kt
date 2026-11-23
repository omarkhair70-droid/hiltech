package com.hiltech.shared.core.executive

import com.hiltech.shared.core.NativeOperationalWorkspace

/**
 * Real Android/Windows Executive 13A+B integration; no invented management role
 * or hardcoded actor claims. The live, authenticated, actor/org-scoped source
 * must explicitly grant a read before the navigation entry becomes visible.
 *
 * Only PROJECT_DETAIL and PROJECT_HEALTH are currently connected to the
 * native Projects host. Other decision/asset destinations remain visible as
 * source evidence but have no misleading clickable actions until their
 * original source-owned detail screens are integrated.
 */
fun executiveNativeWorkspace(
    client: ExecutiveApiClient,
    identityId: String,
    organizationId: String,
    installationId: String,
    authorityRevisionKey: String,
    onOpenProject: ((String) -> Unit)? = null,
): NativeOperationalWorkspace = NativeOperationalWorkspace(
    id = "executive-home",
    label = "الإدارة / Mohamed Home",
    canReadSource = {
        // Failure or source mismatch is interpreted as denied by the host;
        // the expected actor and organization are verified by the client.
        client.home(
            organizationId = organizationId,
            installationId = installationId,
            expectedIdentityId = identityId,
        )
        true
    },
    hostOwnsScroll = false, // ExecutiveHomeSection owns its single bounded vertical scroll.
    content = { exit ->
        ExecutiveHomeRoute(
            client = client,
            currentIdentityId = identityId,
            currentOrganizationId = organizationId,
            installationId = installationId,
            authorityRevisionKey = authorityRevisionKey,
            onOpenSource = { destination ->
                if (onOpenProject != null && executiveNativeProjectDestination(destination)) {
                    onOpenProject(destination.entityId)
                    exit()
                }
            },
            canOpenDestination = { onOpenProject != null && executiveNativeProjectDestination(it) },
        )
    },
)

fun executiveNativeProjectDestination(destination: ExecutiveDestination): Boolean =
    destination.kind == ExecutiveDestinationKind.PROJECT_DETAIL ||
        destination.kind == ExecutiveDestinationKind.PROJECT_HEALTH

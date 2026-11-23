package com.hiltech.shared.core.procurement

import kotlinx.coroutines.CancellationException

/**
 * Present a service-purchasing navigation entry only if at least one
 * current source-owned authorized read succeeds. Buyer/issuer, requester and
 * delivery/Finance reviewers have distinct source endpoints; absence of one
 * entitlement does not imply the others are absent.
 *
 * An empty authorized result is still a valid current read. A 401/403/failure
 * on every source yields no entry. This helper grants no command authority:
 * every original Phase7 API still checks its own source policy at execution.
 */
suspend fun servicePurchaseAnyCurrentSourceVisible(
    needs: ServicePurchaseNeedApiClient,
    sourcing: ServiceSupplierSourcingApiClient,
    orders: ServiceOrderAndInvoiceApiClient,
    organizationId: String,
    installationId: String,
): Boolean {
    val sourceReads: List<suspend () -> Unit> = listOf(
        { needs.teams(organizationId, installationId); Unit },
        { sourcing.eligibleNeeds(organizationId, installationId); Unit },
        { orders.queue(organizationId, installationId); Unit },
        { orders.deliveryOrders(organizationId, installationId); Unit },
        { orders.invoiceOrders(organizationId, installationId); Unit },
    )
    for (read in sourceReads) {
        try {
            read()
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Check only original independently-authorized sources;
            // no mock/role fallback, and no financial/external action.
        }
    }
    return false
}

package com.hiltech.shared.core.procurement

import com.hiltech.shared.core.NativeOperationalWorkspace

/**
 * Restricted Phase 8 supplier-invoice Finance source read, retaining original
 * PO/receipt/invoice match preview. Mutating match event, Finance handoff,
 * document capture, posting and all payment actions are absent from this
 * read-only host. Company financial authority is never guessed by role name.
 */
fun supplierInvoiceFinanceReadWorkspace(
    client: SupplierInvoiceFinanceApiClient,
    organizationId: String,
    installationId: String,
    newOperationId: () -> String,
): NativeOperationalWorkspace = NativeOperationalWorkspace(
    id = "finance-invoice-read",
    label = "مراجعة فواتير الموردين",
    canReadSource = {
        client.list(organizationId, installationId)
        true
    },
    hostOwnsScroll = true, // Original finance section has no root vertical scroll.
    content = { _ ->
        SupplierInvoiceFinanceSection(
            api = client,
            organizationId = organizationId,
            installationId = installationId,
            newOperationId = newOperationId,
            readOnly = true,
        )
    },
)

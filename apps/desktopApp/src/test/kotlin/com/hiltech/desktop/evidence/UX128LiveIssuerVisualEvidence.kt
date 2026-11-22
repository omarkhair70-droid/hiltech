package com.hiltech.desktop.evidence

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.hiltech.shared.core.design.HiltechOperationalTheme
import com.hiltech.shared.core.design.HiltechKeyValue
import com.hiltech.shared.core.design.HiltechPanel
import com.hiltech.shared.core.design.LocalHiltechOperationalColors
import com.hiltech.shared.core.procurement.IssuerLiveUi
import com.hiltech.shared.core.procurement.PurchaseOrderIssuerLiveScreen
import com.hiltech.shared.core.procurement.PurchaseOrderIssuerQueueItemDto
import com.hiltech.shared.core.procurement.PurchaseOrderIssueReceiptDto
import com.hiltech.shared.core.procurement.PurchaseOrderLifecycleSummaryDto
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path

/**
 * Headless QA ONLY of the SAME production PurchaseOrderIssuerLiveScreen.
 * Typed synthetic queue/receipt; actual networked issue action slot is
 * intentionally stubbed here. No real company login, mutation or rights.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() = runBlocking<Unit> {
    val output = Path.of(
        System.getenv("HILTECH_UX128_ISSUER_RENDER_DIR")
            ?: "build/reports/ux128-live-issuer",
    )
    Files.createDirectories(output)
    data class Capture(
        val name: String,
        val width: Int,
        val height: Int,
        val content: @Composable () -> Unit,
    )
    val normal = syntheticIssuerUi()
    val pagination = normal.copy(queue = (1..13).map { index ->
        syntheticIssuerCandidate(index)
    }, selectedCandidateId = syntheticIssuerCandidate(1).candidateId)
    val cases = listOf(
        Capture("01-windows-approved-queue-and-inspector", 1440, 1150) {
            HiltechOperationalTheme(dark = false) { RenderIssuer(normal) }
        },
        Capture("02-windows-compact", 800, 2150) {
            HiltechOperationalTheme(dark = false) { RenderIssuer(normal) }
        },
        Capture("03-windows-dark", 1440, 1150) {
            HiltechOperationalTheme(dark = true) { RenderIssuer(normal) }
        },
        Capture("04-windows-high-contrast", 1440, 1150) {
            HiltechOperationalTheme(dark = true, highContrast = true) { RenderIssuer(normal) }
        },
        Capture("05-windows-180pct-text", 1440, 1750) {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.8f)) {
                HiltechOperationalTheme(dark = false) { RenderIssuer(normal) }
            }
        },
        Capture("06-empty-authorized-issuer-queue", 1440, 850) {
            HiltechOperationalTheme(dark = false) {
                RenderIssuer(normal.copy(queue = emptyList(), selectedCandidateId = null))
            }
        },
        Capture("07-confirmed-internal-po-server-receipt", 1440, 1380) {
            HiltechOperationalTheme(dark = false) {
                RenderIssuer(normal.copy(
                    queue = normal.queue.drop(1),
                    selectedCandidateId = null,
                    recentReceipt = syntheticIssuerReceipt(),
                ))
            }
        },
        Capture("08-paged-authorized-issuer-queue", 1440, 2100) {
            HiltechOperationalTheme(dark = false) { RenderIssuer(pagination) }
        },
        Capture("09-existing-PO-verified-in-register", 1440, 1300) {
            HiltechOperationalTheme(dark = false) {
                RenderIssuer(normal.copy(
                    queue = normal.queue.drop(1),
                    selectedCandidateId = null,
                    verifiedIssuedOrder = syntheticIssuedOrder(),
                ))
            }
        },
    )
    cases.forEach { case ->
        val scene = ImageComposeScene(
            width = case.width,
            height = case.height,
            coroutineContext = coroutineContext,
            content = case.content,
        )
        try {
            scene.render().close()
            val png = output.resolve(case.name + ".png")
            scene.render().use { bitmap ->
                check(bitmap.width == case.width && bitmap.height == case.height)
                requireNotNull(bitmap.encodeToData(EncodedImageFormat.PNG)).use { encoded ->
                    require(encoded.bytes.size > 15_000) { "Unexpected tiny render: " + png }
                    Files.write(png, encoded.bytes)
                }
            }
            println("UX128_ISSUER_NATIVE_SCREEN_PASS " + case.name)
        } finally {
            scene.close()
        }
    }
    val images = Files.list(output).use { files ->
        files.filter { it.fileName.toString().endsWith(".png") }.count()
    }
    check(images == cases.size.toLong())
    println("UX128_ISSUER_NATIVE_RENDER_PASS count=" + images +
        " source_composable=true synthetic_source=true issue_action_stub=true")
}

@Composable
private fun RenderIssuer(ui: IssuerLiveUi) {
    val p = LocalHiltechOperationalColors.current
    Box(Modifier.fillMaxSize().background(p.canvas)) {
        PurchaseOrderIssuerLiveScreen(
            ui = ui,
            onRefresh = {},
            onSelectCandidate = {},
            onToggleLifecycle = {},
            selectedAction = {
                // Production uses real PurchaseOrderIssuerAction; this static
                // visual QA deliberately does not instantiate an API client.
                HiltechPanel(Modifier.fillMaxWidth()) {
                    HiltechKeyValue("Next action in authenticated application",
                        "Verify authorized delivery destinations, then explicitly confirm internal issue.")
                }
            },
            lifecycleContent = {},
        )
    }
}

private fun syntheticIssuerCandidate(index: Int): PurchaseOrderIssuerQueueItemDto =
    PurchaseOrderIssuerQueueItemDto(
        candidateId = "11111111-2222-3333-4444-" + index.toString().padStart(12, '0'),
        supplierOrganizationId = "22222222-3333-4444-5555-" +
            index.toString().padStart(12, '0'),
        supplierName = "Synthetic QA supplier " + index.toString().padStart(2, '0'),
        stockItemId = "33333333-4444-5555-6666-" + index.toString().padStart(12, '0'),
        quantity = (12 + index).toString(),
        unitCode = "PCS",
        currency = "EGP",
        total = (1800 + index * 150).toString() + ".00",
        approvalRequestId = "44444444-5555-6666-7777-" +
            index.toString().padStart(12, '0'),
        approvalPolicyVersion = 4,
    )

private fun syntheticIssuerUi() = IssuerLiveUi(
    queue = listOf(
        syntheticIssuerCandidate(1),
        syntheticIssuerCandidate(2),
        syntheticIssuerCandidate(3),
    ),
    selectedCandidateId = syntheticIssuerCandidate(1).candidateId,
    loaded = true,
)

private fun syntheticIssuerReceipt() = PurchaseOrderIssueReceiptDto(
    purchaseOrderId = "55555555-6666-7777-8888-111111111111",
    code = "QA-PO-2026-0001",
    serial = 1,
    state = "ISSUED",
    supplierDispatchState = "NOT_DISPATCHED",
    candidateId = syntheticIssuerCandidate(1).candidateId,
    approvalRequestId = syntheticIssuerCandidate(1).approvalRequestId,
    total = syntheticIssuerCandidate(1).total,
    currency = "EGP",
    replayed = false,
    stockReceived = false,
    supplierNotified = false,
    paymentAuthorized = false,
)

private fun syntheticIssuedOrder() = PurchaseOrderLifecycleSummaryDto(
    id = "55555555-6666-7777-8888-222222222222",
    code = "QA-PO-2026-EXISTING",
    candidateId = syntheticIssuerCandidate(1).candidateId,
    effectiveState = "ISSUED",
    version = 1L,
    supplierDispatchState = "NOT_DISPATCHED",
    total = syntheticIssuerCandidate(1).total,
    currency = "EGP",
)

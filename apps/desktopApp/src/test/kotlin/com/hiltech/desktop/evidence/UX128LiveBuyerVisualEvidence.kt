package com.hiltech.desktop.evidence

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.hiltech.shared.core.design.HiltechOperationalTheme
import com.hiltech.shared.core.design.LocalHiltechOperationalColors
import com.hiltech.shared.core.procurement.BuyerSourcingLiveScreen
import com.hiltech.shared.core.procurement.BuyerSourcingUi
import com.hiltech.shared.core.procurement.SourcingEligibleNeedDto
import com.hiltech.shared.core.procurement.SourcingSupplierCandidateDto
import com.hiltech.shared.core.procurement.SupplierQuoteComparisonDto
import com.hiltech.shared.core.procurement.SupplierQuoteComparisonRowDto
import com.hiltech.shared.core.procurement.SupplierRfqComparisonDto
import com.hiltech.shared.core.procurement.SupplierRfqDto
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.Path

/**
 * Headless native Desktop Compose visual regression of the SAME production
 * BuyerSourcingLiveScreen, with explicitly synthetic typed DTOs. Not network
 * integration, not physical Android or real purchasing acceptance.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() = runBlocking<Unit> {
    val output = Path.of(
        System.getenv("HILTECH_UX128_SOURCING_RENDER_DIR") ?:
            "build/reports/ux128-live-sourcing",
    )
    Files.createDirectories(output)

    data class Capture(
        val name: String,
        val width: Int,
        val height: Int,
        val content: @Composable () -> Unit,
    )
    val normal = fixture()
    val pagedNeeds = (1..11).map { index ->
        normal.needs.first().copy(
            requirementId = "11111111-2222-3333-4444-" + index.toString().padStart(12, '0'),
            stockItemId = "88888888-7777-6666-5555-" + index.toString().padStart(12, '0'),
            requestedQuantity = index.toString(),
        )
    }
    val cases = listOf(
        Capture("01-windows-real-mounted-screen", 1440, 1080) {
            HiltechOperationalTheme(dark = false) { Ui(normal) }
        },
        Capture("02-windows-compact-adaptive", 830, 1100) {
            HiltechOperationalTheme(dark = false) { Ui(normal) }
        },
        Capture("03-windows-dark", 1440, 1080) {
            HiltechOperationalTheme(dark = true) { Ui(normal) }
        },
        Capture("04-windows-high-contrast", 1440, 1080) {
            HiltechOperationalTheme(dark = true, highContrast = true) { Ui(normal) }
        },
        Capture("05-current-source-revoked", 1440, 1080) {
            HiltechOperationalTheme(dark = false) {
                Ui(normal.copy(
                    comparison = normal.comparison?.copy(
                        rfq = normal.comparison!!.rfq.copy(sourceCurrent = false),
                    ),
                ))
            }
        },
        Capture("06-scaled-text-180pct", 1440, 1400) {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.8f)) {
                HiltechOperationalTheme(dark = false) { Ui(normal) }
            }
        },
        Capture("07-windows-full-comparison-and-inspector", 1440, 2000) {
            HiltechOperationalTheme(dark = false) { Ui(normal) }
        },
        Capture("08-paged-authorized-needs", 1440, 2400) {
            HiltechOperationalTheme(dark = false) {
                Ui(normal.copy(
                    needs = pagedNeeds,
                    selectedRequirementId = pagedNeeds.first().requirementId,
                    rounds = emptyList(),
                    selectedRfqId = null,
                    comparison = null,
                    selectedQuoteId = null,
                ))
            }
        },
    )
    cases.forEach { case ->
        val scene = ImageComposeScene(
            width = case.width, height = case.height,
            coroutineContext = coroutineContext, content = case.content,
        )
        try {
            repeat(2) { scene.render().close() }
            val target = output.resolve("${case.name}.png")
            scene.render().use { bitmap ->
                check(bitmap.width == case.width && bitmap.height == case.height)
                requireNotNull(bitmap.encodeToData(EncodedImageFormat.PNG)).use { encoded ->
                    check(encoded.bytes.size > 15000) {
                        "Suspiciously small Compose image $target"
                    }
                    Files.write(target, encoded.bytes)
                }
            }
            println("HUX128_LIVE_BUYER_RENDER_PASS ${case.name} ${case.width}x${case.height}")
        } finally {
            scene.close()
        }
    }
    check(Files.list(output).use { list ->
        list.filter { it.fileName.toString().endsWith(".png") }.count()
    } == cases.size.toLong())
    println("HUX128_LIVE_BUYER_VISUAL_SET_PASS count=${cases.size} production_composable=true synthetic_source=true")
}

@Composable
private fun Ui(ui: BuyerSourcingUi) {
    val palette = LocalHiltechOperationalColors.current
    Box(modifier = Modifier.fillMaxSize().background(palette.canvas)) {
      BuyerSourcingLiveScreen(
        ui = ui,
        onRefresh = {},
        onSelectRequirement = {},
        onOpenRfq = {},
        onCompareRfq = {},
        onSelectQuote = {},
        onToggleCapture = {},
        quoteCaptureContent = {},
        purchaseCandidateContent = {},
        canCapture = false,
      )
    }
}

private fun fixture(): BuyerSourcingUi {
    val need = SourcingEligibleNeedDto(
        requirementId = "11111111-2222-3333-4444-555555555555",
        technicalVersion = 4,
        stockItemId = "88888888-7777-6666-5555-444444444444",
        requestedQuantity = "18", unitCode = "PCS",
        technicalState = "TECHNICALLY_READY",
        sourceCurrent = true, sourcingRoundExists = true,
    )
    val oldNeed = SourcingEligibleNeedDto(
        requirementId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        technicalVersion = 2,
        stockItemId = "99999999-8888-7777-6666-555555555555",
        requestedQuantity = "30", unitCode = "PCS",
        technicalState = "REVIEW_REQUESTED",
        sourceCurrent = false, sourcingRoundExists = false,
    )
    val rfq = SupplierRfqDto(
        id = "33333333-2222-1111-0000-999999999999",
        requirementId = need.requirementId,
        capturedRequirementVersion = 4, capturedQuantity = "18",
        unitCode = "PCS", sourceCurrent = true,
        quoteCount = 3, purchaseAuthorized = false,
    )
    fun quote(
        id: String, supplier: String, ref: String, total: String,
        currency: String, decision: String, issues: List<String>,
        days: Int?, unitPrice: String, tax: String,
    ) = SupplierQuoteComparisonRowDto(
        quoteId = id, supplierOrganizationId = supplier,
        revisionNumber = 2, quoteReference = ref,
        currency = currency, quantity = "18", unitCode = "PCS",
        unitPrice = unitPrice, taxAmount = tax,
        total = total, deliveryDays = days,
        evidenceRef = "evidence-$id",
        evidenceSha256 = MessageDigest.getInstance("SHA-256")
            .digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) },
        technicalCompliance = decision, issues = issues,
    )
    val a = "11111111-aaaa-2222-bbbb-333333333333"
    val b = "22222222-aaaa-3333-bbbb-444444444444"
    val c = "33333333-aaaa-4444-bbbb-555555555555"
    val rows = listOf(
        quote("f0000001-2222-3333-4444-555555555555", a, "QA-ALPHA-2",
            "17100.00", "EGP", "COMPLIANT", emptyList(), 8, "950.00", "0.00"),
        quote("f0000002-2222-3333-4444-555555555555", b, "QA-BETA-2",
            "16550.00", "EGP", "COMPLIANT", listOf("Delivery terms need clarification"), 5, "900.00", "350.00"),
        quote("f0000003-2222-3333-4444-555555555555", c, "QA-GAMMA-2",
            "380.00", "USD", "REVIEW_REQUIRED", listOf("Mixed currency"), null, "20.00", "20.00"),
    )
    val compared = SupplierRfqComparisonDto(
        rfq = rfq,
        comparison = SupplierQuoteComparisonDto(
            expectedQuantity = "18", expectedUnitCode = "PCS",
            evaluatedAt = "2026-11-22T16:00:00Z",
            latestBySupplier = rows,
            currencies = listOf("EGP", "USD"),
            allQuotesDirectlyComparable = false,
            supplierSelected = false, purchaseAuthorized = false,
        ),
        allRevisionCount = 5, recordedSupplierCount = 3,
    )
    return BuyerSourcingUi(
        organizationId = "synthetic-qa-org",
        needs = listOf(need, oldNeed),
        suppliers = listOf(
            SourcingSupplierCandidateDto(a, "Supplier ALPHA / QA", 2),
            SourcingSupplierCandidateDto(b, "Supplier BETA / QA", 4),
            SourcingSupplierCandidateDto(c, "Supplier GAMMA / QA", 1),
        ),
        rounds = listOf(rfq),
        selectedRequirementId = need.requirementId,
        selectedRfqId = rfq.id,
        comparison = compared,
        selectedQuoteId = rows[1].quoteId,
        fetching = false, busy = false, loaded = true,
        error = null, message = null, showCapture = false,
    )
}

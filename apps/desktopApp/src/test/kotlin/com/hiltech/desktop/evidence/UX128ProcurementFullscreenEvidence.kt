package com.hiltech.desktop.evidence

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import com.hiltech.shared.core.procurement.PurchaseRequirementDraftDto
import com.hiltech.shared.core.procurement.PurchaseRequirementReviewPreviewDto
import com.hiltech.shared.core.procurement.SupplierQuoteComparisonDto
import com.hiltech.shared.core.procurement.SupplierQuoteComparisonRowDto
import com.hiltech.shared.core.procurement.SupplierRfqComparisonDto
import com.hiltech.shared.core.procurement.SupplierRfqDto
import com.hiltech.shared.core.ux.procurement.HuxBuyerDirection
import com.hiltech.shared.core.ux.procurement.HuxBuyerScreen
import com.hiltech.shared.core.ux.procurement.HuxBuyerStudy
import com.hiltech.shared.core.ux.procurement.HuxFieldRequirementScreen
import com.hiltech.shared.core.ux.procurement.HuxFieldStudy
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path

/** QA-only: true Compose rendering, synthetic source-shaped Phase7 S01/S02/S03 DTOs. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() = runBlocking<Unit> {
    val output = Path.of(
        System.getenv("HILTECH_UX128_P7_SCREENS_DIR") ?: "build/reports/ux128-procurement-fullscreen",
    )
    Files.createDirectories(output)
    data class Study(val name: String, val width: Int, val height: Int, val content: @androidx.compose.runtime.Composable () -> Unit)
    val buyer = buyerStudy()
    val cases = listOf(
        Study("01-buyer-decision-workbench", 1440, 940, {
            HuxBuyerScreen(buyer, HuxBuyerDirection.DECISION_FIRST, "quote-01", onSelectQuote = {})
        }),
        Study("02-buyer-quote-ledger", 1440, 940, {
            HuxBuyerScreen(buyer, HuxBuyerDirection.COMPARISON_FIRST, "quote-02", onSelectQuote = {})
        }),
        Study("03-buyer-stale-source", 1440, 940, {
            HuxBuyerScreen(
                buyer.copy(sourcing = buyer.sourcing.copy(rfq = buyer.sourcing.rfq.copy(sourceCurrent = false))),
                HuxBuyerDirection.DECISION_FIRST, "quote-03", onSelectQuote = {},
            )
        }),
        Study("04-field-requirement-stale", 430, 960, {
            HuxFieldRequirementScreen(fieldStudy(current = false, visible = false))
        }),
        Study("05-field-requirement-current", 430, 960, {
            HuxFieldRequirementScreen(fieldStudy(current = true, visible = true))
        }),
    )
    cases.forEach { test ->
        val scene = ImageComposeScene(
            width = test.width, height = test.height,
            coroutineContext = coroutineContext, content = test.content,
        )
        try {
            repeat(2) { scene.render().close() }
            val dest = output.resolve("${test.name}.png")
            scene.render().use { bitmap ->
                check(bitmap.width == test.width && bitmap.height == test.height)
                requireNotNull(bitmap.encodeToData(EncodedImageFormat.PNG)).use { png ->
                    check(png.bytes.size > 12_000) { "Suspiciously small Compose image: $dest" }
                    Files.write(dest, png.bytes)
                }
            }
            println("UX128_P7_RENDER_PASS ${test.name} ${test.width}x${test.height} ${Files.size(dest)}")
        } finally {
            scene.close()
        }
    }
    val count = Files.list(output).use { files ->
        files.filter { it.fileName.toString().endsWith(".png") }.count()
    }
    check(count == cases.size.toLong())
    println("UX128_P7_GALLERY_PASS screenshots=$count actual_phase7_dto=true synthetic=true")
}

private fun buyerStudy(): HuxBuyerStudy {
    fun row(
        id: String, supplier: String, ref: String, total: String,
        currency: String, delivery: Int, technical: String, issues: List<String>,
    ) = SupplierQuoteComparisonRowDto(
        quoteId = id, supplierOrganizationId = supplier,
        revisionNumber = if(id == "quote-02") 3 else 2,
        quoteReference = ref, currency = currency,
        quantity = "18", unitCode = "PCS",
        unitPrice = if (currency == "EGP") "760.00" else "19.00",
        taxAmount = if (currency == "EGP") "650.00" else "0.00",
        total = total, deliveryDays = delivery, validUntil = "2026-12-30",
        evidenceRef = "qa-evidence-$id", evidenceSha256 = "qa-sha-256-$id",
        technicalCompliance = technical, issues = issues,
    )
    val rows = listOf(
        row("quote-01", "synthetic-supplier-a", "QA-A-002", "14330.00", "EGP", 8, "COMPLIANT", emptyList()),
        row("quote-02", "synthetic-supplier-b", "QA-B-003", "16550.00", "EGP", 5, "COMPLIANT", listOf("Delivery term requires clarification")),
        row("quote-03", "synthetic-supplier-c", "QA-C-002", "380.00", "USD", 12, "REVIEW_REQUIRED", listOf("Different currency", "Technical assessment incomplete")),
    )
    return HuxBuyerStudy(
        sourcing = SupplierRfqComparisonDto(
            rfq = SupplierRfqDto(
                id = "rfq-qa", requirementId = "req-qa",
                capturedRequirementVersion = 4, capturedQuantity = "18",
                unitCode = "PCS", sourceCurrent = true, quoteCount = 3,
                purchaseAuthorized = false,
            ),
            comparison = SupplierQuoteComparisonDto(
                expectedQuantity = "18", expectedUnitCode = "PCS",
                evaluatedAt = "2026-11-22T12:10:00Z",
                latestBySupplier = rows, currencies = listOf("EGP", "USD"),
                allQuotesDirectlyComparable = false, supplierSelected = false,
                purchaseAuthorized = false,
            ),
            allRevisionCount = 7, recordedSupplierCount = 3,
        ),
        supplierNames = mapOf(
            "synthetic-supplier-a" to "Supplier A / QA",
            "synthetic-supplier-b" to "Supplier B / QA",
            "synthetic-supplier-c" to "Supplier C / QA",
        ),
        workLabel = "WO-QA-05 / fiber site",
        itemLabel = "ODF patch assembly / QA",
        scopeLabel = "SYNTHETIC COMPANY / BUYER VIEW",
        online = true,
    )
}

private fun fieldStudy(current: Boolean, visible: Boolean): HuxFieldStudy {
    val draft = PurchaseRequirementDraftDto(
        id = "req-qa", workOrderId = "wo-qa", workOrderVersionAtCreation = 4,
        stockItemId = "item-qa", stockItemVersionAtCreation = 7,
        requestedQuantity = "18", unitCode = "PCS",
        justification = "مواد لازمة لاستكمال عمل موقع الاختبار",
        state = "SUBMITTED",
        assessmentCode = if (visible) "INSUFFICIENT_STOCK" else "WAREHOUSE_REVIEW_REQUIRED",
        observedFreeQuantity = if (visible) "6" else null,
        assessedAt = "2026-11-22T12:10:00Z", createdBy = "qa-actor",
        createdAt = "2026-11-22T11:00:00Z", sourceCurrent = current,
        assessmentRequiresRefresh = !current, version = 4,
        canSubmit = false, canRevise = false, canWithdraw = false,
        lastReviewReason = "مراجعة فنية مرتبطة بأمر العمل",
    )
    val preview = PurchaseRequirementReviewPreviewDto(
        requirementId = "req-qa", state = "SUBMITTED",
        currentWorkOrderVersion = 4, currentStockItemVersion = 7,
        sourceCurrent = current,
        assessmentCode = draft.assessmentCode,
        visibleFreeQuantity = if (visible) "6" else null,
        assessedAt = "2026-11-22T12:10:00Z",
        approvalOrPurchaseAuthorized = false,
    )
    return HuxFieldStudy(
        requirement = draft, preview = preview,
        workLabel = "WO-QA-05 / منطقة الاختبار", itemLabel = "ODF patch assembly",
        online = true,
    )
}

package com.hiltech.shared.core.ux.procurement

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hiltech.shared.core.procurement.PurchaseRequirementDraftDto
import com.hiltech.shared.core.procurement.PurchaseRequirementReviewPreviewDto
import com.hiltech.shared.core.procurement.SupplierQuoteComparisonRowDto
import com.hiltech.shared.core.procurement.SupplierRfqComparisonDto

/**
 * Source-typed UX research on Phase7 S03. This is not mounted in HiltechShell.
 * Never grants buyer, PO, invoice, approval, payment or inventory authority.
 * Host must provide already authorized, source-current data and wire actions
 * only through the existing server-enforced contracts.
 */
enum class HuxBuyerDirection { DECISION_FIRST, COMPARISON_FIRST }
data class HuxBuyerStudy(
    val sourcing: SupplierRfqComparisonDto,
    val supplierNames: Map<String, String>,
    val workLabel: String,
    val itemLabel: String,
    val scopeLabel: String,
    val online: Boolean,
)
data class HuxFieldStudy(
    val requirement: PurchaseRequirementDraftDto,
    val preview: PurchaseRequirementReviewPreviewDto?,
    val workLabel: String,
    val itemLabel: String,
    val online: Boolean,
)
fun HuxBuyerStudy.canCompareTotals(): Boolean =
    sourcing.rfq.sourceCurrent &&
        sourcing.comparison.allQuotesDirectlyComparable &&
        sourcing.comparison.currencies.size == 1 &&
        sourcing.comparison.latestBySupplier.isNotEmpty()

/** Typed source hints only; never inferred from a quote's apparent attractiveness. */
fun HuxBuyerStudy.isPurchaseAuthorizedHint(): Boolean =
    sourcing.rfq.purchaseAuthorized && sourcing.comparison.purchaseAuthorized

fun HuxFieldStudy.canShowFreeStock(): Boolean =
    online && requirement.sourceCurrent &&
        !requirement.assessmentRequiresRefresh &&
        requirement.assessmentCode != "WAREHOUSE_REVIEW_REQUIRED" &&
        requirement.observedFreeQuantity != null &&
        preview?.sourceCurrent == true &&
        preview.visibleFreeQuantity != null

fun HuxFieldStudy.canOfferSubmit(wired: Boolean): Boolean =
    wired && online && requirement.state == "DRAFT" &&
        requirement.sourceCurrent && requirement.canSubmit

// Temporary study palette: not HILTECH production tokens.
private val paper = Color(0xFFF2F1EC)
private val ink = Color(0xFF18252C)
private val muted = Color(0xFF66767B)
private val stroke = Color(0xFFD3DADA)
private val green = Color(0xFF226C64)
private val warm = Color(0xFF9B5F37)

@Composable
fun HuxStatusLabel(value: String, positive: Boolean) {
    Surface(color = if (positive) Color(0xFFE5F0EB) else Color(0xFFFFF0E3),
        shape = RoundedCornerShape(5.dp)) {
        Text(value, Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            color = if (positive) green else warm)
    }
}
@Composable
fun HuxLabelValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, fontSize = 11.sp, color = muted)
        Text(value, fontSize = 14.sp, color = ink, fontWeight = FontWeight.Medium)
    }
}
@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier, color = Color.White, shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, stroke)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            content()
        }
    }
}
@Composable
private fun ContextPanel(b: HuxBuyerStudy) {
    Panel {
        Text("SOURCE / PROVENANCE", color = green, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        HuxLabelValue("WorkOrder", b.workLabel)
        HuxLabelValue("StockItem", b.itemLabel)
        HuxLabelValue("Requirement version", b.sourcing.rfq.capturedRequirementVersion.toString())
        HuxLabelValue("Evidence", "${b.sourcing.recordedSupplierCount} suppliers · ${b.sourcing.allRevisionCount} recorded quote revisions")
        HuxStatusLabel(
            if (b.sourcing.rfq.sourceCurrent) "Current source checked" else "Source changed — refresh",
            b.sourcing.rfq.sourceCurrent,
        )
    }
}
@Composable
private fun OfferRow(
    q: SupplierQuoteComparisonRowDto,
    b: HuxBuyerStudy,
    selected: Boolean,
    dense: Boolean,
    onSelect: () -> Unit,
) {
    val supplier = b.supplierNames[q.supplierOrganizationId] ?: "Supplier label unavailable"
    val clean = q.technicalCompliance == "COMPLIANT" && q.issues.isEmpty()
    Surface(
        Modifier.fillMaxWidth()
            .semantics { contentDescription = "Inspect quote ${q.quoteReference}, $supplier" }
            .clickable(onClick = onSelect),
        color = if (selected) Color(0xFFE7F0ED) else Color.White,
        shape = RoundedCornerShape(if (dense) 7.dp else 12.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) green else stroke),
    ) {
        Column(Modifier.padding(if (dense) 12.dp else 17.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(supplier, color = ink, fontSize = if (dense) 14.sp else 17.sp,
                        fontWeight = FontWeight.SemiBold)
                    Text("Quote ${q.quoteReference} · v${q.revisionNumber}", color = muted, fontSize = 11.sp)
                }
                HuxStatusLabel(if (clean) "Technically compliant" else "Needs technical review", clean)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                HuxLabelValue("Submitted total", "${q.total} ${q.currency}", Modifier.weight(1f))
                HuxLabelValue("Delivery", q.deliveryDays?.let { "$it days" } ?: "Not specified", Modifier.weight(.6f))
                if (!dense) HuxLabelValue("Issues", q.issues.size.toString(), Modifier.weight(.4f))
            }
            if (q.issues.isNotEmpty()) Text(q.issues.take(2).joinToString(" · "),
                color = warm, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
@Composable
private fun OfferInspector(
    b: HuxBuyerStudy,
    selected: SupplierQuoteComparisonRowDto?,
    onEvidence: ((String) -> Unit)?,
) {
    Surface(color = Color(0xFFE5E9E7), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("EVIDENCE INSPECTOR", color = green, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            if (selected == null) {
                Text("Choose an offer to read its source facts; selecting it is not a supplier award.",
                    color = muted, fontSize = 14.sp)
            } else {
                Text(b.supplierNames[selected.supplierOrganizationId] ?: "Unresolved supplier",
                    fontSize = 22.sp, fontWeight = FontWeight.Bold, color = ink)
                HuxLabelValue("Quote reference", selected.quoteReference)
                HuxLabelValue("Captured quantity", "${selected.quantity} ${selected.unitCode}")
                HuxLabelValue("Unit price", "${selected.unitPrice} ${selected.currency}")
                HuxLabelValue("Tax", "${selected.taxAmount} ${selected.currency}")
                HuxLabelValue("Technical decision", selected.technicalCompliance)
                if (selected.issues.isNotEmpty()) {
                    HuxStatusLabel("Exceptions: ${selected.issues.size}", false)
                    selected.issues.forEach { Text("• $it", color = warm, fontSize = 12.sp) }
                }
                HuxLabelValue("Recorded evidence", "Quote revision ${selected.revisionNumber}")
                OutlinedButton(onClick = { onEvidence?.invoke(selected.quoteId) },
                    enabled = b.online && onEvidence != null, modifier = Modifier.fillMaxWidth()) {
                    Text("Inspect authorized source")
                }
            }
            Text("No supplier award, PO issue, approval, or payment in this study.",
                fontSize = 11.sp, color = muted)
        }
    }
}

/**
 * Two complete cross-platform buyer compositions, not two sets of fake business
 * data. Caller controls selection by stable quoteId and must clear it on a
 * change of organization/RFQ/permission. Only quote inspection is interactive.
 */
@Composable
fun HuxBuyerScreen(
    study: HuxBuyerStudy,
    direction: HuxBuyerDirection,
    selectedQuoteId: String?,
    onSelectQuote: (String) -> Unit,
    onEvidence: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val data = study.sourcing
    val selected = data.comparison.latestBySupplier.firstOrNull { it.quoteId == selectedQuoteId }
    val directComparison = study.canCompareTotals()
    Column(modifier.fillMaxSize().background(paper).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("HILTECH    /    PROCUREMENT", color = green, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text("Authorized scope: ${study.scopeLabel}", color = muted, fontSize = 11.sp)
            }
            HuxStatusLabel("DESIGN STUDY · NO PURCHASE AUTHORITY", false)
        }
        if (direction == HuxBuyerDirection.DECISION_FIRST) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(Modifier.width(215.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                    Surface(color = ink, shape = RoundedCornerShape(13.dp)) {
                        Column(Modifier.fillMaxWidth().padding(17.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            Text("01    NEED", color = Color(0xFFC1CECC), fontSize = 11.sp)
                            Text(study.workLabel, color = Color.White, fontSize = 17.sp,
                                fontWeight = FontWeight.Bold)
                            Text(study.itemLabel, color = Color(0xFFE1E9E7), fontSize = 13.sp)
                            Text("02    QUOTES", color = Color(0xFFC1CECC), fontSize = 11.sp)
                            Text("03    EVIDENCE", color = Color(0xFFC1CECC), fontSize = 11.sp)
                            Text("04    PURCHASE / NOT ISSUED", color = Color(0xFFC1CECC), fontSize = 11.sp)
                        }
                    }
                    ContextPanel(study)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    TitleBlock("DECISION WORKBENCH", "What needs a decision?",
                        "Understand blockers and source currency before reading offers.")
                    SourceWarning(study, directComparison)
                    Text("SOURCE-CAPTURED OFFERS", color = green, fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    if(data.comparison.latestBySupplier.isEmpty()) {
                        Text("No authorized quote data was returned.", color = muted, fontSize = 14.sp)
                    }
                    data.comparison.latestBySupplier.forEach { quote ->
                        OfferRow(quote, study, selectedQuoteId == quote.quoteId,
                            dense = false, onSelect = { onSelectQuote(quote.quoteId) })
                    }
                    Text("Evaluated at: ${data.comparison.evaluatedAt} · source may change",
                        color = muted, fontSize = 11.sp)
                }
                Column(Modifier.width(300.dp).verticalScroll(rememberScrollState())) {
                    OfferInspector(study, selected, onEvidence)
                }
            }
        } else {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TitleBlock("SOURCE LEDGER", "Compare the offers",
                        "Cost, technical judgment and evidence remain separate source facts.")
                    SourceWarning(study, directComparison)
                    Surface(color = ink, shape = RoundedCornerShape(8.dp)) {
                        Row(Modifier.fillMaxWidth().padding(13.dp),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("SUPPLIER / QUOTE / VERSION", color = Color.White, fontSize = 11.sp)
                            Text("TOTAL / DELIVERY / REVIEW", color = Color.White, fontSize = 11.sp)
                        }
                    }
                    data.comparison.latestBySupplier.forEach { quote ->
                        OfferRow(quote, study, selectedQuoteId == quote.quoteId,
                            dense = true, onSelect = { onSelectQuote(quote.quoteId) })
                    }
                    Text("Captured versions: ${data.allRevisionCount} · Timestamp: ${data.comparison.evaluatedAt}",
                        color = muted, fontSize = 11.sp)
                }
                Column(Modifier.width(326.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    ContextPanel(study)
                    OfferInspector(study, selected, onEvidence)
                }
            }
        }
    }
}
@Composable
private fun TitleBlock(kicker: String, title: String, explanation: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(kicker, color = green, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp)
        Text(title, fontSize = 29.sp, color = ink, fontWeight = FontWeight.Bold)
        Text(explanation, fontSize = 13.sp, color = muted)
    }
}
@Composable
private fun SourceWarning(b: HuxBuyerStudy, compare: Boolean) {
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("BEFORE AN ORDER", fontWeight = FontWeight.Bold, color = ink, fontSize = 12.sp)
            HuxStatusLabel(if(compare) "Comparable currency" else "Comparison restricted", compare)
        }
        Text(
            if (!b.sourcing.rfq.sourceCurrent) "Underlying requirement changed. Re-check current versions."
            else if(!compare) "These offers are not directly comparable as a single numeric ranking."
            else "Totals are readable side by side, not proof of a preferred supplier.",
            color = muted, fontSize = 13.sp,
        )
        if(!b.isPurchaseAuthorizedHint()) Text(
            "No purchase approval or issued PO can be inferred from this comparison.",
            color = warm, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        )
    }
}

/** Read-only mobile work-item UX, grounded in the actual S01 Draft + Preview DTOs. */
@Composable
fun HuxFieldRequirementScreen(
    study: HuxFieldStudy,
    onRefresh: (() -> Unit)? = null,
    onSubmit: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val need = study.requirement
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(modifier.fillMaxSize().background(paper)
            .verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("HILTECH   /   العمل الميداني", color = green, fontSize = 12.sp,
                fontWeight = FontWeight.Bold)
            Text("احتياج أمر العمل", color = ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("طلب احتياج ومراجعة فنية، وليس شراءً أو حجزًا للمخزون.",
                color = muted, fontSize = 13.sp)
            Surface(color = ink, shape = RoundedCornerShape(13.dp)) {
                Column(Modifier.fillMaxWidth().padding(17.dp),
                    verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    Text("أمر العمل", fontSize = 12.sp, color = Color(0xFFC2D0CD))
                    Text(study.workLabel, color = Color.White, fontSize = 22.sp,
                        fontWeight = FontWeight.Bold)
                    Text(study.itemLabel, color = Color.White, fontSize = 16.sp)
                    Text("الكمية: ${need.requestedQuantity} ${need.unitCode}",
                        color = Color(0xFFE1E8E5), fontSize = 14.sp)
                }
            }
            Panel {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("حالة الطلب", color = ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    HuxStatusLabel(if(need.sourceCurrent) need.state else "المصدر تغيّر",
                        need.sourceCurrent)
                }
                HuxLabelValue("سبب الاحتياج", need.justification)
                HuxLabelValue("الملاحظة الفنية", need.lastReviewReason ?: "لا توجد ملاحظة مراجعة")
                HuxLabelValue("إصدار السجل", need.version.toString())
            }
            Panel {
                Text("المخزون قبل الشراء", color = ink, fontSize = 17.sp,
                    fontWeight = FontWeight.Bold)
                if (study.canShowFreeStock()) {
                    HuxStatusLabel("معلومة إرشادية — ليست حجزًا", true)
                    HuxLabelValue("الكمية الحرة الظاهرة", study.preview?.visibleFreeQuantity ?: "غير متاحة")
                } else {
                    HuxStatusLabel("يلزم التحقق من المخزون", false)
                    Text("البيانات القديمة أو المحجوبة لا تعني أن الرصيد صفر.",
                        color = muted, fontSize = 13.sp)
                }
                Text("الحالة المصدرية: ${need.assessmentCode}", color = muted, fontSize = 11.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedButton(onClick = { onRefresh?.invoke() },
                    enabled = study.online && onRefresh != null,
                    modifier = Modifier.weight(1f)) { Text("تحديث", fontSize = 13.sp) }
                Button(onClick = { onSubmit?.invoke() },
                    enabled = study.canOfferSubmit(onSubmit != null),
                    modifier = Modifier.weight(1f)) { Text("إرسال للمراجعة", fontSize = 13.sp) }
            }
            Text("الموافقة النهائية والتحقق من الصلاحيات يتمان على السيرفر، وليس من زر بالواجهة.",
                color = muted, fontSize = 11.sp)
            Spacer(Modifier.height(14.dp))
        }
    }
}

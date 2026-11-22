package com.hiltech.desktop.evidence

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
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
import com.hiltech.shared.core.ux.HuxDensityCandidate
import com.hiltech.shared.core.ux.HuxIconClass
import com.hiltech.shared.core.ux.HuxIconIntent
import com.hiltech.shared.core.ux.HuxLocale
import com.hiltech.shared.core.ux.HuxStatus
import com.hiltech.shared.core.ux.HuxStatusAxis
import com.hiltech.shared.core.ux.HuxThemeCandidate
import com.hiltech.shared.core.ux.HuxVisualIntent
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path

/**
 * QA-only candidate gallery. No production theme, no server connection,
 * no authoritative status inference, no font redistribution, no Robot/window capture.
 * Glyph placeholders display role codes, not selected icon assets.
 */
private enum class Study { STATUS, TYPOGRAPHY, ICONS }

private data class Spec(
    val name: String,
    val width: Int,
    val height: Int,
    val locale: HuxLocale,
    val theme: HuxThemeCandidate,
    val density: HuxDensityCandidate,
    val study: Study,
)

@OptIn(ExperimentalComposeUiApi::class)
fun main() = runBlocking<Unit> {
    val dir = Path.of(
        System.getenv("HILTECH_UX128_SYSTEM_DIR") ?: "build/reports/ux128-system-specimens",
    )
    Files.createDirectories(dir)
    val specimens = listOf(
        Spec("01-phone-ar-light-status", 430, 960, HuxLocale.AR, HuxThemeCandidate.LIGHT, HuxDensityCandidate.FIELD_TOUCH, Study.STATUS),
        Spec("02-phone-ar-dark-status", 430, 960, HuxLocale.AR, HuxThemeCandidate.DARK, HuxDensityCandidate.FIELD_TOUCH, Study.STATUS),
        Spec("03-phone-ar-light-type", 430, 960, HuxLocale.AR, HuxThemeCandidate.LIGHT, HuxDensityCandidate.COMFORTABLE, Study.TYPOGRAPHY),
        Spec("04-phone-ar-light-icons", 430, 960, HuxLocale.AR, HuxThemeCandidate.LIGHT, HuxDensityCandidate.FIELD_TOUCH, Study.ICONS),
        Spec("05-windows-en-light-status", 1180, 880, HuxLocale.EN, HuxThemeCandidate.LIGHT, HuxDensityCandidate.DESKTOP_COMPACT, Study.STATUS),
        Spec("06-windows-en-dark-status", 1180, 880, HuxLocale.EN, HuxThemeCandidate.DARK, HuxDensityCandidate.DESKTOP_COMPACT, Study.STATUS),
        Spec("07-windows-ar-high-contrast", 1180, 880, HuxLocale.AR, HuxThemeCandidate.HIGH_CONTRAST, HuxDensityCandidate.DESKTOP_COMPACT, Study.STATUS),
        Spec("08-windows-ar-light-type", 1180, 880, HuxLocale.AR, HuxThemeCandidate.LIGHT, HuxDensityCandidate.DESKTOP_COMPACT, Study.TYPOGRAPHY),
        Spec("09-windows-en-light-icons", 1180, 880, HuxLocale.EN, HuxThemeCandidate.LIGHT, HuxDensityCandidate.DESKTOP_COMPACT, Study.ICONS),
    )
    for (spec in specimens) {
        val scene = ImageComposeScene(width = spec.width, height = spec.height, coroutineContext = coroutineContext) {
            CandidateScreen(spec)
        }
        try {
            repeat(2) { scene.render().close() }
            val output = dir.resolve("${spec.name}.png")
            scene.render().use { bitmap ->
                check(bitmap.width == spec.width && bitmap.height == spec.height)
                requireNotNull(bitmap.encodeToData(EncodedImageFormat.PNG)).use { png ->
                    check(png.bytes.size > 8_000) { "Suspiciously small native Compose PNG: $output" }
                    Files.write(output, png.bytes)
                }
            }
            println("UX128_SYSTEM_RENDER_PASS name=${spec.name} size=${spec.width}x${spec.height} bytes=${Files.size(output)}")
        } finally {
            scene.close()
        }
    }
    val names = Files.list(dir).use { files -> files.filter { it.fileName.toString().endsWith(".png") }.count() }
    check(names == specimens.size.toLong()) { "Expected ${specimens.size} candidate screenshots, got $names" }
    println("UX128_SYSTEM_GALLERY_PASS screenshots=${specimens.size} synthetic=true render=ImageComposeScene")
}

@Composable
private fun CandidateScreen(s: Spec) {
    val rtl = s.locale == HuxLocale.AR
    val high = s.theme == HuxThemeCandidate.HIGH_CONTRAST
    val dark = s.theme == HuxThemeCandidate.DARK || high
    val background = if (high) Color(0xFF050505) else if (dark) Color(0xFF151A20) else Color(0xFFF3F3F0)
    val ink = if (dark) Color(0xFFF7F7F3) else Color(0xFF1F282D)
    val muted = if (dark) Color(0xFFBFC6CB) else Color(0xFF56626A)
    val card = if (high) Color(0xFF080808) else if (dark) Color(0xFF242A32) else Color.White
    val border = if (high) Color.White else if (dark) Color(0xFF45515B) else Color(0xFFD6DCD9)
    val gap = s.density.baseGapDp.dp
    val mobile = s.width < 600
    CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            Column(
                Modifier.fillMaxSize().background(background).padding(if (mobile) 20.dp else 32.dp),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                Text("HILTECH / UX-128", color = muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (rtl) when (s.study) {
                        Study.STATUS -> "لغة الحالات التشغيلية"
                        Study.TYPOGRAPHY -> "اختبار الخطوط والاتجاهات"
                        Study.ICONS -> "الأيقونات والكثافة"
                    } else when (s.study) {
                        Study.STATUS -> "Operational state language"
                        Study.TYPOGRAPHY -> "Type & bidirectional data"
                        Study.ICONS -> "Icon meanings & density"
                    },
                    color = ink, fontSize = if (mobile) 24.sp else 30.sp, fontWeight = FontWeight.Bold,
                )
                Text(
                    if (rtl) "تجربة Compose أصلية • بيانات افتراضية • ليست واجهة الإنتاج"
                    else "Native Compose study • synthetic content • not production UI",
                    fontSize = 12.sp, color = muted,
                )
                Text("${s.theme} / ${s.density} / ${s.width}×${s.height}", color = muted, fontSize = 11.sp)
                when (s.study) {
                    Study.STATUS -> StateSpecimen(s, mobile, card, border, ink, muted, high)
                    Study.TYPOGRAPHY -> TypeSpecimen(s, mobile, card, border, ink, muted)
                    Study.ICONS -> IconSpecimen(s, mobile, card, border, ink, muted)
                }
            }
    }
}

private val sampleStatuses = listOf(
    HuxStatus.ACTION_WAITING_ON_YOU,
    HuxStatus.ACTION_WAITING_EXTERNAL,
    HuxStatus.SYNC_LOCAL_ONLY,
    HuxStatus.SYNC_QUEUED,
    HuxStatus.SYNC_CONFIRMED,
    HuxStatus.SYNC_CONFLICT,
    HuxStatus.SYNC_PROVIDER_UNAVAILABLE,
    HuxStatus.HEALTH_CRITICAL,
    HuxStatus.RESULT_UNVERIFIED,
    HuxStatus.RESULT_PARTIAL,
    HuxStatus.LIFECYCLE_ACTIVE,
    HuxStatus.LIFECYCLE_CLOSED,
)

@Composable
private fun StateSpecimen(
    s: Spec, mobile: Boolean, card: Color, border: Color, ink: Color, muted: Color, high: Boolean,
) {
    val space = s.density.baseGapDp.dp
    if (mobile) {
        sampleStatuses.take(9).forEach { state ->
            StateCard(state, s, card, border, ink, muted, high)
        }
    } else {
        val left = sampleStatuses.filterIndexed { index, _ -> index % 2 == 0 }
        val right = sampleStatuses.filterIndexed { index, _ -> index % 2 == 1 }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(space)) {
                left.forEach { StateCard(it, s, card, border, ink, muted, high) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(space)) {
                right.forEach { StateCard(it, s, card, border, ink, muted, high) }
            }
        }
    }
}

@Composable
private fun StateCard(
    status: HuxStatus, spec: Spec, card: Color, border: Color, ink: Color, muted: Color, high: Boolean,
) {
    val p = status.present(spec.locale)
    val tone = if (high) Color.White else when (p.visualIntent) {
        HuxVisualIntent.CRITICAL -> Color(0xFFCF624E)
        HuxVisualIntent.CAUTION -> Color(0xFFAE8043)
        HuxVisualIntent.ATTENTION -> Color(0xFF6986A5)
        HuxVisualIntent.POSITIVE -> Color(0xFF538E79)
        HuxVisualIntent.UNKNOWN -> Color(0xFF8E8997)
        HuxVisualIntent.INFORMATION -> Color(0xFF6F93A9)
        else -> Color(0xFF89989B)
    }
    Row(
        Modifier.fillMaxWidth().border(if (high) 2.dp else 1.dp, border, RoundedCornerShape(12.dp))
            .background(card, RoundedCornerShape(12.dp))
            .heightIn(min = spec.density.rowMinDp.dp)
            .semantics { contentDescription = p.accessibleLabel }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.widthIn(min = 4.dp).heightIn(min = 32.dp).background(tone, RoundedCornerShape(3.dp)))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(p.visibleLabel, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ink)
            Text(
                "${p.axis.label(spec.locale)} · ${p.iconRole.name.replace('_', ' ')}",
                color = muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TypeSpecimen(s: Spec, mobile: Boolean, card: Color, border: Color, ink: Color, muted: Color) {
    // U+2066/U+2069 isolate source LTR identifiers/dates inside Arabic context.
    // This is a specimen-level mitigation. Production needs a reusable audited
    // bidi component and copy/paste, selection and TalkBack validation.
    val samples = listOf(
        "WO-QA-05" to "موعد مراجعة الموقع",
        "FLUKE-01" to "العهدة الحالية",
        "ODF-07 / rack 3" to "حالة المسار",
        "10.24.3.15" to "عنوان الجهاز",
        "2,500.75 EGP" to "التكلفة",
        "2026-11-22 14:30 UTC" to "آخر مزامنة",
        "Waiting on client" to "بانتظار العميل",
    )
    SurfaceGroup(card, border) {
        Text(if(s.locale==HuxLocale.AR) "المعرّفات والأرقام المختلطة" else "Mixed scripts & source identifiers",
            fontWeight = FontWeight.Bold, color = ink, fontSize = if(mobile) 16.sp else 20.sp)
        Text(if(s.locale==HuxLocale.AR) "الخط الحالي: Font fallback الخاص بالمنصة، غير معتمد"
            else "Current platform font fallback — no typeface selected", fontSize = 12.sp, color = muted)
        samples.forEachIndexed { index, sample ->
            val visible = "\u2066${sample.first}\u2069 — ${sample.second}"
            Text(visible, color = ink, fontSize = (if (mobile) 13 else 17).sp,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                maxLines = if (mobile) 2 else 1, overflow = TextOverflow.Visible)
            if (index == 3) Text("─────", color = border, fontSize = 8.sp)
        }
        Text("AR / EN  |  100% scale  |  200% scaling still unverified", color = muted, fontSize = 11.sp)
    }
}

@Composable
private fun IconSpecimen(s: Spec, mobile: Boolean, card: Color, border: Color, ink: Color, muted: Color) {
    val candidates = listOf(
        HuxIconIntent.BACK, HuxIconIntent.SEARCH, HuxIconIntent.CODE_LOOKUP,
        HuxIconIntent.WORK_ORDER, HuxIconIntent.ODF, HuxIconIntent.OTDR,
        HuxIconIntent.FLUKE_TEST, HuxIconIntent.CUSTODY, HuxIconIntent.SYNC_PENDING,
    )
    SurfaceGroup(card, border) {
        Text(if (s.locale==HuxLocale.AR) "المعنى قبل الرسم" else "Meaning before artwork",
            fontWeight = FontWeight.Bold, color = ink, fontSize = 19.sp)
        Text(
            if(s.locale==HuxLocale.AR) "المربعات الرمزية هنا مجرد تمثيل لفئات الأيقونات، وليست أيقونات نهائية"
            else "Letter markers stand for icon categories — no glyph artwork selected",
            color = muted, fontSize = 12.sp,
        )
        candidates.forEach { symbol ->
            Row(Modifier.fillMaxWidth().heightIn(min = s.density.rowMinDp.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val abbreviation = when(symbol.iconClass) {
                    HuxIconClass.ACTION -> "A"
                    HuxIconClass.OBJECT -> "O"
                    HuxIconClass.STATUS -> "S"
                }
                Box(
                    Modifier.widthIn(min = s.density.targetMinDp.dp).heightIn(min = s.density.targetMinDp.dp)
                        .border(1.dp, border, RoundedCornerShape(6.dp))
                        .semantics { contentDescription = symbol.accessibleLabel(s.locale) }
                        .padding(9.dp),
                ) { Text(abbreviation, fontWeight = FontWeight.Bold, color = ink, fontSize = 13.sp) }
                Column(Modifier.weight(1f)) {
                    Text(symbol.accessibleLabel(s.locale), color = ink,
                        fontSize = if(mobile) 13.sp else 16.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "${symbol.iconClass} · mirror RTL=${symbol.mirrorsInRtl}",
                        color = muted, fontSize = 10.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun SurfaceGroup(card: Color, border: Color, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().border(1.dp, border, RoundedCornerShape(12.dp))
            .background(card, RoundedCornerShape(12.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        content()
    }
}

/** Only a QA typography wrapper; uses Compose Foundation and platform font fallback. */
@Composable
private fun Text(
    value: String,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = 14.sp,
    fontWeight: FontWeight? = null,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    BasicText(
        text = value,
        modifier = modifier,
        style = TextStyle(color = color, fontSize = fontSize, fontWeight = fontWeight),
        maxLines = maxLines,
        overflow = overflow,
    )
}

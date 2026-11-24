package com.hiltech.shared.core.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.material3.FilterChip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared operational design-system implementation (not an image/specimen).
 * Semantic roles are consumed by native source-connected Compose surfaces.
 * This is a bounded functional DS foundation, not a product-wide visual freeze.
 * Contrast is enforced by tests, and status meaning never depends on color alone.
 */
@Immutable
data class HiltechOperationalColors(
    val canvas: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val divider: Color,
    val accent: Color,
    val selected: Color,
    val focus: Color,
    val goodSurface: Color,
    val goodText: Color,
    val cautionSurface: Color,
    val cautionText: Color,
    val blockedSurface: Color,
    val blockedText: Color,
)

private val Light = HiltechOperationalColors(
    canvas = Color(0xFFF2F3F0),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFE8ECE9),
    primaryText = Color(0xFF17262B),
    secondaryText = Color(0xFF4A5B60),
    divider = Color(0xFFADB8B8),
    accent = Color(0xFF155B56),
    selected = Color(0xFFDFEDE7),
    focus = Color(0xFF135F91),
    goodSurface = Color(0xFFE3F1E9),
    goodText = Color(0xFF14593E),
    cautionSurface = Color(0xFFFFF0DD),
    cautionText = Color(0xFF784315),
    blockedSurface = Color(0xFFFFE8E8),
    blockedText = Color(0xFF822D29),
)
private val Dark = HiltechOperationalColors(
    canvas = Color(0xFF121A1E),
    surface = Color(0xFF1B282C),
    surfaceElevated = Color(0xFF24363B),
    primaryText = Color(0xFFF4F6F2),
    secondaryText = Color(0xFFD1DEDA),
    divider = Color(0xFF788D8F),
    accent = Color(0xFF80D5B7),
    selected = Color(0xFF244E45),
    focus = Color(0xFF9AD4FB),
    goodSurface = Color(0xFF153F31),
    goodText = Color(0xFFA8F0D3),
    cautionSurface = Color(0xFF503718),
    cautionText = Color(0xFFFFD4A0),
    blockedSurface = Color(0xFF532B2B),
    blockedText = Color(0xFFFFC7BB),
)
private val HighContrast = HiltechOperationalColors(
    canvas = Color(0xFF000000),
    surface = Color(0xFF000000),
    surfaceElevated = Color(0xFF171717),
    primaryText = Color.White,
    secondaryText = Color.White,
    divider = Color.White,
    accent = Color(0xFFADFFE4),
    selected = Color(0xFF004E46),
    focus = Color(0xFFFFFF00),
    goodSurface = Color(0xFF003C27),
    goodText = Color.White,
    cautionSurface = Color(0xFF5D3600),
    cautionText = Color.White,
    blockedSurface = Color(0xFF641313),
    blockedText = Color.White,
)

val LocalHiltechOperationalColors = staticCompositionLocalOf { Light }

object HiltechSpace {
    val xxs: Dp = 4.dp
    val xs: Dp = 8.dp
    val small: Dp = 12.dp
    val medium: Dp = 16.dp
    val large: Dp = 24.dp
    val xlarge: Dp = 32.dp
}
object HiltechShape {
    val small = RoundedCornerShape(6.dp)
    val medium = RoundedCornerShape(10.dp)
    val large = RoundedCornerShape(16.dp)
}

/**
 * Operational text hierarchy shared by both native clients. Use the platform
 * font fallback so Arabic shaping and mixed-language record names stay intact.
 */
private fun operationalText(size: Int, leading: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontSize = size.sp, lineHeight = leading.sp, fontWeight = weight, letterSpacing = 0.sp)

private val OperationalTypography = Typography(
    displayLarge = operationalText(44, 56, FontWeight.Bold),
    displayMedium = operationalText(36, 48, FontWeight.Bold),
    displaySmall = operationalText(30, 40, FontWeight.Bold),
    headlineLarge = operationalText(28, 38, FontWeight.Bold),
    headlineMedium = operationalText(24, 34, FontWeight.SemiBold),
    headlineSmall = operationalText(22, 30, FontWeight.SemiBold),
    titleLarge = operationalText(20, 28, FontWeight.SemiBold),
    titleMedium = operationalText(16, 24, FontWeight.SemiBold),
    titleSmall = operationalText(14, 21, FontWeight.SemiBold),
    bodyLarge = operationalText(16, 24),
    bodyMedium = operationalText(14, 22),
    bodySmall = operationalText(12, 19),
    labelLarge = operationalText(14, 20, FontWeight.Medium),
    labelMedium = operationalText(12, 18, FontWeight.Medium),
    labelSmall = operationalText(11, 17, FontWeight.Medium),
)

private val OperationalShapes = Shapes(
    extraSmall = HiltechShape.small,
    small = HiltechShape.small,
    medium = HiltechShape.medium,
    large = HiltechShape.large,
    extraLarge = HiltechShape.large,
)

@Composable
fun HiltechOperationalTheme(
    dark: Boolean = isSystemInDarkTheme(),
    highContrast: Boolean = false,
    content: @Composable () -> Unit,
) {
    val palette = if (highContrast) HighContrast else if (dark) Dark else Light
    val isDark = dark || highContrast
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    // Set container and interaction roles too: Material components must not
    // fall back to unrelated default purple colors in either appearance.
    val scheme = base.copy(
        primary = palette.accent,
        onPrimary = if (isDark) palette.canvas else Color.White,
        primaryContainer = palette.selected,
        onPrimaryContainer = palette.primaryText,
        secondary = palette.secondaryText,
        onSecondary = if (isDark) palette.canvas else Color.White,
        secondaryContainer = palette.selected,
        onSecondaryContainer = palette.primaryText,
        tertiary = palette.accent,
        onTertiary = if (isDark) palette.canvas else Color.White,
        tertiaryContainer = palette.surfaceElevated,
        onTertiaryContainer = palette.primaryText,
        background = palette.canvas,
        onBackground = palette.primaryText,
        surface = palette.surface,
        onSurface = palette.primaryText,
        surfaceVariant = palette.surfaceElevated,
        onSurfaceVariant = palette.secondaryText,
        surfaceTint = palette.accent,
        outline = palette.divider,
        outlineVariant = palette.divider,
        inverseSurface = if (isDark) Light.surface else Dark.surface,
        inverseOnSurface = if (isDark) Light.primaryText else Dark.primaryText,
        inversePrimary = if (isDark) Light.accent else Dark.accent,
        error = palette.blockedText,
        onError = if (isDark) palette.canvas else Color.White,
        errorContainer = palette.blockedSurface,
        onErrorContainer = palette.blockedText,
    )
    CompositionLocalProvider(LocalHiltechOperationalColors provides palette) {
        MaterialTheme(colorScheme = scheme, typography = OperationalTypography, shapes = OperationalShapes, content = content)
    }
}

enum class HiltechStateTone { NEUTRAL, CURRENT, ATTENTION, BLOCKED }
fun procurementSourceTone(sourceCurrent: Boolean, requiresReview: Boolean = false): HiltechStateTone =
    when {
        !sourceCurrent -> HiltechStateTone.BLOCKED
        requiresReview -> HiltechStateTone.ATTENTION
        else -> HiltechStateTone.CURRENT
    }

@Composable
fun HiltechStateTag(label: String, tone: HiltechStateTone, modifier: Modifier = Modifier) {
    val p = LocalHiltechOperationalColors.current
    val (background, foreground) = when (tone) {
        HiltechStateTone.NEUTRAL -> p.surfaceElevated to p.secondaryText
        HiltechStateTone.CURRENT -> p.goodSurface to p.goodText
        HiltechStateTone.ATTENTION -> p.cautionSurface to p.cautionText
        HiltechStateTone.BLOCKED -> p.blockedSurface to p.blockedText
    }
    Surface(modifier = modifier, color = background, shape = HiltechShape.small) {
        Text(label, color = foreground, fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp, lineHeight = 16.sp,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp))
    }
}

@Composable
fun HiltechPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val p = LocalHiltechOperationalColors.current
    Surface(
        modifier = modifier,
        color = p.surface,
        shape = HiltechShape.medium,
        border = BorderStroke(1.dp, p.divider),
    ) {
        Column(Modifier.padding(HiltechSpace.medium),
            verticalArrangement = Arrangement.spacedBy(HiltechSpace.small)) {
            content()
        }
    }
}

@Composable
fun HiltechKeyValue(label: String, value: String, modifier: Modifier = Modifier) {
    val p = LocalHiltechOperationalColors.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = p.secondaryText, fontSize = 12.sp, lineHeight = 16.sp)
        Text(value, color = p.primaryText, fontSize = 15.sp, lineHeight = 20.sp,
            fontWeight = FontWeight.Medium)
    }
}

@Composable
fun HiltechSectionHeading(index: String, title: String, subtitle: String? = null) {
    val p = LocalHiltechOperationalColors.current
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(index, color = p.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp)
        Text(title, color = p.primaryText, fontSize = 23.sp,
            lineHeight = 29.sp, fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, color = p.secondaryText,
            fontSize = 13.sp, lineHeight = 19.sp)
    }
}

/** UI preference only. Never translate identifiers, source data or commands. */
enum class HiltechLanguage(val code: String, val direction: LayoutDirection) {
    Arabic("ar", LayoutDirection.Rtl),
    English("en", LayoutDirection.Ltr);

    fun text(arabic: String, english: String): String =
        if (this == Arabic) arabic else english

    companion object {
        fun fromCode(code: String?): HiltechLanguage = entries.firstOrNull { it.code == code } ?: Arabic
    }
}

val LocalHiltechLanguage = staticCompositionLocalOf { HiltechLanguage.Arabic }

@Composable
fun hiltechText(arabic: String, english: String): String = LocalHiltechLanguage.current.text(arabic, english)

@Composable
fun HiltechLanguageProvider(language: HiltechLanguage, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalHiltechLanguage provides language, LocalLayoutDirection provides language.direction) {
        content()
    }
}

@Composable
fun HiltechLanguagePreference(language: HiltechLanguage, onChange: (HiltechLanguage) -> Unit) {
    HiltechPanel(Modifier.fillMaxWidth()) {
        Text(hiltechText("لغة التطبيق", "App language"), style = MaterialTheme.typography.titleMedium)
        Text(hiltechText("تغيير اللغة واتجاه الواجهة على هذا الجهاز.", "Change the interface language and direction on this device."))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = language == HiltechLanguage.Arabic, onClick = { onChange(HiltechLanguage.Arabic) }, label = { Text("العربية") })
            FilterChip(selected = language == HiltechLanguage.English, onClick = { onChange(HiltechLanguage.English) }, label = { Text("English") })
        }
    }
}

/** A source-owned choice; IDs are kept unchanged and never inferred from labels. */
data class HiltechRecordChoice(val id: String, val title: String, val context: String = "")

/** Only current supplied records are suggested; a known reference can be entered
 * explicitly where the domain already accepts an ID. Backend authority is unchanged. */
@Composable
fun HiltechRecordPicker(
    label: String,
    value: String,
    choices: List<HiltechRecordChoice>,
    onChange: (String) -> Unit,
    allowKnownReference: Boolean = true,
    enabled: Boolean = true,
    allowClear: Boolean = true,
) {
    val palette = LocalHiltechOperationalColors.current
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var referenceMode by remember { mutableStateOf(false) }
    val records = choices.distinctBy { it.id }.filter { it.id.isNotBlank() }
    val selected = records.firstOrNull { it.id == value }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = palette.secondaryText)
        Surface(
            onClick = { expanded = !expanded; query = "" },
            enabled = enabled,
            color = palette.surfaceElevated,
            shape = HiltechShape.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.heightIn(min = 48.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(selected?.title ?: if (value.isBlank()) hiltechText("اختر سجلًا", "Choose a record") else hiltechText("مرجع محدد", "Reference selected"),
                        style = MaterialTheme.typography.bodyLarge)
                    selected?.context?.takeIf { it.isNotBlank() }?.let { Text(it, color = palette.secondaryText, style = MaterialTheme.typography.bodySmall) }
                }
                Text(if (expanded) "▴" else "▾", color = palette.accent, style = MaterialTheme.typography.titleMedium)
            }
        }
        if (expanded) {
            HiltechPanel(Modifier.fillMaxWidth()) {
                OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(hiltechText("بحث في السجلات المتاحة", "Search available records")) })
                val matching = records.filter { query.isBlank() || it.title.contains(query.trim(), true) || it.context.contains(query.trim(), true) || it.id.contains(query.trim(), true) }
                if (matching.isEmpty()) Text(hiltechText("لا توجد نتائج في السجلات المحمّلة.", "No matches in loaded records."))
                else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                    items(matching, key = { it.id }) { record ->
                        Surface(onClick = { onChange(record.id); expanded = false; referenceMode = false },
                            enabled = enabled, color = if (record.id == value) palette.selected else palette.surface,
                            modifier = Modifier.fillMaxWidth(), shape = HiltechShape.small) {
                            Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(record.title, style = MaterialTheme.typography.titleSmall)
                                if (record.context.isNotBlank()) Text(record.context, color = palette.secondaryText, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (allowKnownReference) TextButton(onClick = { referenceMode = !referenceMode }, enabled = enabled) {
                    Text(hiltechText("استخدام مرجع معروف", "Use a known reference"))
                }
                if (allowKnownReference && referenceMode) OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth(),
                    label = { Text(hiltechText("معرّف السجل من المصدر", "Record ID from source")) }, singleLine = true, enabled = enabled)
                if (allowClear && value.isNotBlank()) TextButton(onClick = { onChange(""); expanded = false }, enabled = enabled) {
                    Text(hiltechText("مسح الاختيار", "Clear selection"))
                }
            }
        }
    }
}

/** One record presentation, two layout densities. No synthetic totals. */
data class HiltechTableColumn(val title: String, val weight: Float = 1f)
data class HiltechTableRecord(val values: List<String>)

@Composable
fun HiltechRecordTable(columns: List<HiltechTableColumn>, records: List<HiltechTableRecord>, emptyMessage: String) {
    val palette = LocalHiltechOperationalColors.current
    if (records.isEmpty()) {
        Text(emptyMessage, color = palette.secondaryText, style = MaterialTheme.typography.bodyMedium)
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 640.dp && columns.size <= 6
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (wide) 0.dp else 10.dp)) {
            if (wide) {
                Surface(color = palette.surfaceElevated, shape = HiltechShape.small) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        columns.forEach { column ->
                            Text(column.title, Modifier.weight(column.weight.coerceAtLeast(.2f)), style = MaterialTheme.typography.labelMedium, color = palette.secondaryText)
                        }
                    }
                }
                records.forEachIndexed { index, record ->
                    Surface(color = if (index % 2 == 0) palette.surface else palette.canvas) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            columns.forEachIndexed { cell, column ->
                                Text(record.values.getOrNull(cell).orEmpty().ifBlank { "—" },
                                    Modifier.weight(column.weight.coerceAtLeast(.2f)), style = MaterialTheme.typography.bodyMedium,
                                    color = palette.primaryText)
                            }
                        }
                    }
                    HorizontalDivider(color = palette.divider)
                }
            } else records.forEach { record ->
                HiltechPanel(Modifier.fillMaxWidth()) {
                    columns.forEachIndexed { index, column ->
                        val value = record.values.getOrNull(index).orEmpty().ifBlank { "—" }
                        if (index == 0) {
                            Text(column.title, style = MaterialTheme.typography.labelMedium, color = palette.secondaryText)
                            Text(value, style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                                color = palette.primaryText)
                        } else {
                            HiltechKeyValue(column.title, value)
                        }
                    }
                }
            }
        }
    }
}

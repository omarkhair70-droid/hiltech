package com.hiltech.shared.core.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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

@Composable
fun HiltechOperationalTheme(
    dark: Boolean = isSystemInDarkTheme(),
    highContrast: Boolean = false,
    content: @Composable () -> Unit,
) {
    val palette = if (highContrast) HighContrast else if (dark) Dark else Light
    val scheme = if (dark || highContrast) darkColorScheme(
        primary = palette.accent,
        onPrimary = palette.canvas,
        background = palette.canvas,
        onBackground = palette.primaryText,
        surface = palette.surface,
        onSurface = palette.primaryText,
        outline = palette.divider,
        error = palette.blockedText,
    ) else lightColorScheme(
        primary = palette.accent,
        onPrimary = Color.White,
        background = palette.canvas,
        onBackground = palette.primaryText,
        surface = palette.surface,
        onSurface = palette.primaryText,
        outline = palette.divider,
        error = palette.blockedText,
    )
    CompositionLocalProvider(LocalHiltechOperationalColors provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
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

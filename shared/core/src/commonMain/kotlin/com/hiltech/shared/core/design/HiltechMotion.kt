package com.hiltech.shared.core.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** Shared motion vocabulary; Compose honors the platform animator duration scale.
 * Animate only the current content, never retain a revoked or previous actor's pane.
 * No animation gates commands, source reads, or authoritative status changes. */
object HiltechMotion {
    const val selectionMillis = 140
    const val revealMillis = 180
}

@Composable
fun hiltechRevealModifier(routeKey: Any?): Modifier {
    val progress = remember(routeKey) { Animatable(0f) }
    LaunchedEffect(progress) {
        progress.animateTo(1f, tween(HiltechMotion.revealMillis, easing = FastOutSlowInEasing))
    }
    // Keep text legible throughout and avoid moving hit targets or changing layout.
    return Modifier.graphicsLayer { alpha = 0.86f + 0.14f * progress.value }
}

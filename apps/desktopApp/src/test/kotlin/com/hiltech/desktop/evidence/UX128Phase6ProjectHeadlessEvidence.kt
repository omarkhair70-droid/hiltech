package com.hiltech.desktop.evidence

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.ProjectCommandCenterSection
import com.hiltech.shared.core.projects.ProjectSummaryDto
import com.hiltech.shared.core.work.ProjectCommandCenterDto
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path

/**
 * UX #128 — headless render of ACTUAL existing Phase 4 project Compose UI,
 * against the original Phase4 synthetic fixture on current main.
 *
 * Reuses the existing private Phase4 test factory via JVM reflection solely
 * within test runtime. Never used in app or release code. We deliberately
 * do NOT use java.awt.Robot, as a foreground browser can otherwise produce
 * a false-positive image with a RENDER_PASS console marker.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() = runBlocking<Unit> {
    val output = Path.of(
        System.getenv("HILTECH_UX128_PROJECT_EVIDENCE_DIR")
            ?: "build/reports/ux128-phase6-project",
    )
    Files.createDirectories(output)
    val cases = listOf(
        Triple("review-blocked", "ACTIVE", 11L),
        Triple("active-health", "ACTIVE", 11L),
        Triple("on-hold", "ON_HOLD", 12L),
    )
    val fixtureMethods = mapOf(
        "review-blocked" to "reviewBlockedCommandCenter",
        "active-health" to "activeCommandCenter",
        "on-hold" to "onHoldCommandCenter",
    )
    var count = 0
    cases.forEach { (stateLabel, lifecycle, version) ->
        val dto = fixture(fixtureMethods.getValue(stateLabel))
        val project = projectFixture(lifecycle, version)
        for ((sizeLabel, width, height) in listOf(
            Triple("windows", 1220, 1180),
            Triple("phone", 430, 960),
        )) {
            val scroll = ScrollState(0)
            val scene = ImageComposeScene(width = width, height = height, coroutineContext = coroutineContext) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Column(
                        Modifier.fillMaxSize().background(Color.White)
                            .verticalScroll(scroll).padding(18.dp),
                    ) {
                        ProjectCommandCenterSection(
                            project = project,
                            commandCenter = dto,
                            onAction = {},
                        )
                    }
                }
            }
            try {
                render(scene, output.resolve("ux128-pm-$stateLabel-$sizeLabel-top.png"), width, height)
                count++
                scroll.scrollTo(650)
                render(scene, output.resolve("ux128-pm-$stateLabel-$sizeLabel-scrolled.png"), width, height)
                count++
            } finally {
                scene.close()
            }
        }
    }
    check(count == 12) { "All three PM states at two sizes and two scroll positions must be captured." }
    println("UX128_PHASE6_PM_HEADLESS_COMPOSE_RENDER_PASS exact_fixture=Phase4Slice05 synthetic=true screenshots=$count no_robot=true")
}

private fun fixture(method: String): ProjectCommandCenterDto {
    val owner = Class.forName("com.hiltech.desktop.evidence.Phase4Slice05RenderEvidenceKt")
    val lookup = owner.getDeclaredMethod(method).apply { isAccessible = true }
    return lookup.invoke(null) as ProjectCommandCenterDto
}

private fun projectFixture(lifecycle: String, version: Long): ProjectSummaryDto {
    val owner = Class.forName("com.hiltech.desktop.evidence.Phase4Slice05RenderEvidenceKt")
    val lookup = owner.getDeclaredMethod("project", String::class.java, java.lang.Long.TYPE)
        .apply { isAccessible = true }
    return lookup.invoke(null, lifecycle, version) as ProjectSummaryDto
}

@OptIn(ExperimentalComposeUiApi::class)
private fun render(scene: ImageComposeScene, destination: Path, width: Int, height: Int) {
    scene.render().use { rendered ->
        check(rendered.width == width && rendered.height == height)
        val png = requireNotNull(rendered.encodeToData(EncodedImageFormat.PNG))
        png.use { encoded ->
            check(encoded.bytes.size > 4000) { "Unexpectedly empty image: $destination" }
            Files.write(destination, encoded.bytes)
        }
    }
}

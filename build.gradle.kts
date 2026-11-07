plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinSpring) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room3) apply false
    alias(libs.plugins.springBoot) apply false
}

/**
 * Security-only dependency alignment across server and native clients.
 *
 * The existing Spring Boot BOM resolved Jackson 3.1.5, which the November
 * 2026 OSV PR gate rejects. Jackson 3.1.8 is the patched 3.1.x line,
 * including the later polymorphic-type-validator advisory.
 *
 * Keep this scoped to Jackson's 3.x core artifacts; do not silently
 * upgrade Spring Boot or unrelated dependencies in the warehouse PR.
 * Exact-head CI, dependency resolution and API regressions are mandatory.
 */
subprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (
                requested.group == "tools.jackson.core" &&
                requested.name in setOf("jackson-core", "jackson-databind")
            ) {
                useVersion("3.1.8")
                because(
                    "HILTECH security patch: remediate 2026 Jackson 3.1.5 high/critical advisories while retaining the proven Spring Boot line.",
                )
            }
        }
    }
}

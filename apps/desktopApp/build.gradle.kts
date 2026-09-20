import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("com.hiltech.base")
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared:core"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.core)
}

val testRuntimeClasspath = sourceSets["test"].runtimeClasspath

tasks.register<JavaExec>("runOidcProviderSmoke") {
    group = "verification"
    description = "Runs the Phase 1 native OIDC provider smoke harness."
    dependsOn(tasks.named("testClasses"))
    classpath = testRuntimeClasspath
    mainClass.set(
        "com.hiltech.desktop.evidence.NativeOidcProviderSmokeKt",
    )

    System.getenv("HILTECH_OIDC_TRUST_STORE")
        ?.takeIf { it.isNotBlank() }
        ?.let {
            systemProperty(
                "javax.net.ssl.trustStore",
                it,
            )
        }
    System.getenv("HILTECH_OIDC_TRUST_STORE_PASSWORD")
        ?.takeIf { it.isNotBlank() }
        ?.let {
            systemProperty(
                "javax.net.ssl.trustStorePassword",
                it,
            )
        }
}

tasks.register<JavaExec>("renderPhase1ShellEvidence") {
    group = "verification"
    description = "Renders Phase 1 production Desktop shell evidence."
    dependsOn(tasks.named("testClasses"))
    classpath = testRuntimeClasspath
    mainClass.set(
        "com.hiltech.desktop.evidence.Phase1ShellRenderEvidenceKt",
    )
}

compose.desktop {
    application {
        mainClass = "com.hiltech.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "HILTECH"
            packageVersion = "0.1.0"
        }
    }
}


tasks.register<JavaExec>("renderPhase3OnboardingEvidence") {
    group = "verification"
    description = "Renders Phase 3 onboarding Desktop human-flow evidence."
    dependsOn(tasks.named("testClasses"))
    classpath = testRuntimeClasspath
    mainClass.set(
        "com.hiltech.desktop.evidence.Phase3OnboardingRenderEvidenceKt",
    )
}

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val hiltechVersion = providers
    .gradleProperty("hiltechVersion")
    .orElse("0.1.0")
    .get()

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
}

compose.desktop {
    application {
        mainClass = "com.hiltech.spike.desktop.MainKt"
        jvmArgs += listOf("-Dhiltech.app.version=$hiltechVersion")

        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "HILTECHSpike"
            packageVersion = hiltechVersion
        }
    }
}

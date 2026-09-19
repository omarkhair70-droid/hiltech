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

fun String.asBuildConfigString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val hiltechApiBaseUrl = providers.gradleProperty("hiltech.apiBaseUrl")
    .getOrElse("")

plugins {
    id("com.hiltech.base")
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "com.hiltech.android"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.hiltech.android"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidTargetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField(
            "String",
            "HILTECH_API_BASE_URL",
            hiltechApiBaseUrl.asBuildConfigString(),
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":shared:core"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.work.runtime)
}

fun String.asBuildConfigString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val hiltechApiBaseUrl = providers.gradleProperty("hiltech.apiBaseUrl")
    .getOrElse("")
val hiltechOidcIssuerUri = providers.gradleProperty("hiltech.oidcIssuerUri")
    .getOrElse("")
val hiltechOidcClientId = providers.gradleProperty("hiltech.oidcClientId")
    .getOrElse("hiltech-native")
val personalIssuedStockUiEnabled = providers
    .gradleProperty("hiltech.personalIssuedStockEnabled")
    .getOrElse("false") == "true"
val materialUsageReportingUiEnabled = providers
    .gradleProperty("hiltech.materialUsageReportingEnabled")
    .getOrElse("false") == "true"
val assignedAssetLookupUiEnabled = providers
    .gradleProperty("hiltech.assignedAssetLookupEnabled")
    .getOrElse("false") == "true"
val engineeringTestsUiEnabled = providers
    .gradleProperty("hiltech.engineeringTestsEnabled")
    .getOrElse("false") == "true"

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
        buildConfigField(
            "String",
            "HILTECH_OIDC_ISSUER_URI",
            hiltechOidcIssuerUri.asBuildConfigString(),
        )
        buildConfigField(
            "String",
            "HILTECH_OIDC_CLIENT_ID",
            hiltechOidcClientId.asBuildConfigString(),
        )
        buildConfigField(
            "boolean",
            "HILTECH_PERSONAL_ISSUED_STOCK_ENABLED",
            personalIssuedStockUiEnabled.toString(),
        )
        buildConfigField(
            "boolean",
            "HILTECH_MATERIAL_USAGE_REPORTING_ENABLED",
            materialUsageReportingUiEnabled.toString(),
        )
        buildConfigField(
            "boolean",
            "HILTECH_ASSIGNED_ASSET_LOOKUP_ENABLED",
            assignedAssetLookupUiEnabled.toString(),
        )
        buildConfigField(
            "boolean",
            "HILTECH_ENGINEERING_TESTS_ENABLED",
            engineeringTestsUiEnabled.toString(),
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
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
}

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
val procurementRequirementsUiEnabled = providers
    .gradleProperty("hiltech.procurementRequirementsEnabled")
    .getOrElse("false") == "true"
val assetPurchaseNeedsUiEnabled = providers
    .gradleProperty("hiltech.assetPurchaseNeedsEnabled")
    .getOrElse("false") == "true"
val officeStockNeedsUiEnabled = providers
    .gradleProperty("hiltech.officeStockNeedsEnabled")
    .getOrElse("false") == "true"
val warehouseReplenishmentUiEnabled = providers
    .gradleProperty("hiltech.warehouseReplenishmentEnabled")
    .getOrElse("false") == "true"
val poWarehouseIntakeUiEnabled = providers
    .gradleProperty("hiltech.poWarehouseIntakeEnabled")
    .getOrElse("false") == "true"
val supplierInvoiceFinanceUiEnabled = providers
    .gradleProperty("hiltech.supplierInvoiceFinanceEnabled")
    .getOrElse("false") == "true"
val servicePurchasesUiEnabled = providers
    .gradleProperty("hiltech.servicePurchasesEnabled")
    .getOrElse("false") == "true"
// Read-only source projection; never enabled for production by default.
val executiveHomeUiEnabled = providers
    .gradleProperty("hiltech.executiveHomeEnabled")
    .getOrElse("false") == "true"
val ownPayslipsUiEnabled = providers
    .gradleProperty("hiltech.ownPayslipsEnabled")
    .getOrElse("false") == "true"
val commercialReadUiEnabled = providers
    .gradleProperty("hiltech.commercialReadEnabled")
    .getOrElse("false") == "true"
val handoverReadUiEnabled = providers
    .gradleProperty("hiltech.handoverReadEnabled")
    .getOrElse("false") == "true"
val financeInvoiceReadUiEnabled = providers
    .gradleProperty("hiltech.financeInvoiceReadEnabled")
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
        buildConfigField(
            "boolean",
            "HILTECH_PROCUREMENT_REQUIREMENTS_ENABLED",
            procurementRequirementsUiEnabled.toString(),
        )
        buildConfigField(
            "boolean",
            "HILTECH_ASSET_PURCHASE_NEEDS_ENABLED",
            assetPurchaseNeedsUiEnabled.toString(),
        )
        buildConfigField(
            "boolean",
            "HILTECH_OFFICE_STOCK_NEEDS_ENABLED",
            officeStockNeedsUiEnabled.toString(),
        )
        buildConfigField(
            "boolean",
            "HILTECH_WAREHOUSE_REPLENISHMENT_ENABLED",
            warehouseReplenishmentUiEnabled.toString(),
        )
        buildConfigField(
            "boolean",
            "HILTECH_PO_WAREHOUSE_INTAKE_ENABLED",
            poWarehouseIntakeUiEnabled.toString(),
        )
        buildConfigField(
            "boolean",
            "HILTECH_SUPPLIER_INVOICE_FINANCE_ENABLED",
            supplierInvoiceFinanceUiEnabled.toString(),
        )
        buildConfigField(
            "boolean",
            "HILTECH_SERVICE_PURCHASES_ENABLED",
            servicePurchasesUiEnabled.toString(),
        )
        buildConfigField(
            "boolean",
            "HILTECH_EXECUTIVE_HOME_ENABLED",
            executiveHomeUiEnabled.toString(),
        )
        buildConfigField(
            "boolean",
            "HILTECH_OWN_PAYSLIPS_ENABLED",
            ownPayslipsUiEnabled.toString(),
        )
        buildConfigField(
            "boolean",
            "HILTECH_COMMERCIAL_READ_ENABLED",
            commercialReadUiEnabled.toString(),
        )
        buildConfigField(
            "boolean",
            "HILTECH_HANDOVER_READ_ENABLED",
            handoverReadUiEnabled.toString(),
        )
        buildConfigField(
            "boolean",
            "HILTECH_FINANCE_INVOICE_READ_ENABLED",
            financeInvoiceReadUiEnabled.toString(),
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

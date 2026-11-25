package com.hiltech.server

import com.hiltech.server.identity.identityProductCapabilities
import com.hiltech.server.identity.InternalOperationCatalog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

class OperationsProfileTest {
    private val protectedSettings = listOf(
        "hiltech.identity.oidc.enabled",
        "hiltech.identity.provisioning.keycloak.enabled",
        "hiltech.identity.provisioning.keycloak.email-action-link-enabled",
        "hiltech.authorization.openfga.enabled",
        "hiltech.evidence.storage.enabled",
        "hiltech.finance.bank-statement-intake.manual-entry-enabled",
        "hiltech.finance.gl-period-close.full-ledger-ready.enabled",
        "hiltech.payroll.final-pay-review-enabled",
        "hiltech.payroll.separation-source-enabled",
    )

    @Test
    fun internalProductIsAvailableByDefaultAndTheLegacyProfileUsesTheSameCatalog() {
        val core = environment()
        val product = environment("operations")
        assertEquals(76, InternalOperationCatalog.properties.size)
        assertEquals(76, InternalOperationCatalog.properties.values.toSet().size)
        InternalOperationCatalog.properties.values.forEach { property ->
            assertTrue(core.getProperty(property, Boolean::class.java, false), property)
            assertTrue(product.getProperty(property, Boolean::class.java, false), property)
        }
        assertEquals(core.identityProductCapabilities(), product.identityProductCapabilities())
        val nativeCapabilities = product.identityProductCapabilities()
        assertTrue(nativeCapabilities.assignedAssetLookup)
        assertTrue(nativeCapabilities.executiveHome)
        assertTrue(nativeCapabilities.engineeringTests)
        assertEquals(true, nativeCapabilities.operationAvailability["service.handover"])
        assertEquals(true, nativeCapabilities.operationAvailability["payroll.run-review"])
        assertEquals(true, nativeCapabilities.operationAvailability["finance.bank-statement-intake"])
    }

    @Test
    fun incidentOverridesChangeOnlyTheirDeclaredRoutesAndBootstrapValues() {
        val overridden = environment("operations", mapOf(
            "HILTECH_FIELD_COMPLETION_ENABLED" to "false",
            "HILTECH_FIELD_ENGINEERING_TESTS_ENABLED" to "false",
            "HILTECH_PAYROLL_APPROVAL_REQUEST_ENABLED" to "false",
            "hiltech.phase11.handover.enabled" to "false",
        ))
        assertFalse(overridden.getRequiredProperty("hiltech.field.completion.enabled", Boolean::class.java))
        assertTrue(overridden.getRequiredProperty("hiltech.field.assigned-asset-lookup.enabled", Boolean::class.java))
        val capability = overridden.identityProductCapabilities()
        assertFalse(capability.engineeringTests)
        assertFalse(capability.operationAvailability.getValue("field.engineering-tests"))
        assertFalse(capability.operationAvailability.getValue("field.completion"))
        assertFalse(capability.operationAvailability.getValue("payroll.run-review"))
        assertFalse(capability.operationAvailability.getValue("service.handover"))
        assertTrue(capability.operationAvailability.getValue("field.assigned-asset-lookup"))
        assertTrue(capability.operationAvailability.getValue("payroll.periods"))
    }

    @Test
    fun oneInternalDefaultControlsUnspecifiedOperationsWithoutOverridingExplicitSettings() {
        for (profile in listOf(null, "operations")) {
            val disabled = environment(profile, mapOf("HILTECH_INTERNAL_OPERATIONS_ENABLED" to "false"))
            assertTrue(disabled.identityProductCapabilities().operationAvailability.values.none { it })
            val selected = environment(profile, mapOf("HILTECH_INTERNAL_OPERATIONS_ENABLED" to "false",
                "HILTECH_FIELD_COMPLETION_ENABLED" to "true"))
            assertEquals(setOf("field.completion"), selected.identityProductCapabilities().operationAvailability.filterValues { it }.keys)
        }
    }

    @Test
    fun productAvailabilityDoesNotInventSecurityProviderPolicyOrSourceReadiness() {
        for (profile in listOf(null, "operations")) {
            val product = environment(profile)
            protectedSettings.forEach { property ->
                assertFalse(product.getProperty(property, Boolean::class.java, false), property)
                assertTrue(property !in InternalOperationCatalog.properties.values, property)
            }
            assertEquals("", product.getProperty("hiltech.identity.oidc.issuer-uri"))
            assertEquals("", product.getProperty("hiltech.identity.provisioning.keycloak.admin-client-secret"))
            assertEquals("", product.getProperty("hiltech.commercial.mail-encryption-key"))
            assertNull(product.getProperty("hiltech.finance.ap-posting.policy-key"))
            assertNull(product.getProperty("hiltech.procurement.approval-policy-key"))
            assertNull(product.getProperty("hiltech.finance.payables.review-authority-key"))
            assertTrue(product.identityProductCapabilities().operationAvailability.keys.none { key ->
                key.contains("full-ledger-ready") || key.contains("manual-entry") || key.contains("separation-source") ||
                    key.contains("authority") || key.contains("policy-key") || key.contains("secret")
            })
        }
    }

    @Test
    fun combinedControllerGatesAreReflectedWithoutInferringBusinessReadiness() {
        val product = environment(variables = mapOf(
            "hiltech.phase11.support.enabled" to "false",
            "hiltech.phase11.maintenance.enabled" to "false",
            "hiltech.procurement.requirements.enabled" to "false",
            "hiltech.finance.cashbox.enabled" to "false",
        ))
        val available = product.identityProductCapabilities().operationAvailability
        for (name in listOf("service.support", "service.support.time", "service.warranty", "service.maintenance",
            "service.maintenance.review", "service.maintenance.assigned-findings", "procurement.requirements",
            "procurement.warehouse-replenishment", "procurement.office-stock", "procurement.asset-parts",
            "finance.cashbox", "finance.cashbox.count")) assertFalse(available.getValue(name), name)
        assertTrue(available.getValue("service.handover"))
        assertTrue(available.getValue("payroll.run-review"))
        InternalOperationCatalog.prerequisites.forEach { (name, dependencies) ->
            assertTrue(name in InternalOperationCatalog.properties)
            assertTrue(dependencies.all { it in InternalOperationCatalog.properties })
        }
    }

    private fun environment(
        profile: String? = null,
        variables: Map<String, Any> = emptyMap(),
    ) = StandardEnvironment().apply {
        propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
        propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
        propertySources.addFirst(MapPropertySource("profile-overrides", variables))
        profile?.let(::setActiveProfiles)
        ConfigDataEnvironmentPostProcessor.applyTo(this)
    }
}

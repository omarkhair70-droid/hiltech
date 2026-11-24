package com.hiltech.server

import com.hiltech.server.identity.identityProductCapabilities
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

class OperationsProfileTest {
    private val operations = listOf(
        "hiltech.field.personal-issued-stock.enabled",
        "hiltech.field.material-usage-report.enabled",
        "hiltech.field.assigned-asset-lookup.enabled",
        "hiltech.field.completion.enabled",
        "hiltech.executive.home-enabled",
    )

    @Test
    fun operationsAreGroupedWhileExplicitOverridesRemainEffective() {
        val core = environment()
        val product = environment("operations")
        operations.forEach { property ->
            assertFalse(core.getProperty(property, Boolean::class.java, false), property)
            assertTrue(product.getProperty(property, Boolean::class.java, false), property)
        }
        val nativeCapabilities = product.identityProductCapabilities()
        assertTrue(nativeCapabilities.assignedAssetLookup)
        assertTrue(nativeCapabilities.executiveHome)
        assertFalse(nativeCapabilities.engineeringTests)

        val overridden = environment("operations", mapOf("HILTECH_FIELD_COMPLETION_ENABLED" to "false"))
        assertFalse(overridden.getRequiredProperty("hiltech.field.completion.enabled", Boolean::class.java))
        assertTrue(overridden.getRequiredProperty("hiltech.field.assigned-asset-lookup.enabled", Boolean::class.java))
        assertFalse(product.getRequiredProperty("hiltech.field.material-usage-reconciliation.enabled", Boolean::class.java))
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

package com.hiltech.server.service

import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SupportDeskFieldRulesTest {
    @Test
    fun severityAndPriorityAreIndependentCodes() {
        assertEquals("CRITICAL", SupportDeskFieldRules.code("CRITICAL"))
        assertEquals("LOW", SupportDeskFieldRules.code("LOW"))
        assertEquals("CUSTOM_CODE", SupportDeskFieldRules.code(" CUSTOM_CODE "))
    }

    @Test
    fun invalidCodesAreRejectedNotAutoMappedToAnSla() {
        listOf("", "high priority", "a", "urgent/2", "maybe", "N".repeat(65)).forEach {
            assertThrows(ProductApiException::class.java) {
                SupportDeskFieldRules.code(it)
            }
        }
    }

    @Test
    fun descriptionsAreBoundedAndNotOptional() {
        assertEquals("source", SupportDeskFieldRules.text(" source ", 12))
        assertThrows(ProductApiException::class.java) {
            SupportDeskFieldRules.text("   ", 8000)
        }
        assertThrows(ProductApiException::class.java) {
            SupportDeskFieldRules.text("b".repeat(8001), 8000)
        }
    }
}

package com.hiltech.shared.core.commercial

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommercialClientQuoteDecodingTest {
    @Test
    fun customerQuotePreservesExactDecimalAndNoCostFields() {
        val input = """
        {
          "quoteId":"q",
          "quoteCode":"Q-TEST",
          "versionNumber":2,
          "clientOrganizationId":"client",
          "currency":"EGP",
          "totalPrice":123456789.1234,
          "paymentTerms":"Reviewed",
          "deliveryTerms":"Approved",
          "exclusions":"",
          "validityDate":"2026-12-20",
          "lines":[{
            "lineCode":"A",
            "description":"Scope",
            "quantity":0.2500,
            "unit":"unit",
            "unitPrice":123.4567
          }]
        }
        """.trimIndent()
        val dto = Json.decodeFromString<ClientQuoteDto>(input)
        assertEquals("123456789.1234", dto.totalPrice.content)
        assertEquals("0.2500", dto.lines.single().quantity.content)
        assertEquals("123.4567", dto.lines.single().unitPrice.content)
        val encoded = Json.encodeToString(ClientQuoteDto.serializer(), dto)
        assertTrue("internal_unit_cost" !in encoded)
        assertTrue("margin" !in encoded)
        assertTrue("supplier" !in encoded)
    }
}

package com.hiltech.server.commercial

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.HexFormat

class WhatsappWebhookSignatureTest {
    @Test fun rejectsMissingInvalidOrTamperedSignatures() {
        val body = """{"object":"whatsapp_business_account"}""".toByteArray()
        val key = "test-only-key"
        val mac=Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.toByteArray(),"HmacSHA256")) }
        val sig="sha256="+HexFormat.of().formatHex(mac.doFinal(body))
        assertTrue(WhatsappWebhookSignature.valid(key,body,sig))
        assertFalse(WhatsappWebhookSignature.valid(key,body+byteArrayOf(32),sig))
        assertFalse(WhatsappWebhookSignature.valid("other-key",body,sig))
        assertFalse(WhatsappWebhookSignature.valid(key,body,null))
        assertFalse(WhatsappWebhookSignature.valid(key,body,"sha256=invalid"))
    }
}

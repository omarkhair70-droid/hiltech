package com.hiltech.server.commercial

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.HexFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import tools.jackson.databind.json.JsonMapper

internal object WhatsappWebhookSignature {
    fun valid(secret: String,raw: ByteArray,signature: String?): Boolean {
        if(secret.isBlank() || signature == null || !signature.matches(Regex("sha256=[a-fA-F0-9]{64}"))) return false
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8),"HmacSHA256"))
        return MessageDigest.isEqual(mac.doFinal(raw),HexFormat.of().parseHex(signature.substring(7)))
    }
}
@Component
class CommercialWhatsappWebhook(
    private val jdbc: JdbcTemplate,
    @Value("\${hiltech.commercial.meta.app-secret:}") private val appSecret: String,
    @Value("\${hiltech.commercial.meta.webhook-verify-token:}") private val verifyToken: String,
) {
    private val mapper = JsonMapper.builder().build()
    fun verify(mode:String?,token:String?,challenge:String?):String? =
        challenge?.takeIf { mode=="subscribe" && verifyToken.isNotBlank() && token!=null &&
            MessageDigest.isEqual(verifyToken.toByteArray(Charsets.UTF_8),token.toByteArray(Charsets.UTF_8)) && it.matches(Regex("[0-9]{1,64}")) }
    fun receive(raw:ByteArray,signature:String?):Boolean {
        if(raw.size>1048576 || !WhatsappWebhookSignature.valid(appSecret,raw,signature)) return false
        val root = try { mapper.readTree(raw) } catch(_:Exception) { return false }
        if(root.path("object").asText()!="whatsapp_business_account") return true
        for(entry in root.path("entry")) for(change in entry.path("changes")) {
            if(change.path("field").asText()!="messages") continue
            val value=change.path("value")
            val phone=value.path("metadata").path("phone_number_id").asText()
            if(!phone.matches(Regex("[0-9]{1,40}")))continue
            for(event in value.path("statuses")) {
                val id=event.path("id").asText()
                val next=event.path("status").asText().uppercase()
                val recipient=event.path("recipient_id").asText()
                val opaque=event.path("biz_opaque_callback_data").asText()
                val key=runCatching { UUID.fromString(opaque) }.getOrNull()
                val at=runCatching { OffsetDateTime.ofInstant(Instant.ofEpochSecond(event.path("timestamp").asText().toLong()),ZoneOffset.UTC) }.getOrNull() ?:continue
                if(id.isBlank() || id.length>1024 || next !in setOf("SENT","DELIVERED","READ","FAILED"))continue
                // Match the exact provider message and originating phone, or the operation ID echoed by Meta.
                // Stronger delivery evidence cannot regress due to delayed or repeated events.
                jdbc.update("""UPDATE commercial_quote_whatsapp_attempt
                    SET state=?,provider_message_id=COALESCE(provider_message_id,?),provider_status_at=?,
                        completed_at=COALESCE(completed_at,now())
                    WHERE sender_phone_id=? AND recipient=?
                      AND (provider_message_id=? OR (provider_message_id IS NULL AND id=?))
                      AND (
                        ( ?='READ' AND state<>'READ') OR
                        ( ?='DELIVERED' AND state NOT IN ('READ','DELIVERED')) OR
                        ( ?='SENT' AND state IN ('SENDING','UNKNOWN','API_ACCEPTED')) OR
                        ( ?='FAILED' AND state IN ('SENDING','UNKNOWN','API_ACCEPTED','SENT'))
                      )
                      AND (provider_status_at IS NULL OR provider_status_at<=? OR ? IN ('READ','DELIVERED'))""",
                    next,id,at,phone,recipient,id,key,next,next,next,next,at,next)
            }
        }
        return true
    }
}

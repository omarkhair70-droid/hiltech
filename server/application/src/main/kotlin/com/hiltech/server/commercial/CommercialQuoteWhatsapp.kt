package com.hiltech.server.commercial

import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.MediaType
import org.springframework.http.HttpStatus
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration
import java.util.UUID
import java.util.Base64

data class CommercialWhatsappRequest(val operationId: String, val recipient: String, val consentConfirmed: Boolean)
data class CommercialWhatsappStatus(val id: String, val recipient: String, val state: String, val createdAt: String)

@Component
class CommercialQuoteWhatsapp(
    private val commercial: CommercialService,
    private val jdbc: JdbcTemplate,
    private val connections: CommercialWhatsappConnection,
    @Value("\${hiltech.commercial.whatsapp.company:}") private val company: String,
    @Value("\${hiltech.commercial.whatsapp.phone-id:}") private val phoneId: String,
    @Value("\${hiltech.commercial.whatsapp.token:}") private val token: String,
    @Value("\${hiltech.commercial.whatsapp.template:}") private val template: String,
    @Value("\${hiltech.commercial.whatsapp.language:ar}") private val language: String,
    @Value("\${hiltech.commercial.whatsapp.api-version:}") private val apiVersion: String,
) {
    private fun ready(org: UUID) = company == org.toString() && phoneId.matches(Regex("[0-9]+")) &&
        token.isNotBlank() && template.matches(Regex("[a-z0-9_]+")) && apiVersion.matches(Regex("v[0-9]+[.][0-9]+"))
    private val client = RestClient.builder().requestFactory(
        JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()).apply {
            setReadTimeout(Duration.ofSeconds(30))
        }
    ).build()
    fun available(id: UUID, actor: UUID): Boolean {
        val org = commercial.quoteCompanyForDispatch(id,actor)
        return connections.forSending(org) != null || ready(org)
    }
    private fun status(row: Map<String,Any?>) = CommercialWhatsappStatus(row["id"].toString(),row["recipient"].toString(),row["state"].toString(),row["created_at"].toString())
    fun history(id: UUID,actor: UUID): List<CommercialWhatsappStatus> {
        commercial.customerQuote(id,actor)
        return jdbc.queryForList("SELECT id,recipient,state,created_at FROM commercial_quote_whatsapp_attempt WHERE quote_version_id=? ORDER BY created_at DESC",id).map(::status)
    }
    fun send(id: UUID,actor: UUID,key: UUID,r: CommercialWhatsappRequest): CommercialWhatsappStatus {
        val org = commercial.quoteCompanyForDispatch(id,actor)
        val connection = connections.forSending(org) ?: if (ready(org)) CommercialWhatsappCredentials(phoneId,token,template,language,apiVersion) else null
        if (connection == null) throw commercialError("WHATSAPP_NOT_CONFIGURED","WhatsApp Business is not configured.",HttpStatus.SERVICE_UNAVAILABLE)
        val to = r.recipient.trim().removePrefix("+")
        if (!to.matches(Regex("[1-9][0-9]{7,14}")) || !r.consentConfirmed)
            throw commercialError("WHATSAPP_RECIPIENT_INVALID","Confirm recipient consent and international number.",HttpStatus.BAD_REQUEST)
        val pdf = commercial.customerPdf(id,actor)
        val bytes = Base64.getDecoder().decode(pdf.base64)
        commercial.quoteCompanyForDispatch(id,actor)
        val inserted = jdbc.update("INSERT INTO commercial_quote_whatsapp_attempt(id,quote_version_id,recipient,actor_id,state,sender_phone_id) VALUES (?,?,?,?,'SENDING',?) ON CONFLICT DO NOTHING",key,id,to,actor,connection.phoneId)
        fun current() = jdbc.queryForList("SELECT id,recipient,state,created_at,quote_version_id,actor_id FROM commercial_quote_whatsapp_attempt WHERE id=?",key).single()
        if (inserted == 0) {
            val old = current()
            if (old["quote_version_id"] != id || old["actor_id"] != actor || old["recipient"] != to)
                throw commercialError("WHATSAPP_OPERATION_CONFLICT","Operation already used.",HttpStatus.CONFLICT)
            return status(old)
        }
        var providerId: String? = null
        try {
            val url = "https://graph.facebook.com/"+connection.apiVersion+"/"+connection.phoneId
            val form = LinkedMultiValueMap<String,Any>()
            form.add("messaging_product","whatsapp")
            form.add("type","application/pdf")
            form.add("file",object : ByteArrayResource(bytes) { override fun getFilename() = pdf.filename })
            val uploaded = client.post().uri("$url/media").headers { it.setBearerAuth(connection.token) }.contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form).retrieve().body(Map::class.java)
            val mediaId = requireNotNull(uploaded?.get("id") as? String)
            val body = mapOf("messaging_product" to "whatsapp","to" to to,"type" to "template","biz_opaque_callback_data" to key.toString(),
                "template" to mapOf("name" to connection.template,"language" to mapOf("code" to connection.language),
                    "components" to listOf(mapOf("type" to "header","parameters" to listOf(mapOf("type" to "document",
                        "document" to mapOf("id" to mediaId,"filename" to pdf.filename)))))))
            val sent = client.post().uri("$url/messages").headers { it.setBearerAuth(connection.token) }.contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(Map::class.java)
            providerId = ((sent?.get("messages") as? List<*>)?.firstOrNull() as? Map<*,*>)?.get("id") as? String
        } catch (_: Exception) { /* Ambiguous provider outcome; never retry automatically. */ }
        jdbc.update("UPDATE commercial_quote_whatsapp_attempt SET state=?,provider_message_id=?,completed_at=now() WHERE id=? AND state='SENDING'",
            if (providerId.isNullOrBlank()) "UNKNOWN" else "API_ACCEPTED",providerId,key)
        return status(current())
    }
}

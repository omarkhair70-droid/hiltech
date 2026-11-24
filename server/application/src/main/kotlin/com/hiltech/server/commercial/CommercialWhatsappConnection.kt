package com.hiltech.server.commercial

import org.springframework.http.HttpStatus
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration
import java.util.UUID

data class CommercialWhatsappConnectionStatus(val configured: Boolean,val setupAvailable: Boolean,val phoneId: String? = null,val displayPhone: String? = null,val template: String? = null,val language: String? = null,val apiVersion: String? = null,val version: Long = 0)
class ConnectCommercialWhatsappRequest(val organizationId: String,val phoneId: String,val token: String,val template: String,val language: String,val apiVersion: String,val baseVersion: Long)
data class DisconnectCommercialWhatsappRequest(val organizationId: String,val baseVersion: Long)
internal class CommercialWhatsappCredentials(val phoneId: String,val token: String,val template: String,val language: String,val apiVersion: String)

@Component
class CommercialWhatsappConnection(private val jdbc: JdbcTemplate,private val access: CommercialAccess,private val secrets: CommercialMailConnection) {
    private val client = RestClient.builder().requestFactory(JdkClientHttpRequestFactory(
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()).apply { setReadTimeout(Duration.ofSeconds(15)) }).build()
    private fun record(org: UUID) = jdbc.queryForList("SELECT phone_id,display_phone,template_name,language_code,api_version,encrypted_token,version FROM commercial_company_whatsapp_connection WHERE organization_id=?",org).firstOrNull()
    fun status(org: UUID,actor: UUID): CommercialWhatsappConnectionStatus {
        access.require(actor,org,"SALES_RELEASE")
        val r = record(org)
        return CommercialWhatsappConnectionStatus(r != null,secrets.encryptionAvailable(),r?.get("phone_id") as? String,r?.get("display_phone") as? String,
            r?.get("template_name") as? String,r?.get("language_code") as? String,r?.get("api_version") as? String,(r?.get("version") as? Number)?.toLong() ?: 0)
    }
    fun connect(r: ConnectCommercialWhatsappRequest,actor: UUID): CommercialWhatsappConnectionStatus {
        val org = r.organizationId.commercialUuid()
        access.require(actor,org,"SALES_RELEASE")
        if (!r.phoneId.matches(Regex("[0-9]{1,40}")) || !r.apiVersion.matches(Regex("v[0-9]{1,3}[.][0-9]{1,2}")) ||
            !r.template.matches(Regex("[a-z0-9_]{1,512}")) || !r.language.matches(Regex("[a-zA-Z_]{2,20}")) ||
            r.token.isBlank() || r.token.length > 8192)
            throw commercialError("WHATSAPP_CONNECTION_INVALID","Invalid Meta connection fields.",HttpStatus.BAD_REQUEST)
        val encrypted = secrets.encryptProviderSecret(org,"whatsapp",r.token)
        val display = try {
            val result = client.get().uri("https://graph.facebook.com/"+r.apiVersion+"/"+r.phoneId+"?fields=id,display_phone_number")
                .headers { it.setBearerAuth(r.token) }.retrieve().body(Map::class.java)
            require(result?.get("id")?.toString() == r.phoneId)
            requireNotNull(result?.get("display_phone_number") as? String).take(80)
        } catch (_: Exception) { throw commercialError("WHATSAPP_CONNECTION_FAILED","Meta phone verification failed.",HttpStatus.BAD_REQUEST) }
        access.require(actor,org,"SALES_RELEASE")
        val count = if(r.baseVersion == 0L) jdbc.update(
            "INSERT INTO commercial_company_whatsapp_connection(organization_id,phone_id,display_phone,template_name,language_code,api_version,encrypted_token,version,updated_by,updated_at) VALUES (?,?,?,?,?,?,?,1,?,now()) ON CONFLICT DO NOTHING",
            org,r.phoneId,display,r.template,r.language,r.apiVersion,encrypted,actor)
        else jdbc.update("UPDATE commercial_company_whatsapp_connection SET phone_id=?,display_phone=?,template_name=?,language_code=?,api_version=?,encrypted_token=?,version=version+1,updated_by=?,updated_at=now() WHERE organization_id=? AND version=?",
            r.phoneId,display,r.template,r.language,r.apiVersion,encrypted,actor,org,r.baseVersion)
        if(count != 1) throw commercialError("WHATSAPP_CONNECTION_STALE","Reload connection settings.",HttpStatus.CONFLICT)
        return status(org,actor)
    }
    fun disconnect(r: DisconnectCommercialWhatsappRequest,actor: UUID): CommercialWhatsappConnectionStatus {
        val org = r.organizationId.commercialUuid()
        access.require(actor,org,"SALES_RELEASE")
        if(jdbc.update("DELETE FROM commercial_company_whatsapp_connection WHERE organization_id=? AND version=?",org,r.baseVersion) != 1)
            throw commercialError("WHATSAPP_CONNECTION_STALE","Reload connection settings.",HttpStatus.CONFLICT)
        return status(org,actor)
    }
    internal fun forSending(org: UUID): CommercialWhatsappCredentials? {
        val r = record(org) ?: return null
        return CommercialWhatsappCredentials(r["phone_id"] as String,secrets.decryptProviderSecret(org,"whatsapp",r["encrypted_token"] as ByteArray),
            r["template_name"] as String,r["language_code"] as String,r["api_version"] as String)
    }
}

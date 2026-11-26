package com.hiltech.server.commercial

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mail.javamail.JavaMailSenderImpl
import org.springframework.stereotype.Component
import jakarta.mail.internet.InternetAddress
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class CommercialMailConnectionStatus(val configured: Boolean, val setupAvailable: Boolean, val allowedHosts: List<String>, val host: String? = null, val username: String? = null, val version: Long = 0)
class ConnectCommercialMailRequest(val organizationId: String, val host: String, val username: String, val appPassword: String, val baseVersion: Long)
data class DisconnectCommercialMailRequest(val organizationId: String, val baseVersion: Long)

@Component
class CommercialMailConnection(
    private val jdbc: JdbcTemplate,
    private val access: CommercialAccess,
    @Value("\${hiltech.commercial.mail-encryption-key:}") private val encodedKey: String,
    @Value("\${hiltech.commercial.smtp-allowed-hosts:smtp.gmail.com,smtp.office365.com}") private val configuredHosts: String,
) {
    private val hosts = configuredHosts.split(",").map { it.trim().lowercase() }.filter { it.matches(Regex("[a-z0-9.-]+")) }.distinct()
    private fun key(): ByteArray? = runCatching { Base64.getDecoder().decode(encodedKey).takeIf { it.size == 32 } }.getOrNull()
    internal fun encryptionAvailable(): Boolean = key() != null
    internal fun encryptProviderSecret(company: UUID, purpose: String, secret: String): ByteArray = encrypt(UUID.nameUUIDFromBytes((company.toString()+":"+purpose).toByteArray(Charsets.UTF_8)),secret)
    internal fun decryptProviderSecret(company: UUID, purpose: String, data: ByteArray): String = decrypt(UUID.nameUUIDFromBytes((company.toString()+":"+purpose).toByteArray(Charsets.UTF_8)),data)
    private fun requireKey(): SecretKeySpec = SecretKeySpec(key() ?: throw commercialError("MAIL_SETUP_UNAVAILABLE","Server encryption key is not configured.",HttpStatus.SERVICE_UNAVAILABLE),"AES")
    private fun encrypt(company: UUID, secret: String): ByteArray {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,requireKey(),GCMParameterSpec(128,nonce))
        cipher.updateAAD(company.toString().toByteArray(Charsets.UTF_8))
        return nonce + cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
    }
    private fun decrypt(company: UUID, data: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,requireKey(),GCMParameterSpec(128,data.copyOfRange(0,12)))
        cipher.updateAAD(company.toString().toByteArray(Charsets.UTF_8))
        return cipher.doFinal(data.copyOfRange(12,data.size)).toString(Charsets.UTF_8)
    }
    private fun record(company: UUID) = jdbc.queryForList("SELECT host,username,encrypted_password,version FROM commercial_company_mail_connection WHERE organization_id=?",company).firstOrNull()
    fun status(company: UUID, actor: UUID): CommercialMailConnectionStatus {
        access.require(actor,company,"SALES_RELEASE")
        val row = record(company)
        return CommercialMailConnectionStatus(row != null,key() != null,hosts,row?.get("host") as? String,row?.get("username") as? String,(row?.get("version") as? Number)?.toLong() ?: 0)
    }
    private fun sender(host: String, username: String, password: String): JavaMailSenderImpl {
        if (host !in hosts) throw commercialError("MAIL_HOST_NOT_ALLOWED","SMTP host is not permitted.",HttpStatus.BAD_REQUEST)
        return JavaMailSenderImpl().apply {
            this.host = host
            this.port = 587
            this.username = username
            this.password = password
            javaMailProperties["mail.smtp.auth"] = "true"
            javaMailProperties["mail.smtp.starttls.enable"] = "true"
            javaMailProperties["mail.smtp.starttls.required"] = "true"
            javaMailProperties["mail.smtp.ssl.checkserveridentity"] = "true"
            javaMailProperties["mail.smtp.connectiontimeout"] = "10000"
            javaMailProperties["mail.smtp.timeout"] = "10000"
            javaMailProperties["mail.smtp.writetimeout"] = "10000"
        }
    }
    fun connect(r: ConnectCommercialMailRequest, actor: UUID): CommercialMailConnectionStatus {
        val company = r.organizationId.commercialUuid()
        access.require(actor,company,"SALES_RELEASE")
        val host = r.host.trim().lowercase()
        val username = r.username.trim()
        try { val address = InternetAddress(username,true); address.validate(); require(address.address == username && address.personal == null && username.length <= 254) }
        catch (_: Exception) { throw commercialError("MAIL_ADDRESS_INVALID","Invalid sender address.",HttpStatus.BAD_REQUEST) }
        if (r.appPassword.isBlank() || r.appPassword.length > 4096) throw commercialError("MAIL_CREDENTIAL_INVALID","Missing or invalid credential.",HttpStatus.BAD_REQUEST)
        val encrypted = encrypt(company,r.appPassword)
        try { sender(host,username,r.appPassword).testConnection() }
        catch (_: Exception) { throw commercialError("MAIL_CONNECTION_FAILED","SMTP authentication or connection failed.",HttpStatus.BAD_REQUEST) }
        access.require(actor,company,"SALES_RELEASE")
        val updated = if (r.baseVersion == 0L) jdbc.update(
            "INSERT INTO commercial_company_mail_connection(organization_id,host,username,encrypted_password,version,updated_by,updated_at) VALUES (?,?,?,?,1,?,now()) ON CONFLICT DO NOTHING",
            company,host,username,encrypted,actor)
        else jdbc.update("UPDATE commercial_company_mail_connection SET host=?,username=?,encrypted_password=?,version=version+1,updated_by=?,updated_at=now() WHERE organization_id=? AND version=?",
            host,username,encrypted,actor,company,r.baseVersion)
        if (updated != 1) throw commercialError("MAIL_SETTINGS_STALE","Reload connection settings.",HttpStatus.CONFLICT)
        return status(company,actor)
    }
    fun disconnect(r: DisconnectCommercialMailRequest, actor: UUID): CommercialMailConnectionStatus {
        val company = r.organizationId.commercialUuid()
        access.require(actor,company,"SALES_RELEASE")
        if (jdbc.update("DELETE FROM commercial_company_mail_connection WHERE organization_id=? AND version=?",company,r.baseVersion) != 1)
            throw commercialError("MAIL_SETTINGS_STALE","Reload connection settings.",HttpStatus.CONFLICT)
        return status(company,actor)
    }
    fun forSending(company: UUID): Pair<JavaMailSenderImpl,String>? {
        val row = record(company) ?: return null
        val username = row["username"] as String
        return sender(row["host"] as String,username,decrypt(company,row["encrypted_password"] as ByteArray)) to username
    }
}

package com.hiltech.server.commercial

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.net.URLEncoder
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import jakarta.mail.internet.InternetAddress

data class CommercialOAuthStatus(val providers: List<String>,val provider: String? = null,val email: String? = null,val version: Long = 0)
data class CommercialOAuthStartRequest(val organizationId: String,val provider: String)
data class CommercialOAuthStart(val id: String,val authorizationUrl: String)
data class CommercialOAuthPending(val status: String,val provider: String,val email: String? = null)
data class CommercialOAuthFinishRequest(val organizationId: String,val pendingId: String)
data class CommercialOAuthDisconnectRequest(val organizationId: String,val version: Long)
data class CommercialOAuthSender(val company: UUID,val provider: String,val email: String)

@Component
class CommercialMailOAuth(
    private val jdbc: JdbcTemplate,
    private val access: CommercialAccess,
    private val secrets: CommercialMailConnection,
    @Value("\${hiltech.commercial.oauth.public-base:}") private val publicBase: String,
    @Value("\${hiltech.commercial.oauth.google-client-id:}") private val googleId: String,
    @Value("\${hiltech.commercial.oauth.google-client-secret:}") private val googleSecret: String,
    @Value("\${hiltech.commercial.oauth.microsoft-client-id:}") private val microsoftId: String,
    @Value("\${hiltech.commercial.oauth.microsoft-client-secret:}") private val microsoftSecret: String,
) {
    private data class Provider(val id: String,val secret: String,val authorize: String,val token: String,val scope: String)
    private val client = RestClient.builder().requestFactory(JdkClientHttpRequestFactory(
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()).apply { setReadTimeout(Duration.ofSeconds(30)) }).build()
    private val random = SecureRandom()
    private fun randomString() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    private fun hash(value: String) = HexFormat.of().formatHex(sha(value))
    private fun enc(value: String) = URLEncoder.encode(value,Charsets.UTF_8)
    private fun provider(name: String): Provider? {
        if (!secrets.encryptionAvailable() || !runCatching { URI(publicBase).let { it.scheme == "https" && it.host != null && it.userInfo == null && it.query == null && it.fragment == null } }.getOrDefault(false)) return null
        return when(name) {
            "GOOGLE" -> Provider(googleId,googleSecret,"https://accounts.google.com/o/oauth2/v2/auth","https://oauth2.googleapis.com/token","openid email https://www.googleapis.com/auth/gmail.send")
            "MICROSOFT" -> Provider(microsoftId,microsoftSecret,"https://login.microsoftonline.com/common/oauth2/v2.0/authorize","https://login.microsoftonline.com/common/oauth2/v2.0/token","offline_access https://graph.microsoft.com/User.Read https://graph.microsoft.com/Mail.Send")
            else -> null
        }?.takeIf { it.id.isNotBlank() && it.secret.isNotBlank() }
    }
    private fun redirect() = publicBase.trimEnd('/')+"/v1/commercial/oauth/callback"
    private fun record(org: UUID) = jdbc.queryForList("SELECT provider,account_email,encrypted_refresh,version FROM commercial_mail_oauth_connection WHERE organization_id=?",org).firstOrNull()
    fun status(org: UUID,actor: UUID): CommercialOAuthStatus {
        access.require(actor,org,"SALES_RELEASE")
        val r = record(org)
        return CommercialOAuthStatus(listOf("GOOGLE","MICROSOFT").filter { provider(it)!=null },r?.get("provider") as? String,r?.get("account_email") as? String,(r?.get("version") as? Number)?.toLong() ?: 0)
    }
    fun start(r: CommercialOAuthStartRequest,actor: UUID): CommercialOAuthStart {
        val org = r.organizationId.commercialUuid()
        access.require(actor,org,"SALES_RELEASE")
        val p = provider(r.provider) ?: throw commercialError("OAUTH_NOT_CONFIGURED","Provider is not configured.",HttpStatus.SERVICE_UNAVAILABLE)
        val id = UUID.randomUUID()
        val state = randomString()
        val verifier = randomString()
        jdbc.update("DELETE FROM commercial_mail_oauth_pending WHERE expires_at < now() OR (organization_id=? AND actor_id=?)",org,actor)
        jdbc.update("INSERT INTO commercial_mail_oauth_pending(id,organization_id,actor_id,provider,state_hash,encrypted_verifier,expires_at,status,base_version) VALUES (?,?,?,?,?,?,now()+interval '10 minutes','PENDING',?)",id,org,actor,r.provider,hash(state),secrets.encryptProviderSecret(org,"oauth-verifier:$id",verifier),(record(org)?.get("version") as? Number)?.toLong() ?: 0L)
        val query = linkedMapOf("client_id" to p.id,"redirect_uri" to redirect(),"response_type" to "code","scope" to p.scope,"state" to state,"code_challenge_method" to "S256","code_challenge" to Base64.getUrlEncoder().withoutPadding().encodeToString(sha(verifier)),"prompt" to "consent")
        if(r.provider=="GOOGLE") query["access_type"]="offline"
        return CommercialOAuthStart(id.toString(),p.authorize+"?"+query.entries.joinToString("&") { enc(it.key)+"="+enc(it.value) })
    }
    private fun token(p: Provider,params: Map<String,String>): Map<*,*> {
        val form = LinkedMultiValueMap<String,String>()
        (params+mapOf("client_id" to p.id,"client_secret" to p.secret)).forEach { (k,v)->form.add(k,v) }
        return requireNotNull(client.post().uri(p.token).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(Map::class.java))
    }
    fun callback(state: String?,code: String?,error: String?): Boolean {
        if(state == null || !state.matches(Regex("[A-Za-z0-9_-]{43}"))) return false
        val row = jdbc.queryForList("SELECT id,organization_id,actor_id,provider,encrypted_verifier FROM commercial_mail_oauth_pending WHERE state_hash=? AND status='PENDING' AND expires_at>now()",hash(state)).firstOrNull() ?: return false
        val id = row["id"] as UUID
        val org = row["organization_id"] as UUID
        if(jdbc.update("UPDATE commercial_mail_oauth_pending SET status='EXCHANGING' WHERE id=? AND status='PENDING' AND expires_at>now()",id)!=1) return false
        return try {
            require(error == null && !code.isNullOrBlank() && code.length <= 8192)
            access.require(row["actor_id"] as UUID,org,"SALES_RELEASE")
            val name = row["provider"] as String
            val p = requireNotNull(provider(name))
            val verifier = secrets.decryptProviderSecret(org,"oauth-verifier:$id",row["encrypted_verifier"] as ByteArray)
            val tokens = token(p,mapOf("grant_type" to "authorization_code","code" to code,"redirect_uri" to redirect(),"code_verifier" to verifier))
            val at = requireNotNull(tokens["access_token"] as? String)
            val rt = requireNotNull(tokens["refresh_token"] as? String)
            val endpoint = if(name=="GOOGLE") "https://openidconnect.googleapis.com/v1/userinfo" else "https://graph.microsoft.com/v1.0/me?\u0024select=mail,userPrincipalName"
            val profile = requireNotNull(client.get().uri(endpoint).headers { it.setBearerAuth(at) }.retrieve().body(Map::class.java))
            if(name=="GOOGLE") require(profile["email_verified"] == true)
            val email = if(name=="GOOGLE") profile["email"] as? String else (profile["mail"] as? String)?.takeIf { it.isNotBlank() } ?: profile["userPrincipalName"] as? String
            val address = requireNotNull(email)
            InternetAddress(address,true).validate()
            require(address.length<=254)
            jdbc.update("UPDATE commercial_mail_oauth_pending SET status='READY',account_email=?,encrypted_refresh=? WHERE id=? AND status='EXCHANGING' AND expires_at>now()",address,secrets.encryptProviderSecret(org,"oauth-refresh",rt),id)==1
        } catch (_: Exception) {
            jdbc.update("UPDATE commercial_mail_oauth_pending SET status='FAILED' WHERE id=? AND status='EXCHANGING'",id)
            false
        }
    }
    private fun pending(org: UUID,id: UUID,actor: UUID): Map<String,Any?> {
        access.require(actor,org,"SALES_RELEASE")
        return jdbc.queryForList("SELECT provider,status,account_email,encrypted_refresh,base_version FROM commercial_mail_oauth_pending WHERE id=? AND organization_id=? AND actor_id=? AND expires_at>now()",id,org,actor).firstOrNull()
            ?: throw commercialError("OAUTH_REQUEST_EXPIRED","Connection request expired.",HttpStatus.NOT_FOUND)
    }
    fun pendingStatus(org: UUID,id: UUID,actor: UUID): CommercialOAuthPending {
        val r = pending(org,id,actor)
        return CommercialOAuthPending(r["status"] as String,r["provider"] as String,r["account_email"] as? String)
    }
    @org.springframework.transaction.annotation.Transactional
    fun finish(r: CommercialOAuthFinishRequest,actor: UUID): CommercialOAuthStatus {
        val org = r.organizationId.commercialUuid(); val id = r.pendingId.commercialUuid()
        jdbc.queryForObject("SELECT id FROM organization WHERE id=? FOR UPDATE",UUID::class.java,org)
        val row = pending(org,id,actor)
        if(row["status"]!="READY") throw commercialError("OAUTH_NOT_READY","Confirm a completed provider authorization.",HttpStatus.CONFLICT)
        val old = (record(org)?.get("version") as? Number)?.toLong() ?: 0
        if(old!=(row["base_version"] as Number).toLong()) throw commercialError("OAUTH_STALE","Connection changed; start again.",HttpStatus.CONFLICT)
        jdbc.update("INSERT INTO commercial_mail_oauth_connection(organization_id,provider,account_email,encrypted_refresh,version,updated_at,updated_by) VALUES (?,?,?,?,?,now(),?) ON CONFLICT(organization_id) DO UPDATE SET provider=EXCLUDED.provider,account_email=EXCLUDED.account_email,encrypted_refresh=EXCLUDED.encrypted_refresh,version=EXCLUDED.version,updated_at=now(),updated_by=EXCLUDED.updated_by",org,row["provider"],row["account_email"],row["encrypted_refresh"],old+1,actor)
        jdbc.update("DELETE FROM commercial_mail_oauth_pending WHERE organization_id=?",org)
        return status(org,actor)
    }
    @org.springframework.transaction.annotation.Transactional
    fun disconnect(r: CommercialOAuthDisconnectRequest,actor: UUID): CommercialOAuthStatus {
        val org = r.organizationId.commercialUuid(); access.require(actor,org,"SALES_RELEASE")
        jdbc.queryForObject("SELECT id FROM organization WHERE id=? FOR UPDATE",UUID::class.java,org)
        if(jdbc.update("DELETE FROM commercial_mail_oauth_connection WHERE organization_id=? AND version=?",org,r.version)!=1) throw commercialError("OAUTH_STALE","Connection changed; reload.",HttpStatus.CONFLICT)
        jdbc.update("DELETE FROM commercial_mail_oauth_pending WHERE organization_id=?",org)
        return status(org,actor)
    }
    internal fun sender(org: UUID): CommercialOAuthSender? {
        val r = record(org) ?: return null
        val name = r["provider"] as String
        if(provider(name)==null) return null
        return CommercialOAuthSender(org,name,r["account_email"] as String)
    }
    @org.springframework.transaction.annotation.Transactional
    fun send(sender: CommercialOAuthSender,mime: ByteArray) {
        val org = sender.company
        jdbc.queryForObject("SELECT id FROM organization WHERE id=? FOR UPDATE",UUID::class.java,org)
        val r = requireNotNull(record(org))
        require(r["provider"]==sender.provider && r["account_email"]==sender.email)
        val p = requireNotNull(provider(sender.provider))
        val refresh = secrets.decryptProviderSecret(org,"oauth-refresh",r["encrypted_refresh"] as ByteArray)
        val tokens = token(p,mapOf("grant_type" to "refresh_token","refresh_token" to refresh))
        val at = requireNotNull(tokens["access_token"] as? String)
        (tokens["refresh_token"] as? String)?.let { next ->
            jdbc.update("UPDATE commercial_mail_oauth_connection SET encrypted_refresh=? WHERE organization_id=?",secrets.encryptProviderSecret(org,"oauth-refresh",next),org)
        }
        if(sender.provider=="GOOGLE") {
            client.post().uri("https://gmail.googleapis.com/gmail/v1/users/me/messages/send").headers { it.setBearerAuth(at) }
                .contentType(MediaType.APPLICATION_JSON).body(mapOf("raw" to Base64.getUrlEncoder().withoutPadding().encodeToString(mime))).retrieve().toBodilessEntity()
        } else {
            client.post().uri("https://graph.microsoft.com/v1.0/me/sendMail").headers { it.setBearerAuth(at) }
                .contentType(MediaType.TEXT_PLAIN).body(Base64.getEncoder().encodeToString(mime)).retrieve().toBodilessEntity()
        }
    }
}

package com.hiltech.server.commercial

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.URI
import java.net.http.HttpClient
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

data class MetaSignupStartRequest(val organizationId: String,val template: String,val language: String)
data class MetaSignupStart(val id: String,val url: String)
data class MetaSignupState(val status: String,val displayPhone: String? = null)
data class MetaSignupFinishRequest(val organizationId: String,val pendingId: String)
class MetaSignupBrowserResult(val state: String,val code: String,val wabaId: String,val phoneId: String)

@Component
class CommercialMetaSignup(
    private val jdbc: JdbcTemplate,
    private val access: CommercialAccess,
    private val secrets: CommercialMailConnection,
    private val connections: CommercialWhatsappConnection,
    @Value("\${hiltech.commercial.meta.public-base:}") private val publicBase: String,
    @Value("\${hiltech.commercial.meta.app-id:}") private val appId: String,
    @Value("\${hiltech.commercial.meta.app-secret:}") private val appSecret: String,
    @Value("\${hiltech.commercial.meta.config-id:}") private val configId: String,
    @Value("\${hiltech.commercial.meta.api-version:}") private val apiVersion: String,
) {
    private val client = RestClient.builder().requestFactory(JdkClientHttpRequestFactory(
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()).apply { setReadTimeout(Duration.ofSeconds(30)) }).build()
    private fun hash(s: String) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)))
    fun available() = secrets.encryptionAvailable() && appId.matches(Regex("[0-9]+")) && configId.matches(Regex("[0-9]+")) &&
        appSecret.isNotBlank() && apiVersion.matches(Regex("v[0-9]+[.][0-9]+")) &&
        runCatching { URI(publicBase).let { it.scheme=="https" && it.host!=null && it.userInfo==null && it.query==null && it.fragment==null } }.getOrDefault(false)
    fun start(r: MetaSignupStartRequest,actor: UUID): MetaSignupStart {
        val org = r.organizationId.commercialUuid()
        access.require(actor,org,"SALES_RELEASE")
        if(!available()) throw commercialError("META_SIGNUP_UNAVAILABLE","Meta app is not configured.",HttpStatus.SERVICE_UNAVAILABLE)
        if(!r.template.matches(Regex("[a-z0-9_]{1,512}")) || !r.language.matches(Regex("[a-zA-Z_]{2,20}")))
            throw commercialError("META_TEMPLATE_INVALID","Enter an approved document template and language.",HttpStatus.BAD_REQUEST)
        val id = UUID.randomUUID()
        val state = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        jdbc.update("DELETE FROM commercial_meta_signup_pending WHERE expires_at<now() OR (organization_id=? AND actor_id=?)",org,actor)
        jdbc.update("INSERT INTO commercial_meta_signup_pending(id,organization_id,actor_id,state_hash,expires_at,status,template_name,language_code,base_version) VALUES (?,?,?,?,now()+interval '15 minutes','PENDING',?,?,?)",
            id,org,actor,hash(state),r.template,r.language,connections.status(org,actor).version)
        return MetaSignupStart(id.toString(),publicBase.trimEnd('/')+"/v1/commercial/meta/launch#"+state)
    }
    fun browserPage(): String {
        if(!available()) return "<!doctype html><title>HILTECH</title><p>Meta signup is not configured.</p>"
        // No token is returned to the browser. The one-use state stays in the URL fragment.
        return """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>HILTECH | Connect WhatsApp</title></head>
<body style="font:18px system-ui;max-width:38rem;margin:8vh auto;padding:24px"><h1>Connect WhatsApp Business</h1><p>Choose your business and phone number in Meta. Then return to HILTECH to confirm the connection.</p>
<button id="connect" disabled>Continue with Meta</button><p id="status" role="status">Loading Meta...</p>
<script>
const state=location.hash.slice(1); history.replaceState(null,"",location.pathname);
let code=null, assets=null, sent=false;
const status=document.getElementById("status"), button=document.getElementById("connect");
async function complete(){
 if(sent||!code||!assets)return; sent=true;button.disabled=true;status.textContent="Verifying account...";
 try{
  const response=await fetch("complete",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({state,code,wabaId:String(assets.waba_id),phoneId:String(assets.phone_number_id)})});
  status.textContent=response.ok?"Return to HILTECH and confirm your account.":"Verification failed. Return to HILTECH and start again.";
 }catch(e){status.textContent="Connection not confirmed. Return to HILTECH.";}
}
window.addEventListener("message",event=>{
 if(!["https://www.facebook.com","https://web.facebook.com","https://business.facebook.com"].includes(event.origin))return;
 try {const data=typeof event.data==="string"?JSON.parse(event.data):event.data;
 if(data.type==="WA_EMBEDDED_SIGNUP" && data.event.startsWith("FINISH") && data.data?.phone_number_id && data.data?.waba_id){assets=data.data;complete();}
 if(data.type==="WA_EMBEDDED_SIGNUP" && data.event==="CANCEL")status.textContent="Authorization cancelled.";
 }catch(e){}
});
window.fbAsyncInit=function(){FB.init({appId:"APP_ID",version:"API_VERSION",xfbml:false});button.disabled=!/^[A-Za-z0-9_-]{43}$/.test(state);status.textContent="Ready";};
button.onclick=()=>{button.disabled=true;FB.login(response=>{code=response.authResponse?.code;if(!code){status.textContent="Authorization not completed.";button.disabled=false;}else{status.textContent="Waiting for business account information...";complete();}},
 {config_id:"CONFIG_ID",response_type:"code",override_default_response_type:true,extras:{setup:{}}});};
</script><script async defer src="https://connect.facebook.net/en_US/sdk.js"></script></body></html>"""
            .replace("APP_ID",appId).replace("API_VERSION",apiVersion).replace("CONFIG_ID",configId)
    }
    fun complete(r: MetaSignupBrowserResult): Boolean {
        if(!available() || !r.state.matches(Regex("[A-Za-z0-9_-]{43}")) || !r.phoneId.matches(Regex("[0-9]{1,40}")) ||
            !r.wabaId.matches(Regex("[0-9]{1,40}")) || r.code.isBlank() || r.code.length>8192) return false
        val row = jdbc.queryForList("SELECT id,organization_id,actor_id,template_name,language_code FROM commercial_meta_signup_pending WHERE state_hash=? AND status='PENDING' AND expires_at>now()",hash(r.state)).firstOrNull() ?: return false
        val id = row["id"] as UUID; val org = row["organization_id"] as UUID
        if(jdbc.update("UPDATE commercial_meta_signup_pending SET status='EXCHANGING' WHERE id=? AND status='PENDING' AND expires_at>now()",id)!=1) return false
        return try {
            access.require(row["actor_id"] as UUID,org,"SALES_RELEASE")
            val tokenResponse = client.get().uri("https://graph.facebook.com/{v}/oauth/access_token?client_id={app}&client_secret={secret}&code={code}",apiVersion,appId,appSecret,r.code).retrieve().body(Map::class.java)
            val token = requireNotNull(tokenResponse?.get("access_token") as? String)
            val phone = requireNotNull(client.get().uri("https://graph.facebook.com/{v}/{phone}?fields=id,display_phone_number",apiVersion,r.phoneId).headers { it.setBearerAuth(token) }.retrieve().body(Map::class.java))
            require(phone["id"]?.toString()==r.phoneId)
            val list = requireNotNull(client.get().uri("https://graph.facebook.com/{v}/{waba}/phone_numbers?fields=id&limit=100",apiVersion,r.wabaId).headers { it.setBearerAuth(token) }.retrieve().body(Map::class.java))
            require((list["data"] as? List<*>)?.any { (it as? Map<*,*>)?.get("id")?.toString()==r.phoneId }==true)
            val templates = requireNotNull(client.get().uri("https://graph.facebook.com/{v}/{waba}/message_templates?name={name}&fields=name,status,language,components&limit=100",apiVersion,r.wabaId,row["template_name"]).headers { it.setBearerAuth(token) }.retrieve().body(Map::class.java))
            require((templates["data"] as? List<*>)?.any { raw ->
                val t = raw as? Map<*,*> ?: return@any false
                val components = (t["components"] as? List<*>)?.mapNotNull { it as? Map<*,*> }.orEmpty()
                t["name"]==row["template_name"] && t["language"]==row["language_code"] && t["status"]=="APPROVED" &&
                    components.any { it["type"]=="HEADER" && it["format"]=="DOCUMENT" } &&
                    components.none { it["type"]=="BUTTONS" || (it["type"]=="BODY" && (it["text"] as? String)?.contains("{{")==true) }
            }==true)
            jdbc.update("UPDATE commercial_meta_signup_pending SET status='READY',phone_id=?,display_phone=?,encrypted_token=? WHERE id=? AND status='EXCHANGING' AND expires_at>now()",
                r.phoneId,requireNotNull(phone["display_phone_number"] as? String).take(80),secrets.encryptProviderSecret(org,"meta-pending:$id",token),id)==1
        }catch(_:Exception){
            jdbc.update("UPDATE commercial_meta_signup_pending SET status='FAILED' WHERE id=? AND status='EXCHANGING'",id)
            false
        }
    }
    private fun pending(org:UUID,id:UUID,actor:UUID): Map<String,Any?> {
        access.require(actor,org,"SALES_RELEASE")
        return jdbc.queryForList("SELECT * FROM commercial_meta_signup_pending WHERE id=? AND organization_id=? AND actor_id=? AND expires_at>now()",id,org,actor).firstOrNull()
            ?: throw commercialError("META_SIGNUP_EXPIRED","Signup expired.",HttpStatus.NOT_FOUND)
    }
    fun pendingStatus(org:UUID,id:UUID,actor:UUID): MetaSignupState {
        val r=pending(org,id,actor); return MetaSignupState(r["status"] as String,r["display_phone"] as? String)
    }
    @org.springframework.transaction.annotation.Transactional
    fun finish(r:MetaSignupFinishRequest,actor:UUID):CommercialWhatsappConnectionStatus {
        val org=r.organizationId.commercialUuid();val id=r.pendingId.commercialUuid()
        jdbc.queryForObject("SELECT id FROM organization WHERE id=? FOR UPDATE",UUID::class.java,org)
        val p=pending(org,id,actor)
        if(p["status"]!="READY")throw commercialError("META_SIGNUP_NOT_READY","Authorization not verified.",HttpStatus.CONFLICT)
        val token=secrets.decryptProviderSecret(org,"meta-pending:$id",p["encrypted_token"] as ByteArray)
        val saved=connections.connect(ConnectCommercialWhatsappRequest(org.toString(),p["phone_id"] as String,token,p["template_name"] as String,p["language_code"] as String,apiVersion,(p["base_version"] as Number).toLong()),actor)
        jdbc.update("DELETE FROM commercial_meta_signup_pending WHERE organization_id=?",org)
        return saved
    }
}

package com.hiltech.server.procurement

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.documents.EvidencePersistencePort
import com.hiltech.server.documents.EvidenceStorageAccessPort
import com.hiltech.server.documents.storage.EvidenceUploadSpec
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

data class ProtectedServiceQuoteDocument(
    val quoteId:UUID,val contentType:String,val bytes:ByteArray,
)

/** HIGHLY_RESTRICTED service quote delivery, no direct object link. */
@Service
class ServiceQuoteProtectedDocumentService @org.springframework.beans.factory.annotation.Autowired constructor(
    private val jdbc:JdbcTemplate,
    private val evidence:EvidencePersistencePort,
    private val targetAccess:ServiceQuoteDocumentTargetAdapter,
    private val storageAccess:EvidenceStorageAccessPort,
    private val audit:AuditEventWriter,
    private val clock:Clock,
    @Value("\${hiltech.procurement.service-sourcing.enabled:false}")
    private val enabled:Boolean=false,
) {
    private data class Source(
        val id:UUID,val slot:UUID,val rfq:UUID,val org:UUID,
        val supplier:UUID,val evidence:UUID,val sha:String,
    )
    fun read(actor:UUID,id:UUID,correlation:String):ProtectedServiceQuoteDocument {
        if(!enabled) hidden()
        val src=jdbc.query(
            """
            SELECT id,slot_id,rfq_id,organization_id,supplier_organization_id,evidence_id,sha256
            FROM procurement_service_supplier_quote WHERE id=?
            """.trimIndent(),
            {rs,_->Source(rs.getObject("id",UUID::class.java),
                rs.getObject("slot_id",UUID::class.java),
                rs.getObject("rfq_id",UUID::class.java),
                rs.getObject("organization_id",UUID::class.java),
                rs.getObject("supplier_organization_id",UUID::class.java),
                rs.getObject("evidence_id",UUID::class.java),rs.getString("sha256"))},id,
        ).singleOrNull()?:hidden()
        val target=targetAccess.loadTarget(src.slot)?:hidden()
        if(target.slotId!=src.slot || target.rfqId!=src.rfq ||
            target.purchasingOrganizationId!=src.org ||
            target.supplierOrganizationId!=src.supplier ||
            !targetAccess.canAccess(actor,target)) hidden()
        val e=evidence.loadEvidenceRecord(src.evidence)?:hidden()
        if(e.evidenceId!=src.evidence || e.organizationId!=src.org ||
            e.targetType!="SERVICE_QUOTE_DOCUMENT" || e.targetId!=src.slot ||
            e.evidenceRequirementKey!="SERVICE_QUOTE_BINARY" ||
            e.evidenceTypeCode!="SERVICE_QUOTE" ||
            e.storageState!="READY" || e.uploadState!="READY" ||
            e.classificationCode!="HIGHLY_RESTRICTED" ||
            e.clientVisibilityMode!="INTERNAL_ONLY" ||
            e.expectedSha256!=src.sha ||
            e.expectedSizeBytes !in 1..EvidenceUploadSpec.MAX_EVIDENCE_OBJECT_BYTES) hidden()
        val scan=when(e.contentType) {
            "application/pdf"->"SIGNED_DOCUMENT"
            "image/jpeg","image/png","image/webp"->"NATIVE_MEDIA"
            else->hidden()
        }
        if(e.securityScanClass!=scan) hidden()
        val prefix="org/"+src.org+"/evidence/"+src.evidence+"/objects/"
        if(!e.objectKey.startsWith(prefix) ||
            runCatching {UUID.fromString(e.objectKey.removePrefix(prefix))}.isFailure) hidden()
        val delivered=try {
            storageAccess.readRestrictedObject(e.objectKey,e.expectedSizeBytes)
        } catch (_:Exception) {
            throw ProductApiException("SERVICE_QUOTE_STORAGE_UNAVAILABLE",
                "Protected service quote is temporarily unavailable.",
                HttpStatus.SERVICE_UNAVAILABLE)
        }
        val bytes=delivered?:hidden()
        if(bytes.size.toLong()!=e.expectedSizeBytes ||
            !MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(bytes),
                toBytes(src.sha)) || !magic(bytes,e.contentType)) hidden()
        audit.append(AuditEventRecord(
            actorUserId=actor,action="PROCUREMENT_SERVICE_QUOTE_DOCUMENT_READ",
            targetType="SERVICE_SUPPLIER_QUOTE",targetId=id,
            occurredAt=clock.instant(),correlationId=correlation,
            reason="PROTECTED_SERVER_DELIVERY"))
        return ProtectedServiceQuoteDocument(id,e.contentType,bytes)
    }
    private fun toBytes(s:String):ByteArray {
        if(!Regex("^[0-9a-f]{64}$").matches(s))hidden()
        return ByteArray(32) {s.substring(it*2,it*2+2).toInt(16).toByte()}
    }
    private fun magic(b:ByteArray,t:String)=when(t) {
        "application/pdf"->b.size>=5 && b.copyOfRange(0,5).decodeToString()=="%PDF-"
        "image/jpeg"->b.size>=3 && b[0]==0xff.toByte() &&
            b[1]==0xd8.toByte() && b[2]==0xff.toByte()
        "image/png"->b.size>=8 && b.sliceArray(0..7).contentEquals(byteArrayOf(
            0x89.toByte(),0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a))
        "image/webp"->b.size>=12 && b.copyOfRange(0,4).decodeToString()=="RIFF" &&
            b.copyOfRange(8,12).decodeToString()=="WEBP"
        else->false
    }
    private fun hidden():Nothing=throw ProductApiException(
        "SERVICE_QUOTE_DOCUMENT_NOT_VISIBLE","Service quote is not available.",
        HttpStatus.NOT_FOUND)
}

@RestController
class ServiceQuoteProtectedDocumentController(
    private val service:ServiceQuoteProtectedDocumentService,
) {
    @PostMapping("/v1/service-procurement/quotes/{id}/protected-document")
    fun read(req:HttpServletRequest,@PathVariable id:UUID):ResponseEntity<ByteArray> {
        val context=HiltechRequestContext.current(req)
        val actor=context.identityId?.let {
            runCatching {UUID.fromString(it)}.getOrNull()
        }?:throw ProductApiException("UNAUTHENTICATED","Authentication required.",
            HttpStatus.UNAUTHORIZED)
        val result=service.read(actor,id,context.correlationId)
        val ext=when(result.contentType) {
            "application/pdf"->"pdf"
            "image/jpeg"->"jpg"
            "image/png"->"png"
            "image/webp"->"webp"
            else->throw IllegalStateException("Unexpected protected content type")
        }
        val headers=HttpHeaders()
        headers.contentType=MediaType.parseMediaType(result.contentType)
        headers.contentLength=result.bytes.size.toLong()
        headers.cacheControl=CacheControl.noStore().headerValue
        headers.set("Pragma","no-cache")
        headers.set("X-Content-Type-Options","nosniff")
        headers.contentDisposition=ContentDisposition.attachment()
            .filename("service-quote-"+id+"."+ext).build()
        return ResponseEntity.status(HttpStatus.OK).headers(headers).body(result.bytes)
    }
}

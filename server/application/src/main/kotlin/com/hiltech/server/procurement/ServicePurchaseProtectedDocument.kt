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
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

data class ProtectedServicePurchaseDocument(
    val evidenceId:UUID,val kind:String,val contentType:String,val bytes:ByteArray,
)

/**
 * Only the currently assigned acceptance reviewer or Finance authority can
 * retrieve actual immutable service deliverable/invoice bytes. No S3 URL,
 * generic Evidence presign, cross-organization or buyer-price bypass.
 */
@Service
class ServicePurchaseProtectedDocumentService @org.springframework.beans.factory.annotation.Autowired constructor(
    private val evidence:EvidencePersistencePort,
    private val slots:ServicePurchaseDocumentTargetAdapter,
    private val storageAccess:EvidenceStorageAccessPort,
    private val audit:AuditEventWriter,
    private val clock:Clock,
    @Value("\${hiltech.procurement.service-orders.enabled:false}")
    private val enabled:Boolean=false,
) {
    fun read(actor:UUID,id:UUID,correlation:String):ProtectedServicePurchaseDocument {
        if(!enabled)hidden()
        val row=evidence.loadEvidenceRecord(id)?:hidden()
        if(row.evidenceId!=id || row.targetType!="SERVICE_PURCHASE_DOCUMENT" ||
            row.evidenceRequirementKey !in setOf(
                "SERVICE_DELIVERY_BINARY","SERVICE_INVOICE_BINARY") ||
            row.evidenceTypeCode !in setOf("SERVICE_DELIVERY","SERVICE_INVOICE") ||
            row.storageState!="READY" || row.uploadState!="READY" ||
            row.classificationCode!="HIGHLY_RESTRICTED" ||
            row.clientVisibilityMode!="INTERNAL_ONLY" ||
            row.expectedSizeBytes !in 1..EvidenceUploadSpec.MAX_EVIDENCE_OBJECT_BYTES)hidden()
        val target=slots.loadTarget(row.targetId)?:hidden()
        val expected=when(target.documentKind) {
            "DELIVERY" -> "SERVICE_DELIVERY"
            "INVOICE" -> "SERVICE_INVOICE"
            else -> hidden()
        }
        if(row.organizationId!=target.organizationId ||
            row.evidenceRequirementKey!=expected+"_BINARY" ||
            row.evidenceTypeCode!=expected ||
            !slots.canAccess(actor,target))hidden()
        val scan=when(row.contentType) {
            "application/pdf"->"SIGNED_DOCUMENT"
            "image/jpeg","image/png","image/webp"->"NATIVE_MEDIA"
            else->hidden()
        }
        if(row.securityScanClass!=scan)hidden()
        val prefix="org/"+row.organizationId+"/evidence/"+row.evidenceId+"/objects/"
        if(!row.objectKey.startsWith(prefix) ||
            runCatching {UUID.fromString(row.objectKey.removePrefix(prefix))}.isFailure) hidden()
        val storage=storageAccess.current()?:hidden()
        val bytes=try {
            storage.readRestrictedObject(row.objectKey,row.expectedSizeBytes)
        } catch (_:Exception) {
            throw ProductApiException(
                "SERVICE_DOCUMENT_STORAGE_UNAVAILABLE",
                "Protected service document is temporarily unavailable.",
                HttpStatus.SERVICE_UNAVAILABLE)
        }
        if(bytes.size.toLong()!=row.expectedSizeBytes ||
            !MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(bytes),
                shaBytes(row.expectedSha256)) || !validMagic(bytes,row.contentType))hidden()
        audit.append(AuditEventRecord(
            actorUserId=actor,action="PROCUREMENT_SERVICE_DOCUMENT_PROTECTED_READ",
            targetType="SERVICE_PURCHASE_DOCUMENT",targetId=id,
            occurredAt=clock.instant(),correlationId=correlation,
            reason="PROTECTED_SERVER_DELIVERY"))
        return ProtectedServicePurchaseDocument(id,target.documentKind,row.contentType,bytes)
    }
    private fun shaBytes(s:String):ByteArray {
        if(!Regex("^[0-9a-f]{64}$").matches(s))hidden()
        return ByteArray(32) {s.substring(it*2,it*2+2).toInt(16).toByte()}
    }
    private fun validMagic(b:ByteArray,t:String)=when(t) {
        "application/pdf" -> b.size>=5 && b.copyOfRange(0,5).decodeToString()=="%PDF-"
        "image/jpeg" -> b.size>=3 && b[0]==0xff.toByte() &&
            b[1]==0xd8.toByte() && b[2]==0xff.toByte()
        "image/png" -> b.size>=8 && b.sliceArray(0..7).contentEquals(byteArrayOf(
            0x89.toByte(),0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a))
        "image/webp" -> b.size>=12 && b.copyOfRange(0,4).decodeToString()=="RIFF" &&
            b.copyOfRange(8,12).decodeToString()=="WEBP"
        else -> false
    }
    private fun hidden():Nothing=throw ProductApiException(
        "SERVICE_DOCUMENT_NOT_VISIBLE","Protected service document unavailable.",
        HttpStatus.NOT_FOUND)
}

@RestController
class ServicePurchaseProtectedDocumentController(
    private val delivery:ServicePurchaseProtectedDocumentService,
) {
    @PostMapping("/v1/service-procurement/evidence/{id}/protected-document")
    fun read(req:HttpServletRequest,@PathVariable id:UUID):ResponseEntity<ByteArray> {
        val c=HiltechRequestContext.current(req)
        val actor=c.identityId?.let {runCatching {UUID.fromString(it)}.getOrNull()}
            ?:throw ProductApiException("UNAUTHENTICATED","Authentication required.",
                HttpStatus.UNAUTHORIZED)
        val file=delivery.read(actor,id,c.correlationId)
        val ext=when(file.contentType) {
            "application/pdf"->"pdf"
            "image/jpeg"->"jpg"
            "image/png"->"png"
            "image/webp"->"webp"
            else->throw IllegalStateException("Invalid protected file type")
        }
        val headers=HttpHeaders()
        headers.contentType=MediaType.parseMediaType(file.contentType)
        headers.contentLength=file.bytes.size.toLong()
        headers.cacheControl=CacheControl.noStore().headerValue
        headers.set("Pragma","no-cache")
        headers.set("X-Content-Type-Options","nosniff")
        headers.contentDisposition=ContentDisposition.attachment()
            .filename("service-"+file.kind.lowercase()+"-"+id+"."+ext).build()
        return ResponseEntity.status(HttpStatus.OK).headers(headers).body(file.bytes)
    }
}

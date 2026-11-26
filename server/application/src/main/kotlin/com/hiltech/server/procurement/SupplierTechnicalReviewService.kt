package com.hiltech.server.procurement

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID
import kotlinx.serialization.encodeToString

@Service
class SupplierTechnicalReviewService(
    private val repository: SupplierTechnicalReviewRepository,
    private val source: SupplierTechnicalReviewSource,
    private val sourcing: SupplierSourcingPersistence,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
) {
    @Transactional(readOnly=true)
    fun queue(actor: UUID, org: UUID, search: String, offset: Int, limit: Int): SupplierTechnicalQueueDto {
        source.requireOrganization(actor,org)
        if (search.length>160 || offset !in 0..100000 || limit !in 1..100) technicalFail("SUPPLIER_TECHNICAL_INPUT_INVALID",HttpStatus.BAD_REQUEST)
        val rows = repository.queue(org,search.trim(),offset,limit+1)
        val effective = rows.map { it.rfqId }.distinct().associateWith(sourcing::latestTechnicalAssessments)
        val items = rows.take(limit).map { q -> SupplierTechnicalQueueItemDto(source.describe(q),repository.latest(q.id)?.revision ?: 0,effective[q.rfqId]?.get(q.id) ?: "NOT_ASSESSED") }
        source.requireOrganization(actor,org)
        return SupplierTechnicalQueueDto(org.toString(),source.canPrepare(actor,org),source.canReview(actor,org),items,if (rows.size>limit) offset+limit else null)
    }
    @Transactional(readOnly=true)
    fun detail(actor: UUID, quoteId: UUID): SupplierTechnicalDetailDto {
        val quote = source.accessibleQuote(actor,quoteId)
        val described = source.describe(quote)
        val prepare = source.canPrepare(actor,quote.organizationId)
        val packets = repository.packets(quoteId).map { packet ->
            val documents = repository.proofReferences(packet.id).mapNotNull { (id,sha) -> repository.evidence(quote.organizationId,quoteId,id)?.takeIf { it.sha == sha }?.dto() }
            SupplierTechnicalPacketDto(packet.id.toString(),quote.organizationId.toString(),quoteId.toString(),packet.revision,packet.need,packet.details,packet.hash,
                repository.name(packet.creator),packet.createdAt.toString(),documents,repository.displayed(actor,packet),source.current(packet))
        }
        val history = repository.history(quoteId)
        source.accessibleQuote(actor,quoteId)
        return SupplierTechnicalDetailDto(described,prepare,source.canReview(actor,quote.organizationId) && described.sourceCurrent && described.latestQuote,packets,
            if (prepare) repository.readyDocuments(quote.organizationId,quoteId).map { it.dto() } else emptyList(),history,
            history.maxOfOrNull { it.reviewRevision } ?: 0,sourcing.latestTechnicalAssessments(quote.rfqId)[quoteId] ?: "NOT_ASSESSED")
    }
    fun createDocument(actor: UUID, quoteId: UUID, request: SupplierTechnicalDocumentRequest, correlation: String): SupplierTechnicalDocumentSlotDto {
        val quote = source.accessibleQuote(actor,quoteId,prepare=true)
        val title = request.title.trim()
        if (title.length !in 1..240 || title.any { it.code<32 || it.code==127 }) technicalFail("SUPPLIER_TECHNICAL_INPUT_INVALID",HttpStatus.BAD_REQUEST)
        val operation = technicalUuid(request.operationId); val id = derived("document",operation)
        val result = commands.execute(spec(operation,actor,"SUPPLIER_TECHNICAL_DOCUMENT_PREPARED",id,listOf(quoteId,title),correlation)) {
            if (!sourcing.lockRfq(quote.rfqId)) technicalFail("SUPPLIER_TECHNICAL_SOURCE_CHANGED")
            val (current,_) = source.currentQuote(actor,quoteId)
            repository.insertSlot(id,operation,current,title,actor,clock.instant())
            event(actor,"SUPPLIER_TECHNICAL_DOCUMENT_PREPARED",id,quoteId,correlation)
            IdempotentCommandOutcome("SUPPLIER_TECHNICAL_DOCUMENT_PREPARED")
        }
        val saved = repository.slot(id)?.takeIf { it.quoteId==quoteId && it.organizationId==quote.organizationId } ?: technicalFail("SUPPLIER_TECHNICAL_NOT_VISIBLE",HttpStatus.NOT_FOUND)
        return SupplierTechnicalDocumentSlotDto(operation.toString(),quote.organizationId.toString(),quoteId.toString(),id.toString(),saved.title,result.replayed)
    }
    fun publish(actor: UUID, quoteId: UUID, request: SupplierTechnicalPublishRequest, correlation: String): SupplierTechnicalPacketReceiptDto {
        val quote = source.accessibleQuote(actor,quoteId,prepare=true)
        val operation = technicalUuid(request.operationId); val id = derived("packet",operation)
        if (request.expectedLatestRevision !in 0 until Int.MAX_VALUE) technicalFail("SUPPLIER_TECHNICAL_INPUT_INVALID",HttpStatus.BAD_REQUEST)
        val fields = source.normalized(request.details)
        val result = commands.execute(spec(operation,actor,"SUPPLIER_TECHNICAL_PACKET_PUBLISHED",id,listOf(quoteId,request.expectedLatestRevision,technicalJson.encodeToString(fields)),correlation)) {
            if (!sourcing.lockRfq(quote.rfqId)) technicalFail("SUPPLIER_TECHNICAL_SOURCE_CHANGED")
            val (current,described) = source.currentQuote(actor,quoteId)
            if ((repository.latest(quoteId)?.revision ?: 0)!=request.expectedLatestRevision) technicalFail("SUPPLIER_TECHNICAL_SOURCE_CHANGED")
            repository.lockEvidence(fields.evidenceIds.map(::technicalUuid))
            val proofs = source.proofs(current,fields)
            val hash = SupplierTechnicalPacketRules.hash(described,fields,proofs)
            repository.insertPacket(SupplierTechnicalPacket(id,current.organizationId,quoteId,current.rfqId,request.expectedLatestRevision+1,current.needVersion,
                described,fields,hash,actor,clock.instant()),operation,proofs)
            event(actor,"SUPPLIER_TECHNICAL_PACKET_PUBLISHED",id,quoteId,correlation)
            IdempotentCommandOutcome("SUPPLIER_TECHNICAL_PACKET_PUBLISHED")
        }
        val packet = repository.packet(quote.organizationId,quoteId,id) ?: technicalFail("SUPPLIER_TECHNICAL_NOT_VISIBLE",HttpStatus.NOT_FOUND)
        return SupplierTechnicalPacketReceiptDto(operation.toString(),quote.organizationId.toString(),quoteId.toString(),id.toString(),packet.revision,"PUBLISHED_FOR_TECHNICAL_REVIEW",result.replayed)
    }
    private fun derived(kind: String, op: UUID) = UUID.nameUUIDFromBytes("hiltech:supplier-technical:$kind:$op".toByteArray(Charsets.UTF_8))
    private fun spec(op: UUID, actor: UUID, command: String, target: UUID, values: List<Any?>, correlation: String) = IdempotentCommandSpec(op,actor,command,"SUPPLIER_TECHNICAL_PACKET",target,
        IdempotencyKeyContract.fingerprint(values.joinToString("\u001f")),correlation)
    private fun event(actor: UUID, action: String, id: UUID, quote: UUID, correlation: String) = audit.append(AuditEventRecord(
        actorUserId=actor,action=action,targetType="SUPPLIER_TECHNICAL_PACKET",targetId=id,occurredAt=clock.instant(),correlationId=correlation,
        safeDiffJson="""{"source":"NONFINANCIAL_TECHNICAL_PACKET","quoteId":"$quote","purchaseAuthorized":false}"""))
}

package com.hiltech.server.procurement

import com.hiltech.server.platform.ProductApiException
import kotlinx.serialization.encodeToString
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

internal fun technicalFail(code: String, status: HttpStatus = HttpStatus.CONFLICT): Nothing =
    throw ProductApiException(code, "Review the current technical packet, source and responsibility.", status)
internal fun technicalUuid(value: String): UUID = value.takeIf { Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(it) }
    ?.let(UUID::fromString) ?: technicalFail("SUPPLIER_TECHNICAL_INPUT_INVALID", HttpStatus.BAD_REQUEST)
internal fun technicalHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal object SupplierTechnicalPacketRules {
    fun normalize(input: SupplierTechnicalPacketFields, today: LocalDate): SupplierTechnicalPacketFields {
        fun text(value: String, min: Int, max: Int) = value.trim().also {
            if (it.length !in min..max || it.any { c -> c.code == 127 || (c.code < 32 && c != '\n') }) technicalFail("SUPPLIER_TECHNICAL_INPUT_INVALID", HttpStatus.BAD_REQUEST)
        }
        if (!input.nonFinancialConfirmed || input.evidenceIds.size > 10) technicalFail("SUPPLIER_TECHNICAL_INPUT_INVALID", HttpStatus.BAD_REQUEST)
        val ids = input.evidenceIds.map { technicalUuid(it).toString() }.sorted()
        if (ids.distinct().size != ids.size) technicalFail("SUPPLIER_TECHNICAL_INPUT_INVALID", HttpStatus.BAD_REQUEST)
        val date = input.sourceDate?.let { raw ->
            val parsed = runCatching { LocalDate.parse(raw) }.getOrNull() ?: technicalFail("SUPPLIER_TECHNICAL_INPUT_INVALID", HttpStatus.BAD_REQUEST)
            if (parsed.year !in 1900..2200 || parsed > today) technicalFail("SUPPLIER_TECHNICAL_INPUT_INVALID", HttpStatus.BAD_REQUEST)
            parsed.toString()
        }
        return SupplierTechnicalPacketFields(text(input.manufacturer,0,160),text(input.model,0,240),text(input.offeredSpecification,8,8000),
            text(input.deviations,1,4000),text(input.supplierSource,1,240),text(input.sourceReference,1,1000),date,ids,true,text(input.correctionReason,8,1000))
    }
    fun needToken(q: SupplierTechnicalQuoteDto): String = listOf(q.quoteId,q.organizationId,q.rfqId,q.supplierId,q.quoteRevision,q.itemId,q.requirementId,q.needVersion,
        q.requestedQuantity,q.requestedUnit,q.offeredQuantity,q.offeredUnit,q.sourceType,q.needDescription).joinToString("\u001f")
    fun hash(q: SupplierTechnicalQuoteDto, fields: SupplierTechnicalPacketFields, proofs: List<SupplierTechnicalEvidenceSource>): String =
        technicalHash((needToken(q) + "\u001f" + technicalJson.encodeToString(fields) + "\u001f" + proofs.sortedBy { it.id }.joinToString("\u001f") { "${it.id}|${it.sha}" }).toByteArray(Charsets.UTF_8))
}

@Component
class SupplierTechnicalReviewSource(
    private val repository: SupplierTechnicalReviewRepository,
    private val requirements: PurchaseRequirementDraftPersistence,
    private val verifier: ProcurementDemandSourceVerifier,
    private val buyer: SupplierSourcingAuthorizationPort,
    private val reviewer: SupplierQuoteTechnicalReviewerAuthorizationPort,
    private val clock: Clock,
) : SupplierTechnicalAssessmentSourcePort {
    internal fun canPrepare(actor: UUID, org: UUID) = buyer.canManageSourcing(actor,org)
    internal fun canReview(actor: UUID, org: UUID) = reviewer.canReviewOrganization(actor,org)
    internal fun requireOrganization(actor: UUID, org: UUID) {
        if (!canPrepare(actor,org) && !canReview(actor,org)) technicalFail("SUPPLIER_TECHNICAL_NOT_VISIBLE", HttpStatus.NOT_FOUND)
    }
    internal fun accessibleQuote(actor: UUID, id: UUID, prepare: Boolean = false): SupplierTechnicalQuoteSource {
        val quote = repository.quote(id) ?: technicalFail("SUPPLIER_TECHNICAL_NOT_VISIBLE", HttpStatus.NOT_FOUND)
        if (prepare) {
            if (!canPrepare(actor,quote.organizationId)) technicalFail("SUPPLIER_TECHNICAL_NOT_VISIBLE", HttpStatus.NOT_FOUND)
        } else requireOrganization(actor,quote.organizationId)
        return quote
    }
    internal fun currentQuote(actor: UUID, id: UUID, prepare: Boolean = true): Pair<SupplierTechnicalQuoteSource,SupplierTechnicalQuoteDto> {
        val source = accessibleQuote(actor,id,prepare)
        val dto = describe(source)
        if (!dto.sourceCurrent || !dto.latestQuote) technicalFail("SUPPLIER_TECHNICAL_SOURCE_CHANGED")
        return source to dto
    }
    internal fun describe(quote: SupplierTechnicalQuoteSource): SupplierTechnicalQuoteDto {
        val root = requirements.byId(quote.requirementId) ?: technicalFail("SUPPLIER_TECHNICAL_SOURCE_CHANGED")
        val last = requirements.latestTransition(root.id)
        val version = last?.resultVersion ?: root.version
        val quantity = last?.revised?.quantity ?: root.requestedQuantity
        val unit = last?.revised?.unitCode ?: root.unitCode
        val current = root.organizationId == quote.organizationId && root.stockItemId == quote.itemId && version == quote.needVersion &&
            last?.toState == "READY_FOR_SOURCING" && quantity.compareTo(quote.requestedQuantity) == 0 && unit == quote.requestedUnit && verifier.isCurrent(root,last)
        return SupplierTechnicalQuoteDto(quote.id.toString(),quote.organizationId.toString(),quote.rfqId.toString(),quote.supplierId.toString(),quote.supplierName,quote.supplierCode,
            quote.reference,quote.revision,quote.itemId.toString(),quote.itemName,quote.itemCode,quote.requirementId.toString(),quote.needVersion,
            quote.requestedQuantity.toPlainString(),quote.requestedUnit,quote.quantity.toPlainString(),quote.unit,root.sourceType,last?.revised?.justification ?: root.justification,quote.sourceLabel,current,quote.latest)
    }
    internal fun proofs(quote: SupplierTechnicalQuoteSource, fields: SupplierTechnicalPacketFields): List<SupplierTechnicalEvidenceSource> = fields.evidenceIds.map { id ->
        repository.evidence(quote.organizationId,quote.id,technicalUuid(id)) ?: technicalFail("SUPPLIER_TECHNICAL_ORIGINAL_UNAVAILABLE")
    }
    internal fun current(packet: SupplierTechnicalPacket): Boolean {
        val quote = repository.quote(packet.quoteId)?.takeIf { it.organizationId == packet.organizationId } ?: return false
        val current = describe(quote)
        if (!current.sourceCurrent || !current.latestQuote || repository.latest(quote.id)?.id != packet.id || SupplierTechnicalPacketRules.needToken(current) != SupplierTechnicalPacketRules.needToken(packet.need)) return false
        val references = repository.proofReferences(packet.id)
        if (references.map { it.first.toString() }.toSet() != packet.details.evidenceIds.toSet()) return false
        val documents = references.map { (id, hash) -> repository.evidence(packet.organizationId,packet.quoteId,id)?.takeIf { it.sha == hash } ?: return false }
        return SupplierTechnicalPacketRules.hash(current,packet.details,documents) == packet.hash
    }
    override fun requireCurrentAssessment(actor: UUID, quoteId: UUID, packetId: UUID?, packetReviewed: Boolean, decision: String) {
        val (quote,currentQuote) = currentQuote(actor,quoteId,prepare=false)
        if (!reviewer.canReviewQuote(actor,quote.organizationId,quote.rfqId)) technicalFail("SUPPLIER_TECHNICAL_NOT_VISIBLE", HttpStatus.NOT_FOUND)
        val packet = repository.latest(quoteId)
        if (packet == null) {
            if (decision != "NEEDS_CLARIFICATION" || packetId != null || packetReviewed) technicalFail("SUPPLIER_TECHNICAL_PACKET_REQUIRED")
            return
        }
        if (packet.id != packetId || SupplierTechnicalPacketRules.needToken(currentQuote) != SupplierTechnicalPacketRules.needToken(packet.need) ||
            (decision != "NEEDS_CLARIFICATION" && !current(packet))) technicalFail("SUPPLIER_TECHNICAL_SOURCE_CHANGED")
        if (decision in setOf("COMPLIANT","NON_COMPLIANT") && !packetReviewed) technicalFail("SUPPLIER_TECHNICAL_REVIEW_CONFIRMATION_REQUIRED")
        if (decision == "COMPLIANT" && !repository.displayed(actor,packet).toSet().containsAll(packet.details.evidenceIds)) technicalFail("SUPPLIER_TECHNICAL_ORIGINAL_REVIEW_REQUIRED")
    }
    internal fun normalized(fields: SupplierTechnicalPacketFields) = SupplierTechnicalPacketRules.normalize(fields,LocalDate.now(clock.withZone(ZoneOffset.UTC)))
}

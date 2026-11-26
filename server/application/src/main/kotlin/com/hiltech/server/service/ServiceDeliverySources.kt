package com.hiltech.server.service

import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.projects.ProjectAuthorizationPort
import com.hiltech.server.projects.ProjectSnapshot
import com.hiltech.server.projects.ProjectsPersistencePort
import kotlinx.serialization.encodeToString
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

internal fun deliveryFail(code: String, status: HttpStatus = HttpStatus.CONFLICT): Nothing =
    throw ProductApiException(code, "Review the current delivery source, agreement and responsibility.", status)
internal fun deliveryUuid(value: String?): UUID = value?.takeIf { Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(it) }?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    ?: deliveryFail("SERVICE_DELIVERY_INPUT_INVALID", HttpStatus.BAD_REQUEST)
internal fun deliveryHash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
internal fun deliveryVersion(text: String): Long = (ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)), 0, 8).long and Long.MAX_VALUE).coerceAtLeast(1)

internal data class ServiceDeliveryResolvedSource(
    val project: ProjectSnapshot, val stream: String, val details: ServiceDeliveryDetails, val baseEventNumber: Int,
    val stamp: String, val scopeName: String?, val proofs: List<ServiceDeliveryProposalProof>,
    val packageDocuments: List<HandoverDocumentDto>,
    val packageFindings: List<HandoverSourceFindingDto>,
)

/** Business assertions remain explicit. Written text never becomes an automatic warranty entitlement. */
internal object ServiceDeliveryRules {
    val actions = setOf("HANDOVER_RELEASE", "HANDOVER_CLIENT_RESPONSE", "HANDOVER_WITHDRAW", "WARRANTY_TERMS", "WARRANTY_ASSESSMENT")
    fun normalized(action: String, d: ServiceDeliveryDetails, today: LocalDate): ServiceDeliveryDetails {
        if (action !in actions) invalid()
        fun text(value: String, max: Int, required: Boolean = false): String = value.trim().also {
            if (it.length > max || (required && it.isBlank()) || it.any { c -> c.code == 127 || (c.code < 32 && c != '\n') }) invalid()
        }
        fun date(value: String?, required: Boolean = true, past: Boolean = false): String? {
            if (value == null && !required) return null
            val parsed = runCatching { LocalDate.parse(value) }.getOrNull() ?: invalid()
            if (parsed.year !in 1900..2200 || (past && parsed > today)) invalid()
            return parsed.toString()
        }
        fun id(raw: String?): String = deliveryUuid(raw).toString()
        val reason = text(d.reason, 4000, true).also { if (it.length < 8) invalid() }
        return when (action) {
            "HANDOVER_RELEASE" -> {
                if (!d.scopeConfirmed || !d.disclosureConfirmed) invalid()
                ServiceDeliveryDetails(packageVersionId = id(d.packageVersionId), agreementEvidenceId = id(d.agreementEvidenceId),
                    contractReference = text(d.contractReference, 240, true), scopeConfirmed = true, disclosureConfirmed = true,
                    sourceExceptionsReviewed = d.sourceExceptionsReviewed, reason = reason)
            }
            "HANDOVER_CLIENT_RESPONSE" -> {
                if (d.responseOutcome !in setOf("ACCEPTED", "REJECTED", "ACCEPTED_WITH_RESERVATIONS") || !d.representativeVerified) invalid()
                ServiceDeliveryDetails(releaseEventId = id(d.releaseEventId), correctsEventId = d.correctsEventId?.let(::id),
                    proofEvidenceId = id(d.proofEvidenceId), responseOutcome = d.responseOutcome, responseOn = date(d.responseOn, past = true),
                    representativeName = text(d.representativeName, 160, true), representativeCapacity = text(d.representativeCapacity, 160, true),
                    representativeAuthoritySource = text(d.representativeAuthoritySource, 1000, true), representativeVerified = true, reason = reason)
            }
            "HANDOVER_WITHDRAW" -> ServiceDeliveryDetails(releaseEventId = id(d.releaseEventId), reason = reason)
            "WARRANTY_TERMS" -> {
                if (d.scopeType !in setOf("SITE", "WORK") || !d.scopeConfirmed) invalid()
                val start = date(d.startsOn)!!
                val end = date(d.endsOn)!!
                if (LocalDate.parse(end) < LocalDate.parse(start)) invalid()
                ServiceDeliveryDetails(correctsEventId = d.correctsEventId?.let(::id), agreementEvidenceId = id(d.agreementEvidenceId),
                    proofEvidenceId = d.proofEvidenceId?.let(::id), contractReference = text(d.contractReference, 240, true),
                    scopeType = d.scopeType, scopeId = id(d.scopeId), coveredItem = text(d.coveredItem, 500, true), startsOn = start, endsOn = end,
                    writtenTerms = text(d.writtenTerms, 8000, true), exclusions = text(d.exclusions, 4000), responseObligations = text(d.responseObligations, 4000),
                    scopeConfirmed = true, reason = reason)
            }
            else -> {
                if (d.coverageDecision !in setOf("COVERED", "NOT_COVERED", "INSUFFICIENT_INFORMATION")) invalid()
                val insufficient = d.coverageDecision == "INSUFFICIENT_INFORMATION"
                if (!insufficient && (d.termsEventId == null || d.proofEvidenceId == null)) invalid()
                if (d.coverageDecision == "COVERED" && (!d.scopeConfirmed || d.exclusionApplies)) invalid()
                ServiceDeliveryDetails(correctsEventId = d.correctsEventId?.let(::id), termsEventId = d.termsEventId?.let(::id),
                    proofEvidenceId = d.proofEvidenceId?.let(::id), ticketId = id(d.ticketId), incidentOn = date(d.incidentOn, required = !insufficient, past = true),
                    coverageDecision = d.coverageDecision, incidentDescription = text(d.incidentDescription, 4000, true),
                    exclusionApplies = d.exclusionApplies, exclusionReason = text(d.exclusionReason, 2000, d.exclusionApplies),
                    scopeConfirmed = d.scopeConfirmed, reason = reason)
            }
        }
    }

    fun coveredDateAllowed(terms: ServiceDeliveryDetails, assessment: ServiceDeliveryDetails): Boolean =
        assessment.coverageDecision != "COVERED" || runCatching {
            val date = LocalDate.parse(assessment.incidentOn)
            date >= LocalDate.parse(terms.startsOn) && date <= LocalDate.parse(terms.endsOn) &&
                assessment.scopeConfirmed && !assessment.exclusionApplies
        }.getOrDefault(false)

    private fun invalid(): Nothing = deliveryFail("SERVICE_DELIVERY_INPUT_INVALID", HttpStatus.BAD_REQUEST)
}

@Component
class ServiceDeliverySources(
    private val projects: ProjectsPersistencePort,
    private val authorization: ProjectAuthorizationPort,
    private val repository: ServiceDeliveryRepository,
    private val handover: JdbcHandoverSource,
    private val clock: Clock,
) {
    internal fun manage(projectId: UUID, actor: UUID): ProjectSnapshot =
        (projects.project(projectId, clock.instant()) ?: deliveryFail("SERVICE_DELIVERY_NOT_FOUND", HttpStatus.NOT_FOUND)).also {
            if (!authorization.canManageProject(actor, it)) deliveryFail("SERVICE_DELIVERY_NOT_FOUND", HttpStatus.NOT_FOUND)
        }

    internal fun canManage(project: UUID, org: UUID, actor: UUID): Boolean = projects.project(project, clock.instant())
        ?.takeIf { it.organizationId == org }?.let { authorization.canManageProject(actor, it) } == true

    internal fun resolve(projectId: UUID, actor: UUID, action: String, requestedBase: Int, raw: ServiceDeliveryDetails): ServiceDeliveryResolvedSource {
        if (requestedBase < 0 || requestedBase == Int.MAX_VALUE) deliveryFail("SERVICE_DELIVERY_INPUT_INVALID", HttpStatus.BAD_REQUEST)
        val project = manage(projectId, actor)
        val org = project.organizationId
        val d = ServiceDeliveryRules.normalized(action, raw, LocalDate.now(clock.withZone(ZoneOffset.UTC)))
        val proofs = linkedMapOf<UUID, ServiceDeliveryProposalProof>()
        val source = mutableListOf("SERVICE_DELIVERY_V1", org.toString(), projectId.toString(), project.version.toString(), action, deliveryJson.encodeToString(d))
        var scopeName: String? = null
        var packageDocuments = emptyList<HandoverDocumentDto>()
        var packageFindings = emptyList<HandoverSourceFindingDto>()
        fun proof(id: String?, required: Boolean, purposes: Set<String>? = null, historical: Boolean = false): ServiceDeliveryEvidenceSource {
            val evidence = repository.evidence(org, projectId, deliveryUuid(id), historical) ?: deliveryFail("SERVICE_DELIVERY_PROOF_NOT_READY")
            if (purposes != null && evidence.purpose !in purposes) deliveryFail("SERVICE_DELIVERY_PROOF_PURPOSE_MISMATCH")
            proofs[evidence.id] = ServiceDeliveryProposalProof(evidence, required || proofs[evidence.id]?.requiredToOpen == true)
            return evidence
        }
        fun event(id: String?, kind: String): ServiceDeliveryEvent = repository.event(org, deliveryUuid(id))?.takeIf {
            it.proposal.projectId == projectId && it.proposal.action == kind
        } ?: deliveryFail("SERVICE_DELIVERY_REFERENCE_CHANGED")
        val stream = when (action) {
            "HANDOVER_RELEASE" -> {
                val snapshot = handover.version(projectId, deliveryUuid(d.packageVersionId)) ?: deliveryFail("SERVICE_DELIVERY_REFERENCE_CHANGED")
                val works = handover.work(projectId)
                val requirements = handover.requirements(projectId)
                val documents = handover.approvedDocuments(projectId, org)
                val current = HandoverReadinessPolicy.digest(documents, works, requirements, project.version)
                val omissions = HandoverReadinessPolicy.missing(works, requirements, documents, false)
                val exceptions = omissions.filter { it.startsWith("HANDOVER_DOCUMENT_WAIVER_NOT_APPROVED:") || it.startsWith("HANDOVER_DOCUMENT_EXCLUSION_NOT_APPROVED:") }
                if (handover.latest(projectId)?.versionId != snapshot.versionId || current != snapshot.digest ||
                    omissions.any { it !in exceptions } || (exceptions.isNotEmpty() && !d.sourceExceptionsReviewed) || documents.isEmpty())
                    deliveryFail("SERVICE_DELIVERY_PACKAGE_NOT_READY")
                if (repository.activeRelease(org, projectId) != null) deliveryFail("SERVICE_DELIVERY_WITHDRAW_PREVIOUS_RELEASE")
                source += snapshot.versionId.toString(); source += snapshot.digest
                packageDocuments = handover.snapshotDocuments(snapshot.versionId)
                packageFindings = handover.sourceFindings(projectId)
                documents.forEach {
                    val document = proof(it.evidenceId.toString(), required = false)
                    if (document.sha256 != it.sourceSha256 || document.classification == "HIGHLY_RESTRICTED")
                        deliveryFail("SERVICE_DELIVERY_DOCUMENT_DISCLOSURE_BLOCKED")
                }
                proof(d.agreementEvidenceId, required = true, purposes = setOf("AGREEMENT"))
                "HANDOVER"
            }
            "HANDOVER_CLIENT_RESPONSE", "HANDOVER_WITHDRAW" -> {
                val release = event(d.releaseEventId, "HANDOVER_RELEASE")
                if (repository.activeRelease(org, projectId)?.id != release.id) deliveryFail("SERVICE_DELIVERY_RELEASE_CHANGED")
                source += release.id.toString(); source += release.proposal.sourceStamp
                packageDocuments = release.proposal.packageDocuments
                packageFindings = release.proposal.packageFindings
                repository.proposalProofs(release.proposal).forEach { (id, frozen) ->
                    val original = proof(id.toString(), false, historical = true)
                    if (original.sha256 != frozen.first) deliveryFail("SERVICE_DELIVERY_PROOF_NOT_READY")
                }
                if (action == "HANDOVER_CLIENT_RESPONSE") {
                    if (LocalDate.parse(d.responseOn) < release.recordedAt.atOffset(ZoneOffset.UTC).toLocalDate()) deliveryFail("SERVICE_DELIVERY_RESPONSE_DATE_INVALID")
                    proof(d.proofEvidenceId, required = true, purposes = setOf("CLIENT_RESPONSE"))
                    if (repository.responseProofUsedForOtherRelease(org, projectId, deliveryUuid(d.proofEvidenceId), release.id))
                        deliveryFail("SERVICE_DELIVERY_RESPONSE_PROOF_ALREADY_LINKED")
                    val previous = repository.latest(org, projectId, "HANDOVER")
                    val expectedCorrection = previous?.takeIf { it.proposal.action == "HANDOVER_CLIENT_RESPONSE" }?.id?.toString()
                    if (d.correctsEventId != expectedCorrection) deliveryFail("SERVICE_DELIVERY_CORRECTION_SOURCE_REQUIRED")
                }
                "HANDOVER"
            }
            "WARRANTY_TERMS" -> {
                val chosen = repository.scopeChoices(projectId).singleOrNull { it.id == d.scopeId && it.kind == d.scopeType }
                    ?: deliveryFail("SERVICE_DELIVERY_SCOPE_CHANGED")
                scopeName = listOf(chosen.code, chosen.title).filter { it.isNotBlank() }.joinToString(" · ")
                proof(d.agreementEvidenceId, true, setOf("AGREEMENT"))
                d.proofEvidenceId?.let { proof(it, true, setOf("WARRANTY_SUPPORT", "CLIENT_RESPONSE", "WORK_DOCUMENT")) }
                d.correctsEventId?.let {
                    val previous = event(it, "WARRANTY_TERMS")
                    if (previous.proposal.details.scopeId != d.scopeId || previous.proposal.details.scopeType != d.scopeType ||
                        repository.latest(org, projectId, previous.proposal.stream)?.id != previous.id)
                        deliveryFail("SERVICE_DELIVERY_CORRECTION_SOURCE_REQUIRED")
                    source += previous.id.toString()
                    previous.proposal.stream
                } ?: "TERMS:" + d.scopeType + ":" + d.scopeId + ":" + deliveryHash(d.coveredItem.lowercase()).take(32)
            }
            else -> {
                val ticket = deliveryUuid(d.ticketId)
                val ticketTitle = repository.ticketTitle(org, projectId, ticket) ?: deliveryFail("SERVICE_DELIVERY_TICKET_NOT_FOUND", HttpStatus.NOT_FOUND)
                scopeName = ticketTitle
                val previous = repository.latest(org, projectId, "TICKET:$ticket")
                if (d.correctsEventId != previous?.id?.toString()) deliveryFail("SERVICE_DELIVERY_CORRECTION_SOURCE_REQUIRED")
                d.termsEventId?.let {
                    val terms = event(it, "WARRANTY_TERMS")
                    if (repository.latest(org, projectId, terms.proposal.stream)?.id != terms.id) deliveryFail("SERVICE_DELIVERY_WARRANTY_TERMS_CHANGED")
                    if (!ServiceDeliveryRules.coveredDateAllowed(terms.proposal.details, d)) deliveryFail("SERVICE_DELIVERY_COVERAGE_CONTRADICTS_TERMS")
                    scopeName = listOfNotNull(ticketTitle, terms.proposal.scopeName).joinToString(" · ")
                    source += terms.id.toString(); source += terms.proposal.sourceStamp
                    repository.proposalProofs(terms.proposal).forEach { (id, pinned) ->
                        val original = proof(id.toString(), true, historical = true)
                        if (original.sha256 != pinned.first) deliveryFail("SERVICE_DELIVERY_PROOF_NOT_READY")
                    }
                }
                d.proofEvidenceId?.let { proof(it, true, setOf("INCIDENT", "WORK_DOCUMENT")) }
                "TICKET:$ticket"
            }
        }
        val previous = repository.latest(org, projectId, stream)
        if ((previous?.number ?: 0) != requestedBase) deliveryFail("SERVICE_DELIVERY_SOURCE_CHANGED")
        // The first terms record cannot accidentally overwrite an already approved scope.
        if (action == "WARRANTY_TERMS" && previous != null && d.correctsEventId != previous.id.toString())
            deliveryFail("SERVICE_DELIVERY_CORRECTION_SOURCE_REQUIRED")
        source += stream; source += requestedBase.toString(); source += previous?.id?.toString().orEmpty()
        source += deliveryJson.encodeToString(packageDocuments)
        source += deliveryJson.encodeToString(packageFindings)
        proofs.values.sortedBy { it.source.id.toString() }.forEach { source += listOf(it.source.id, it.source.sha256, it.source.targetType, it.source.targetId, it.requiredToOpen).joinToString("|") }
        return ServiceDeliveryResolvedSource(project, stream, d, requestedBase, deliveryHash(source.joinToString("\u001f")), scopeName, proofs.values.toList(), packageDocuments, packageFindings)
    }
}

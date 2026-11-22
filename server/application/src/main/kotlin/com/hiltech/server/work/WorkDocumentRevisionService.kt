package com.hiltech.server.work

import com.hiltech.server.approval.ApprovalApproved
import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.approval.ApprovalSubjectVersionSource
import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.projects.ProjectAuthorizationPort
import com.hiltech.server.projects.ProjectsPersistencePort
import jakarta.servlet.http.HttpServletRequest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class CreateWorkDocumentRevisionRequest(
    val operationId: UUID,
    val documentCode: String,
    val title: String,
    val revisionLabel: String,
    val evidenceId: UUID,
    val expectedPreviousRevisionId: UUID? = null,
    val baseWorkOrderVersion: Long,
    val clientOccurredAt: Instant,
)

data class WorkDocumentRevisionResult(
    val workOrderId: UUID,
    val documentId: UUID,
    val revisionId: UUID,
    val revisionSequence: Int,
    val revisionLabel: String,
    val approvalRequired: Boolean,
    val approvalRequestId: UUID?,
    val approvalPolicyKey: String,
    val approvalPolicyVersion: Int,
    val replayed: Boolean,
)

@Service
class WorkDocumentRevisionService @Autowired constructor(
    private val persistence:
        WorkDocumentRevisionPersistence,
    private val work: WorkPersistencePort,
    private val projects:
        ProjectsPersistencePort,
    private val authorization:
        ProjectAuthorizationPort,
    private val approvals:
        ApprovalEngineService,
    private val commands:
        IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val events:
        ApplicationEventPublisher,
    private val clock: Clock,
    @Value("\${hiltech.field.work-documents.enabled:false}")
    private val enabled: Boolean = false,
) {
    @Transactional
    fun createRevision(
        actor: UUID,
        workOrderId: UUID,
        requirementKey: String,
        request:
            CreateWorkDocumentRevisionRequest,
        correlationId: String,
    ): WorkDocumentRevisionResult {
        if (!enabled) {
            unavailable()
        }
        if (request.baseWorkOrderVersion < 1) {
            invalid(
                "DOCUMENT_WORK_VERSION_INVALID",
            )
        }

        val now = clock.instant()
        val order =
            work.workOrder(workOrderId)
                ?: hidden()
        val project =
            projects.project(
                order.projectId,
                now,
            ) ?: hidden()
        if (!authorization.canCreateWork(
                actor,
                project,
            )
        ) {
            hidden()
        }
        if (
            order.version !=
            request.baseWorkOrderVersion
        ) {
            stale()
        }
        if (
            order.lifecycleState in
            TERMINAL_WORK_STATES
        ) {
            conflict(
                "DOCUMENT_WORK_STATE_FROZEN",
            )
        }

        val normalizedRequirementKey =
            requestKey(requirementKey)
        val requirement =
            persistence.requirement(
                workOrderId,
                normalizedRequirementKey,
            ) ?: throw ProductApiException(
                "DOCUMENT_REQUIREMENT_UNAVAILABLE",
                "The WorkOrder has no authoritative document requirement with this key.",
                HttpStatus.NOT_FOUND,
            )

        val code =
            required(
                request.documentCode,
                96,
            )
        requirement.configuredDocumentCode
            ?.let { configured ->
                if (configured != code) {
                    conflict(
                        "DOCUMENT_CODE_MISMATCH",
                    )
                }
            }
        val title =
            required(
                request.title,
                240,
            )
        val revisionLabel =
            required(
                request.revisionLabel,
                96,
            )

        if (
            !persistence.readyEvidenceMatches(
                request.evidenceId,
                order.organizationId,
                workOrderId,
            )
        ) {
            conflict(
                "DOCUMENT_EVIDENCE_NOT_READY",
            )
        }

        val documentId =
            deterministic(
                "work-document",
                workOrderId.toString() +
                    "|" +
                    requirement.requirementKey +
                    "|" +
                    code,
            )
        val revisionId =
            deterministic(
                "work-document-revision",
                request.operationId
                    .toString(),
            )
        val fingerprint =
            IdempotencyKeyContract.fingerprint(
                listOf(
                    workOrderId,
                    requirement.requirementKey,
                    code,
                    title,
                    revisionLabel,
                    request.evidenceId,
                    request.expectedPreviousRevisionId,
                    request.baseWorkOrderVersion,
                    request.clientOccurredAt,
                    requirement.approvalPolicyKey,
                    requirement.sourceTemplateId,
                    requirement.sourceTemplateRevision,
                ).joinToString("|"),
            )

        val execution =
            commands.execute(
                IdempotentCommandSpec(
                    operationId =
                        request.operationId,
                    actorUserId = actor,
                    commandType =
                        "WORK_DOCUMENT_REVISION_CREATE",
                    targetType =
                        WORK_DOCUMENT_REVISION_SUBJECT,
                    targetId = revisionId,
                    requestFingerprint =
                        fingerprint,
                    correlationId =
                        correlationId,
                ),
            ) {
                val currentOrder =
                    work.workOrder(
                        workOrderId,
                    ) ?: hidden()
                if (
                    currentOrder.version !=
                    request.baseWorkOrderVersion
                ) {
                    stale()
                }
                if (
                    currentOrder.lifecycleState in
                    TERMINAL_WORK_STATES
                ) {
                    conflict(
                        "DOCUMENT_WORK_STATE_FROZEN",
                    )
                }

                val existingDocument =
                    persistence.document(
                        documentId,
                    )
                if (existingDocument == null) {
                    persistence
                        .insertDocumentIfMissing(
                            WorkDocumentIdentity(
                                documentId =
                                    documentId,
                                organizationId =
                                    currentOrder
                                        .organizationId,
                                workOrderId =
                                    workOrderId,
                                requirementKey =
                                    requirement
                                        .requirementKey,
                                documentCode =
                                    code,
                                title = title,
                                approvalPolicyKey =
                                    requirement
                                        .approvalPolicyKey,
                                version = 1,
                            ),
                            actor,
                            now,
                        )
                } else if (
                    existingDocument
                        .organizationId !=
                    currentOrder.organizationId ||
                    existingDocument
                        .workOrderId !=
                    workOrderId ||
                    existingDocument
                        .requirementKey !=
                    requirement.requirementKey ||
                    existingDocument
                        .documentCode !=
                    code ||
                    existingDocument.title !=
                    title ||
                    existingDocument
                        .approvalPolicyKey !=
                    requirement.approvalPolicyKey
                ) {
                    conflict(
                        "DOCUMENT_IDENTITY_CONFLICT",
                    )
                }

                val latest =
                    persistence.latestRevision(
                        documentId,
                        lock = true,
                    )
                if (
                    latest?.revisionId !=
                    request.expectedPreviousRevisionId
                ) {
                    conflict(
                        "DOCUMENT_REVISION_SOURCE_CHANGED",
                    )
                }

                val sequence =
                    (latest?.revisionSequence ?: 0) +
                        1
                persistence.insertRevision(
                    WorkDocumentRevisionRecord(
                        revisionId =
                            revisionId,
                        organizationId =
                            currentOrder
                                .organizationId,
                        workOrderId =
                            workOrderId,
                        documentId =
                            documentId,
                        revisionSequence =
                            sequence,
                        revisionLabel =
                            revisionLabel,
                        evidenceId =
                            request.evidenceId,
                        supersedesRevisionId =
                            latest?.revisionId,
                        createdByUserId =
                            actor,
                        createdAt = now,
                        version = 1,
                    ),
                )

                val approval =
                    approvals.requestException(
                        actorUserId = actor,
                        operationId =
                            deterministic(
                                "work-document-approval-request",
                                request.operationId
                                    .toString(),
                            ),
                        subject =
                            ApprovalSubjectRef(
                                organizationId =
                                    currentOrder
                                        .organizationId,
                                subjectType =
                                    WORK_DOCUMENT_REVISION_SUBJECT,
                                subjectId =
                                    revisionId,
                                subjectVersion = 1,
                            ),
                        policyKey =
                            requirement
                                .approvalPolicyKey,
                        reasonCode =
                            "WORK_DOCUMENT_REVISION",
                        safeReasonSummary =
                            "Release Work document revision " +
                                revisionLabel,
                        correlationId =
                            correlationId,
                        allowAuthorityBypass =
                            false,
                    )

                persistence.insertRelease(
                    WorkDocumentReleaseRecord(
                        revisionId =
                            revisionId,
                        organizationId =
                            currentOrder
                                .organizationId,
                        workOrderId =
                            workOrderId,
                        documentId =
                            documentId,
                        policyKey =
                            approval.policyKey,
                        policyVersion =
                            approval.policyVersion,
                        releaseMode =
                            if (
                                approval
                                    .approvalRequired
                            ) {
                                "APPROVAL_REQUEST"
                            } else {
                                "NO_APPROVAL_REQUIRED"
                            },
                        approvalRequestId =
                            approval
                                .approvalRequestId,
                    ),
                    now,
                )

                if (
                    !approval.approvalRequired
                ) {
                    persistence.bumpWorkSource(
                        workOrderId,
                        now,
                    )
                    events.publishEvent(
                        WorkResourceChanged(
                            UUID.randomUUID(),
                            workOrderId,
                            actor,
                            correlationId,
                        ),
                    )
                }

                audit.append(
                    AuditEventRecord(
                        actorUserId = actor,
                        action =
                            "WORK_DOCUMENT_REVISION_CREATED",
                        targetType =
                            WORK_DOCUMENT_REVISION_SUBJECT,
                        targetId =
                            revisionId,
                        newStateRef =
                            if (
                                approval
                                    .approvalRequired
                            ) {
                                "PENDING_APPROVAL"
                            } else {
                                "APPROVED_NO_REQUEST"
                            },
                        safeDiffJson =
                            buildJsonObject {
                                put(
                                    "documentCode",
                                    code,
                                )
                                put(
                                    "revisionLabel",
                                    revisionLabel,
                                )
                                put(
                                    "revisionSequence",
                                    sequence,
                                )
                            }.toString(),
                        occurredAt = now,
                        correlationId =
                            correlationId,
                        configRevisionRefsJson =
                            "[\"" +
                                requirement
                                    .sourceTemplateId +
                                "\"]",
                    ),
                )

                IdempotentCommandOutcome(
                    "WORK_DOCUMENT_REVISION_CREATED",
                    buildJsonObject {
                        put(
                            "revisionId",
                            revisionId.toString(),
                        )
                        put(
                            "documentId",
                            documentId.toString(),
                        )
                    }.toString(),
                )
            }

        val revision =
            persistence.revision(
                revisionId,
            ) ?: conflict(
                "DOCUMENT_REVISION_RESULT_MISSING",
            )
        val release =
            persistence.releaseByRevision(
                revisionId,
            ) ?: conflict(
                "DOCUMENT_RELEASE_RESULT_MISSING",
            )

        return WorkDocumentRevisionResult(
            workOrderId = workOrderId,
            documentId = documentId,
            revisionId = revisionId,
            revisionSequence =
                revision.revisionSequence,
            revisionLabel =
                revision.revisionLabel,
            approvalRequired =
                release.releaseMode ==
                "APPROVAL_REQUEST",
            approvalRequestId =
                release.approvalRequestId,
            approvalPolicyKey =
                release.policyKey,
            approvalPolicyVersion =
                release.policyVersion,
            replayed =
                execution.replayed,
        )
    }

    private fun required(
        value: String,
        max: Int,
    ): String =
        value.trim()
            .takeIf {
                it.isNotEmpty() &&
                    it.length <= max
            }
            ?: invalid(
                "DOCUMENT_VALUE_INVALID",
            )

    private fun requestKey(
        value: String,
    ): String =
        value.trim()
            .takeIf {
                REQUIREMENT_KEY
                    .matches(it)
            }
            ?: invalid(
                "DOCUMENT_REQUIREMENT_KEY_INVALID",
            )

    private fun deterministic(
        namespace: String,
        value: String,
    ): UUID =
        UUID.nameUUIDFromBytes(
            ("hiltech:" +
                namespace +
                ":" +
                value)
                .toByteArray(
                    StandardCharsets.UTF_8,
                ),
        )

    private fun hidden(): Nothing =
        throw ProductApiException(
            "OBJECT_NOT_VISIBLE",
            "The requested Work object is unavailable.",
            HttpStatus.NOT_FOUND,
        )

    private fun unavailable(): Nothing =
        throw ProductApiException(
            "WORK_DOCUMENTS_UNAVAILABLE",
            "The Work document revision source is not enabled.",
            HttpStatus.NOT_FOUND,
        )

    private fun stale(): Nothing =
        throw ProductApiException(
            "DOCUMENT_WORK_SOURCE_CHANGED",
            "The WorkOrder changed before the document revision was registered.",
            HttpStatus.CONFLICT,
        )

    private fun invalid(
        code: String,
    ): Nothing =
        throw ProductApiException(
            code,
            "The Work document revision request is invalid.",
            HttpStatus.BAD_REQUEST,
        )

    private fun conflict(
        code: String,
    ): Nothing =
        throw ProductApiException(
            code,
            "The Work document revision conflicts with current authoritative data.",
            HttpStatus.CONFLICT,
        )

    private companion object {
        val REQUIREMENT_KEY =
            Regex(
                "[A-Za-z0-9][A-Za-z0-9._-]{0,63}",
            )

        val TERMINAL_WORK_STATES =
            setOf(
                WorkOrderLifecycle.ACCEPTED,
                WorkOrderLifecycle.CLOSED,
                WorkOrderLifecycle.CANCELLED,
            )
    }
}

@Component
class WorkDocumentApprovalSubjectVersionSource @Autowired constructor(
    private val persistence:
        WorkDocumentRevisionPersistence,
    @Value("\${hiltech.field.work-documents.enabled:false}")
    private val enabled: Boolean = false,
) : ApprovalSubjectVersionSource {
    override fun supports(
        subjectType: String,
    ): Boolean =
        subjectType ==
            WORK_DOCUMENT_REVISION_SUBJECT

    override fun isCurrent(
        subject: ApprovalSubjectRef,
    ): Boolean {
        if (
            !enabled ||
            subject.subjectType !=
            WORK_DOCUMENT_REVISION_SUBJECT
        ) {
            return false
        }
        val revision =
            persistence.revision(
                subject.subjectId,
            ) ?: return false
        return (
            revision.organizationId ==
                subject.organizationId &&
                revision.version ==
                subject.subjectVersion &&
                persistence.isLatestRevision(
                    revision.revisionId,
                    revision.documentId,
                )
            )
    }
}

@Component
class WorkDocumentApprovalListener @Autowired constructor(
    private val persistence:
        WorkDocumentRevisionPersistence,
    private val audit: AuditEventWriter,
    private val events:
        ApplicationEventPublisher,
    @Value("\${hiltech.field.work-documents.enabled:false}")
    private val enabled: Boolean = false,
) {
    @EventListener
    fun approved(
        event: ApprovalApproved,
    ) {
        if (
            !enabled ||
            event.subjectType !=
            WORK_DOCUMENT_REVISION_SUBJECT
        ) {
            return
        }
        val release =
            persistence
                .releaseByApprovalRequest(
                    event.approvalRequestId,
                ) ?: return
        if (
            release.revisionId !=
            event.subjectId ||
            release.organizationId !=
            event.organizationId
        ) {
            throw IllegalStateException(
                "Approval event does not match Work document release.",
            )
        }

        val version =
            persistence.bumpWorkSource(
                release.workOrderId,
                event.occurredAt,
            ) ?: return

        audit.append(
            AuditEventRecord(
                actorUserId =
                    event.actorIdentityId,
                action =
                    "WORK_DOCUMENT_REVISION_APPROVED",
                targetType =
                    WORK_DOCUMENT_REVISION_SUBJECT,
                targetId =
                    release.revisionId,
                newStateRef =
                    "APPROVED_CURRENT_CANDIDATE",
                safeDiffJson =
                    buildJsonObject {
                        put(
                            "workOrderVersion",
                            version,
                        )
                    }.toString(),
                occurredAt =
                    event.occurredAt,
                correlationId =
                    event.correlationId,
            ),
        )
        events.publishEvent(
            WorkResourceChanged(
                eventId(event.eventId),
                release.workOrderId,
                event.actorIdentityId,
                event.correlationId,
            ),
        )
    }

    private fun eventId(
        sourceId: UUID,
    ): UUID =
        UUID.nameUUIDFromBytes(
            ("work-document-readiness:" +
                sourceId)
                .toByteArray(
                    StandardCharsets.UTF_8,
                ),
        )
}

data class WorkDocumentReadinessFact(
    val satisfied: Boolean,
    val reason: String,
    val sourceId: UUID?,
    val sourceVersion: Long?,
    val asOf: Instant,
)

fun interface WorkDocumentReadinessSource {
    fun evaluate(
        order: WorkOrderSnapshot,
        requirement:
            ReadinessRequirementExecutionSnapshot,
        at: Instant,
    ): WorkDocumentReadinessFact?
}

@Component
class JdbcWorkDocumentReadinessSource @Autowired constructor(
    private val persistence:
        WorkDocumentRevisionPersistence,
    @Value("\${hiltech.field.work-documents.enabled:false}")
    private val enabled: Boolean = false,
) : WorkDocumentReadinessSource {
    override fun evaluate(
        order: WorkOrderSnapshot,
        requirement:
            ReadinessRequirementExecutionSnapshot,
        at: Instant,
    ): WorkDocumentReadinessFact? {
        if (!enabled) {
            return null
        }
        val source =
            persistence.requirement(
                order.workOrderId,
                requirement.key,
            ) ?: return WorkDocumentReadinessFact(
                satisfied = false,
                reason =
                    "DOCUMENT_REQUIREMENT_SOURCE_UNAVAILABLE",
                sourceId = null,
                sourceVersion = null,
                asOf = at,
            )

        val current =
            persistence.currentApproved(
                workOrderId =
                    order.workOrderId,
                requirementKey =
                    requirement.key,
                documentCode =
                    source
                        .configuredDocumentCode,
            ).firstOrNull()
                ?: return WorkDocumentReadinessFact(
                    satisfied = false,
                    reason =
                        "APPROVED_DOCUMENT_REVISION_REQUIRED",
                    sourceId = null,
                    sourceVersion = null,
                    asOf = at,
                )

        return WorkDocumentReadinessFact(
            satisfied = true,
            reason =
                "APPROVED_DOCUMENT_REVISION_CURRENT",
            sourceId =
                current.revisionId,
            sourceVersion =
                current.revisionSequence
                    .toLong(),
            asOf =
                current.approvedAt,
        )
    }
}

@RestController
@RequestMapping("/v1/work-orders")
class WorkDocumentRevisionController(
    private val service:
        WorkDocumentRevisionService,
) {
    @PostMapping(
        "/{workOrderId}/documents/{requirementKey}/revisions",
    )
    fun create(
        servletRequest: HttpServletRequest,
        @PathVariable
        workOrderId: UUID,
        @PathVariable
        requirementKey: String,
        @RequestHeader("Idempotency-Key")
        idempotencyKey: String,
        @RequestBody
        request:
            CreateWorkDocumentRevisionRequest,
    ): WorkDocumentRevisionResult {
        IdempotencyKeyContract.requireMatches(
            idempotencyKey,
            request.operationId,
        )
        val context =
            HiltechRequestContext.current(
                servletRequest,
            )
        val actor =
            context.identityId
                ?.let {
                    runCatching {
                        UUID.fromString(it)
                    }.getOrNull()
                }
                ?: throw ProductApiException(
                    "AUTHENTICATION_REQUIRED",
                    "A HILTECH identity is required.",
                    HttpStatus.UNAUTHORIZED,
                )
        return service.createRevision(
            actor,
            workOrderId,
            requirementKey,
            request,
            context.correlationId,
        )
    }
}

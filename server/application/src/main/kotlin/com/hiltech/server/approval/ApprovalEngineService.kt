package com.hiltech.server.approval

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.security.AuthorizationDesiredState
import com.hiltech.server.security.AuthorizationProjectionIntent
import com.hiltech.server.security.AuthorizationProjectionIntentWriter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

sealed interface ApprovalEvaluationResult {
    data class NoApprovalRequired(
        val policyKey: String,
        val policyVersion: Int,
    ) : ApprovalEvaluationResult

    data class ApprovalRequired(
        val policyKey: String,
        val policyVersion: Int,
        val authorityKey: String,
    ) : ApprovalEvaluationResult
}

data class ApprovalRequestResult(
    val approvalRequired: Boolean,
    val approvalRequestId: UUID?,
    val state: ApprovalRequestState?,
    val policyKey: String,
    val policyVersion: Int,
    val authorityKey: String?,
    val replayed: Boolean,
)

data class ApprovalDecisionResult(
    val approvalRequestId: UUID,
    val state: ApprovalRequestState,
    val replayed: Boolean,
)

data class ApprovalSubjectStatus(
    val approvalRequestId: UUID,
    val state: ApprovalRequestState,
    val subjectVersion: Long,
    val policyKey: String,
)

fun interface ApprovalSubjectVersionGuard {
    fun isCurrent(subject: ApprovalSubjectRef): Boolean
}

@Component
class FailClosedApprovalSubjectVersionGuard @Autowired constructor(
    private val sources: List<ApprovalSubjectVersionSource>,
) : ApprovalSubjectVersionGuard {
    constructor() : this(emptyList())

    override fun isCurrent(subject: ApprovalSubjectRef): Boolean {
        val matching = sources.filter { it.supports(subject.subjectType) }
        return matching.singleOrNull()?.isCurrent(subject) ?: false
    }
}

@Component
class ApprovalEngineService @Autowired constructor(
    private val persistence:
        ApprovalPersistencePort,
    private val idempotency:
        IdempotentCommandExecutor,
    private val projectionWriter:
        AuthorizationProjectionIntentWriter,
    private val authorization:
        ApprovalAuthorizationPort,
    private val subjectVersionGuard:
        ApprovalSubjectVersionGuard,
    private val audit:
        AuditEventWriter,
    private val events:
        ApplicationEventPublisher,
    private val clock: Clock,
    private val policyMaintenance: ApprovalPolicyMaintenancePersistencePort? = null,
    private val policyMaintenanceAuthority: ApprovalPolicyMaintenanceAuthorityPort? = null,
    private val decisionConstraints: List<ApprovalDecisionConstraint> = emptyList(),
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
        }

    /**
     * Explicit company setup maintenance. This can only end pending assignments;
     * it cannot approve a source or give the administrator reviewer access.
     * The HTTP setup boundary separately requires recent reauthentication.
     */
    fun supersedePolicyRequests(
        actorUserId: UUID,
        operationId: UUID,
        scope: ApprovalPolicyMaintenanceScope,
        reason: String,
        correlationId: String,
    ): ApprovalPolicyMaintenanceResult {
        val store = policyMaintenance ?: throw ProductApiException(
            "APPROVAL_POLICY_MAINTENANCE_UNAVAILABLE", "Review workflow maintenance is unavailable.", HttpStatus.SERVICE_UNAVAILABLE,
        )
        fun requireMaintenanceAuthority() {
            requireActiveMember(scope.organizationId, actorUserId, clock.instant())
            if (policyMaintenanceAuthority?.canSupersedePending(actorUserId, scope) != true) throw ProductApiException(
                "APPROVAL_POLICY_ADMINISTRATION_DENIED", "Current company review workflow setup authority is required.", HttpStatus.FORBIDDEN,
            )
        }
        requireMaintenanceAuthority()
        val note = reason.trim()
        if (note.length !in 3..1000 || note.any { it.code < 32 } || scope.policyVersion < 1 ||
            scope.policyRevision < 1 || scope.expectedPendingCount < 1) throw ProductApiException(
            "APPROVAL_POLICY_MAINTENANCE_INVALID", "A current policy version, pending count and reason are required.", HttpStatus.BAD_REQUEST,
        )
        val fingerprint = IdempotencyKeyContract.fingerprint(buildJsonObject {
            put("organizationId", scope.organizationId.toString()); put("policyId", scope.policyId.toString())
            put("policyKey", scope.policyKey); put("policyRevision", scope.policyRevision); put("policyVersion", scope.policyVersion)
            put("subjectType", scope.subjectType); put("expectedPendingCount", scope.expectedPendingCount); put("reason", note)
        }.toString())
        val execution = idempotency.execute(IdempotentCommandSpec(
            operationId, actorUserId, "SupersedeCompanyReviewPolicyRequests", "ApprovalPolicyVersion",
            scope.policyId, fingerprint, correlationId,
        )) {
            if (!store.lockPolicy(scope)) throw ProductApiException(
                "APPROVAL_POLICY_CHANGED", "The review policy version changed; refresh the setup impact.", HttpStatus.CONFLICT,
            )
            requireMaintenanceAuthority()
            val requests = store.pendingRequestIds(scope)
            if (requests.size != scope.expectedPendingCount) throw ProductApiException(
                "APPROVAL_POLICY_PENDING_CHANGED", "The pending review count changed; review the impact again.", HttpStatus.CONFLICT,
            )
            val now = clock.instant()
            val contexts = requests.map { id ->
                val context = persistence.loadDecisionContextForUpdate(id) ?: throw notVisible()
                if (context.organizationId != scope.organizationId || context.policyKey != scope.policyKey ||
                    context.policyVersion != scope.policyRevision || context.subjectType != scope.subjectType ||
                    context.requestState != ApprovalRequestState.PENDING || context.assignmentState != "ASSIGNED") {
                    throw ProductApiException("APPROVAL_POLICY_PENDING_CHANGED", "The pending review changed; refresh the setup impact.", HttpStatus.CONFLICT)
                }
                context
            }
            contexts.forEach { context ->
                val id = context.requestId
                persistence.supersede(context, now)
                projectionWriter.write(revokeApproverIntent(context, now))
                audit.append(AuditEventRecord(
                    actorUserId = actorUserId, action = "APPROVAL_SUPERSEDED", targetType = "ApprovalRequest", targetId = id,
                    previousStateRef = "approval:PENDING", newStateRef = "approval:SUPERSEDED",
                    safeDiffJson = buildJsonObject {
                        put("policyId", scope.policyId.toString()); put("policyRevision", scope.policyRevision)
                        put("maintenanceOperationId", operationId.toString()); put("reason", note)
                    }.toString(), occurredAt = now, correlationId = correlationId, reason = "COMPANY_REVIEW_WORKFLOW_RECONFIGURED",
                ))
                events.publishEvent(ApprovalSuperseded(
                    UUID.randomUUID(), id, context.organizationId, context.subjectType, context.subjectId,
                    context.subjectVersion, actorUserId, now, correlationId,
                ))
            }
            IdempotentCommandOutcome("APPROVAL_POLICY_PENDING_SUPERSEDED", buildJsonObject { put("supersededCount", requests.size) }.toString())
        }
        val count = json.parseToJsonElement(requireNotNull(execution.outcome.resultPayloadJson)).jsonObject
            .getValue("supersededCount").jsonPrimitive.content.toInt()
        return ApprovalPolicyMaintenanceResult(scope.policyId, count, execution.replayed)
    }

    fun evaluate(
        actorUserId: UUID,
        organizationId: UUID,
        policyKey: String,
    ): ApprovalEvaluationResult {
        val now = clock.instant()
        requireActiveMember(
            organizationId =
                organizationId,
            actorUserId =
                actorUserId,
            at = now,
        )

        val policy =
            loadPolicy(
                organizationId =
                    organizationId,
                policyKey =
                    normalizeKey(
                        policyKey,
                        "APPROVAL_POLICY_INVALID",
                    ),
                at = now,
            )

        if (
            !policy.approvalRequired ||
            policy.mode ==
                ApprovalPolicyMode
                    .NO_APPROVAL_REQUIRED
        ) {
            return ApprovalEvaluationResult
                .NoApprovalRequired(
                    policyKey =
                        policy.policyKey,
                    policyVersion =
                        policy.versionNumber,
                )
        }

        val authorityKey =
            requireNotNull(
                policy.authorityKey,
            )
        val authority =
            resolveAuthority(
                organizationId =
                    organizationId,
                authorityKey =
                    authorityKey,
                at = now,
            )

        if (
            persistence.actorMatchesAuthority(
                actorUserId =
                    actorUserId,
                authority =
                    authority,
                at = now,
            )
        ) {
            return ApprovalEvaluationResult
                .NoApprovalRequired(
                    policyKey =
                        policy.policyKey,
                    policyVersion =
                        policy.versionNumber,
                )
        }

        return ApprovalEvaluationResult
            .ApprovalRequired(
                policyKey =
                    policy.policyKey,
                policyVersion =
                    policy.versionNumber,
                authorityKey =
                    authorityKey,
            )
    }

    fun requestException(
        actorUserId: UUID,
        operationId: UUID,
        subject: ApprovalSubjectRef,
        policyKey: String,
        reasonCode: String,
        safeReasonSummary: String?,
        correlationId: String,
        allowAuthorityBypass: Boolean = true,
    ): ApprovalRequestResult {
        val now = clock.instant()
        requireActiveMember(
            organizationId =
                subject.organizationId,
            actorUserId =
                actorUserId,
            at = now,
        )
        require(
            subject.subjectVersion >= 1,
        )

        val normalizedPolicyKey =
            normalizeKey(
                policyKey,
                "APPROVAL_POLICY_INVALID",
            )
        val normalizedReasonCode =
            normalizeKey(
                reasonCode,
                "APPROVAL_REASON_INVALID",
            )
        val normalizedSubjectType =
            normalizeKey(
                subject.subjectType,
                "APPROVAL_SUBJECT_TYPE_INVALID",
            )
        val normalizedSubject =
            subject.copy(
                subjectType =
                    normalizedSubjectType,
            )

        val policy =
            loadPolicy(
                organizationId =
                    subject.organizationId,
                policyKey =
                    normalizedPolicyKey,
                at = now,
            )

        if (
            !policy.approvalRequired ||
            policy.mode !=
                ApprovalPolicyMode.SINGLE
        ) {
            return ApprovalRequestResult(
                approvalRequired = false,
                approvalRequestId = null,
                state = null,
                policyKey = policy.policyKey,
                policyVersion =
                    policy.versionNumber,
                authorityKey = null,
                replayed = false,
            )
        }

        val authorityKey =
            requireNotNull(
                policy.authorityKey,
            )
        val authority =
            resolveAuthority(
                subject = normalizedSubject,
                authorityKey = authorityKey,
                at = now,
            )

        if (
            allowAuthorityBypass &&
            persistence.actorMatchesAuthority(
                actorUserId =
                    actorUserId,
                authority =
                    authority,
                at = now,
            )
        ) {
            return ApprovalRequestResult(
                approvalRequired = false,
                approvalRequestId = null,
                state = null,
                policyKey = policy.policyKey,
                policyVersion =
                    policy.versionNumber,
                authorityKey =
                    authority.authorityKey,
                replayed = false,
            )
        }

        val requestId = UUID.randomUUID()
        val stepId = UUID.randomUUID()
        val assignmentId = UUID.randomUUID()

        val fingerprint =
            IdempotencyKeyContract.fingerprint(
                listOf(
                    normalizedSubject
                        .organizationId,
                    normalizedSubject
                        .subjectType,
                    normalizedSubject
                        .subjectId,
                    normalizedSubject
                        .subjectVersion,
                    policy.policyKey,
                    policy.versionNumber,
                    normalizedReasonCode,
                    safeReasonSummary
                        ?.trim()
                        ?.take(500)
                        .orEmpty(),
                    allowAuthorityBypass,
                ).joinToString("|"),
            )

        val execution =
            idempotency.execute(
                IdempotentCommandSpec(
                    operationId =
                        operationId,
                    actorUserId =
                        actorUserId,
                    commandType =
                        "RequestApprovalException",
                    targetType =
                        normalizedSubject
                            .subjectType,
                    targetId =
                        normalizedSubject
                            .subjectId,
                    requestFingerprint =
                        fingerprint,
                    correlationId =
                        correlationId,
                ),
            ) {
                val createdAt =
                    clock.instant()

                persistence
                    .insertExceptionRequest(
                        ApprovalRequestCreation(
                            requestId =
                                requestId,
                            stepId =
                                stepId,
                            assignmentId =
                                assignmentId,
                            subject =
                                normalizedSubject,
                            policy =
                                policy,
                            requesterUserId =
                                actorUserId,
                            authority =
                                authority,
                            reasonCode =
                                normalizedReasonCode,
                            safeReasonSummary =
                                safeReasonSummary
                                    ?.trim()
                                    ?.take(500)
                                    ?.takeIf {
                                        it.isNotEmpty()
                                    },
                            correlationId =
                                correlationId,
                            createdAt =
                                createdAt,
                        ),
                    )

                projectionWriter.write(
                    projectionIntent(
                        eventId =
                            UUID.randomUUID(),
                        tuple =
                            ApprovalAuthorizationRelations
                                .requester(
                                    actorUserId =
                                        actorUserId,
                                    approvalRequestId =
                                        requestId,
                                ),
                        requestId =
                            requestId,
                        sourceVersion = 1,
                        occurredAt =
                            createdAt,
                    ),
                )
                projectionWriter.write(
                    projectionIntent(
                        eventId =
                            UUID.randomUUID(),
                        tuple =
                            ApprovalAuthorizationRelations
                                .approver(
                                    principalType =
                                        authority
                                            .principalType,
                                    principalId =
                                        authority
                                            .principalId,
                                    approvalRequestId =
                                        requestId,
                                ),
                        requestId =
                            requestId,
                        sourceVersion = 1,
                        occurredAt =
                            createdAt,
                    ),
                )

                audit.append(
                    AuditEventRecord(
                        actorUserId =
                            actorUserId,
                        action =
                            "APPROVAL_REQUESTED",
                        targetType =
                            "ApprovalRequest",
                        targetId =
                            requestId,
                        newStateRef =
                            "approval:PENDING",
                        occurredAt =
                            createdAt,
                        correlationId =
                            correlationId,
                        reason =
                            normalizedReasonCode,
                        configRevisionRefsJson =
                            buildJsonObject {
                                put(
                                    "policyKey",
                                    policy.policyKey,
                                )
                                put(
                                    "policyVersion",
                                    policy.versionNumber,
                                )
                            }.toString(),
                    ),
                )

                events.publishEvent(
                    ApprovalRequested(
                        eventId =
                            UUID.randomUUID(),
                        approvalRequestId =
                            requestId,
                        organizationId =
                            normalizedSubject
                                .organizationId,
                        subjectType =
                            normalizedSubject
                                .subjectType,
                        subjectId =
                            normalizedSubject
                                .subjectId,
                        subjectVersion =
                            normalizedSubject
                                .subjectVersion,
                        policyKey =
                            policy.policyKey,
                        policyVersion =
                            policy.versionNumber,
                        authorityKey =
                            authority.authorityKey,
                        requesterIdentityId =
                            actorUserId,
                        occurredAt =
                            createdAt,
                        correlationId =
                            correlationId,
                    ),
                )

                IdempotentCommandOutcome(
                    resultCode =
                        "APPROVAL_REQUESTED",
                    resultPayloadJson =
                        buildJsonObject {
                            put(
                                "approvalRequestId",
                                requestId
                                    .toString(),
                            )
                            put(
                                "state",
                                ApprovalRequestState
                                    .PENDING.name,
                            )
                            put(
                                "policyKey",
                                policy.policyKey,
                            )
                            put(
                                "policyVersion",
                                policy
                                    .versionNumber,
                            )
                            put(
                                "authorityKey",
                                authority
                                    .authorityKey,
                            )
                        }.toString(),
                )
            }

        val payload =
            requirePayload(
                execution.outcome
                    .resultPayloadJson,
            )

        return ApprovalRequestResult(
            approvalRequired = true,
            approvalRequestId =
                UUID.fromString(
                    payload[
                        "approvalRequestId"
                    ]!!.jsonPrimitive.content,
                ),
            state =
                ApprovalRequestState
                    .valueOf(
                        payload["state"]!!
                            .jsonPrimitive
                            .content,
                    ),
            policyKey =
                payload["policyKey"]!!
                    .jsonPrimitive.content,
            policyVersion =
                payload["policyVersion"]!!
                    .jsonPrimitive
                    .content.toInt(),
            authorityKey =
                payload["authorityKey"]!!
                    .jsonPrimitive.content,
            replayed =
                execution.replayed,
        )
    }

    /** The generic API keeps its established conflict response after a stale request is recorded. */
    fun decide(
        actorUserId: UUID,
        operationId: UUID,
        approvalRequestId: UUID,
        decision: ApprovalDecisionType,
        comment: String?,
        correlationId: String,
    ): ApprovalDecisionResult {
        val recorded = decideForSourceWorkflow(actorUserId, operationId, approvalRequestId, decision, comment, correlationId)
        if (recorded.state == ApprovalRequestState.SUPERSEDED) throw ProductApiException(
            code = "APPROVAL_SUPERSEDED",
            message = "The subject changed while approval was pending.",
            status = HttpStatus.CONFLICT,
        )
        return recorded
    }

    /**
     * Source-owned commands need the recorded terminal outcome inside their
     * surrounding transaction. Returning SUPERSEDED preserves that close instead
     * of rolling it back as an HTTP conflict; it never applies a business decision.
     * Every assignment, current authority and source constraint below is shared
     * with the generic API. There is no caller-controlled bypass option.
     */
    fun decideForSourceWorkflow(
        actorUserId: UUID,
        operationId: UUID,
        approvalRequestId: UUID,
        decision: ApprovalDecisionType,
        comment: String?,
        correlationId: String,
    ): ApprovalDecisionResult {
        val normalizedComment =
            comment
                ?.trim()
                ?.take(1000)
                ?.takeIf { it.isNotEmpty() }

        if (
            decision !=
                ApprovalDecisionType.APPROVE &&
            normalizedComment == null
        ) {
            throw ProductApiException(
                code =
                    "APPROVAL_REASON_REQUIRED",
                message =
                    "A reason is required for this decision.",
                status =
                    HttpStatus.UNPROCESSABLE_ENTITY,
            )
        }

        val fingerprint =
            IdempotencyKeyContract.fingerprint(
                listOf(
                    approvalRequestId,
                    decision.name,
                    normalizedComment.orEmpty(),
                ).joinToString("|"),
            )

        val execution =
            idempotency.execute(
                IdempotentCommandSpec(
                    operationId =
                        operationId,
                    actorUserId =
                        actorUserId,
                    commandType =
                        "DecideApproval",
                    targetType =
                        "ApprovalRequest",
                    targetId =
                        approvalRequestId,
                    requestFingerprint =
                        fingerprint,
                    correlationId =
                        correlationId,
                ),
            ) {
                val now = clock.instant()
                val context =
                    persistence
                        .loadDecisionContextForUpdate(
                            approvalRequestId,
                        )
                        ?: throw notVisible()

                if (
                    context.requestState !=
                        ApprovalRequestState.PENDING ||
                    context.assignmentState !=
                        "ASSIGNED"
                ) {
                    throw ProductApiException(
                        code =
                            "APPROVAL_ALREADY_HANDLED",
                        message =
                            "This approval is no longer pending.",
                        status =
                            HttpStatus.CONFLICT,
                    )
                }

                if (
                    !persistence.actorMatchesAssignment(
                        actorUserId =
                            actorUserId,
                        context =
                            context,
                        at = now,
                    )
                ) {
                    throw notVisible()
                }

                val authority =
                    resolveAuthority(
                        subject = ApprovalSubjectRef(
                            organizationId = context.organizationId,
                            subjectType = context.subjectType,
                            subjectId = context.subjectId,
                            subjectVersion = context.subjectVersion,
                        ),
                        authorityKey = context.authorityKey,
                        at = now,
                    )

                if (
                    !persistence.actorMatchesAuthority(
                        actorUserId =
                            actorUserId,
                        authority =
                            authority,
                        at = now,
                    )
                ) {
                    throw ProductApiException(
                        code =
                            "APPROVAL_AUTHORITY_CHANGED",
                        message =
                            "Current approval authority has changed.",
                        status =
                            HttpStatus.CONFLICT,
                    )
                }

                if (
                    !authorization.canDecide(
                        actorUserId =
                            actorUserId,
                        approvalRequestId =
                            context.requestId,
                        assignmentPrincipalType =
                            context
                                .assignmentPrincipalType,
                        assignmentPrincipalId =
                            context
                                .assignmentPrincipalId,
                    )
                ) {
                    throw notVisible()
                }

                val subject =
                    ApprovalSubjectRef(
                        organizationId =
                            context.organizationId,
                        subjectType =
                            context.subjectType,
                        subjectId =
                            context.subjectId,
                        subjectVersion =
                            context.subjectVersion,
                    )

                if (
                    !subjectVersionGuard
                        .isCurrent(subject)
                ) {
                    persistence.supersede(
                        context = context,
                        at = now,
                    )

                    projectionWriter.write(
                        revokeApproverIntent(
                            context =
                                context,
                            occurredAt =
                                now,
                        ),
                    )

                    audit.append(
                        AuditEventRecord(
                            actorUserId =
                                actorUserId,
                            action =
                                "APPROVAL_SUPERSEDED",
                            targetType =
                                "ApprovalRequest",
                            targetId =
                                context.requestId,
                            previousStateRef =
                                "approval:PENDING",
                            newStateRef =
                                "approval:SUPERSEDED",
                            occurredAt = now,
                            correlationId =
                                correlationId,
                            reason =
                                "SUBJECT_VERSION_CHANGED",
                        ),
                    )

                    events.publishEvent(
                        ApprovalSuperseded(
                            eventId =
                                UUID.randomUUID(),
                            approvalRequestId =
                                context.requestId,
                            organizationId =
                                context
                                    .organizationId,
                            subjectType =
                                context.subjectType,
                            subjectId =
                                context.subjectId,
                            subjectVersion =
                                context.subjectVersion,
                            actorIdentityId =
                                actorUserId,
                            occurredAt = now,
                            correlationId =
                                correlationId,
                        ),
                    )

                    return@execute IdempotentCommandOutcome(
                            resultCode =
                                "APPROVAL_SUPERSEDED",
                            resultPayloadJson =
                                decisionPayload(
                                    context.requestId,
                                    ApprovalRequestState
                                        .SUPERSEDED,
                                ),
                        )
                }

                // A stale source was ended above without recording a business
                // decision. Current-source decisions must satisfy their owner's
                // independence and proof requirements even through the generic API.
                val sourceConstraints = decisionConstraints.filter { it.supports(context.subjectType) }
                if (sourceConstraints.size > 1 || sourceConstraints.singleOrNull()?.canDecide(actorUserId, context, decision) == false) {
                    throw ProductApiException(
                        "APPROVAL_SOURCE_REVIEW_NOT_ALLOWED", "This source requires an independent currently eligible reviewer and its review evidence.", HttpStatus.FORBIDDEN,
                    )
                }

                persistence.applyDecision(
                    context = context,
                    decisionId =
                        UUID.randomUUID(),
                    actorUserId =
                        actorUserId,
                    decision = decision,
                    decidedAt = now,
                    comment =
                        normalizedComment,
                    operationId =
                        operationId,
                    correlationId =
                        correlationId,
                )

                projectionWriter.write(
                    revokeApproverIntent(
                        context = context,
                        occurredAt = now,
                    ),
                )

                val newState =
                    decision.toRequestState()

                audit.append(
                    AuditEventRecord(
                        actorUserId =
                            actorUserId,
                        action =
                            "APPROVAL_" +
                                decision.name,
                        targetType =
                            "ApprovalRequest",
                        targetId =
                            context.requestId,
                        previousStateRef =
                            "approval:PENDING",
                        newStateRef =
                            "approval:" +
                                newState.name,
                        occurredAt = now,
                        correlationId =
                            correlationId,
                        reason =
                            normalizedComment,
                        configRevisionRefsJson =
                            buildJsonObject {
                                put(
                                    "policyKey",
                                    context.policyKey,
                                )
                                put(
                                    "policyVersion",
                                    context.policyVersion,
                                )
                            }.toString(),
                    ),
                )

                events.publishEvent(
                    decisionEvent(
                        decision = decision,
                        context = context,
                        actorUserId =
                            actorUserId,
                        occurredAt = now,
                        correlationId =
                            correlationId,
                    ),
                )

                IdempotentCommandOutcome(
                    resultCode =
                        "APPROVAL_" +
                            newState.name,
                    resultPayloadJson =
                        decisionPayload(
                            context.requestId,
                            newState,
                        ),
                )
            }

        val payload =
            requirePayload(
                execution.outcome
                    .resultPayloadJson,
            )
        val state =
            ApprovalRequestState.valueOf(
                payload["state"]!!
                    .jsonPrimitive.content,
            )

        return ApprovalDecisionResult(
            approvalRequestId =
                UUID.fromString(
                    payload[
                        "approvalRequestId"
                    ]!!.jsonPrimitive.content,
                ),
            state = state,
            replayed =
                execution.replayed,
        )
    }

    private fun loadPolicy(
        organizationId: UUID,
        policyKey: String,
        at: java.time.Instant,
    ): ApprovalPolicySnapshot =
        persistence.loadActivePolicy(
            organizationId =
                organizationId,
            policyKey = policyKey,
            at = at,
        ) ?: throw ProductApiException(
            code =
                "APPROVAL_POLICY_NOT_ACTIVE",
            message =
                "The approval policy is not active.",
            status =
                HttpStatus.UNPROCESSABLE_ENTITY,
        )

    fun subjectStatus(
        subject: ApprovalSubjectRef,
        policyKey: String,
    ): ApprovalSubjectStatus? =
        persistence.loadSubjectApproval(subject, policyKey)?.let {
            ApprovalSubjectStatus(it.requestId, it.state, it.subjectVersion, it.policyKey)
        }

    private fun resolveAuthority(
        organizationId: UUID,
        authorityKey: String,
        at: java.time.Instant,
    ): ApprovalAuthorityBindingSnapshot =
        persistence.resolveCurrentAuthority(
            organizationId = organizationId,
            authorityKey = authorityKey,
            at = at,
        ) ?: throw ProductApiException(
            code = "APPROVAL_AUTHORITY_UNAVAILABLE",
            message = "The required approval authority is not currently assigned.",
            status = HttpStatus.SERVICE_UNAVAILABLE,
            retryable = false,
        )

    private fun resolveAuthority(
        subject: ApprovalSubjectRef,
        authorityKey: String,
        at: java.time.Instant,
    ): ApprovalAuthorityBindingSnapshot =
        persistence.resolveCurrentAuthorityForSubject(
            subject = subject,
            authorityKey = authorityKey,
            at = at,
        ) ?: throw ProductApiException(
            code =
                "APPROVAL_AUTHORITY_UNAVAILABLE",
            message =
                "The required approval authority is not currently assigned.",
            status =
                HttpStatus.SERVICE_UNAVAILABLE,
            retryable = false,
        )

    private fun requireActiveMember(
        organizationId: UUID,
        actorUserId: UUID,
        at: java.time.Instant,
    ) {
        if (
            !persistence.isActiveOrganizationMember(
                organizationId =
                    organizationId,
                actorUserId =
                    actorUserId,
                at = at,
            )
        ) {
            throw notVisible()
        }
    }

    private fun projectionIntent(
        eventId: UUID,
        tuple:
            com.hiltech.server.security
                .OpenFgaTuple,
        requestId: UUID,
        sourceVersion: Long,
        occurredAt: java.time.Instant,
    ): AuthorizationProjectionIntent =
        AuthorizationProjectionIntent(
            eventId = eventId,
            tuple = tuple,
            desiredState =
                AuthorizationDesiredState
                    .PRESENT,
            sourceType =
                "ApprovalRequest",
            sourceId =
                requestId.toString(),
            sourceVersion =
                sourceVersion,
            eventType =
                "APPROVAL_AUTHORIZATION_CHANGED",
            occurredAt = occurredAt,
        )

    private fun revokeApproverIntent(
        context: ApprovalDecisionContext,
        occurredAt: java.time.Instant,
    ): AuthorizationProjectionIntent =
        AuthorizationProjectionIntent(
            eventId = UUID.randomUUID(),
            tuple =
                ApprovalAuthorizationRelations
                    .approver(
                        principalType =
                            context
                                .assignmentPrincipalType,
                        principalId =
                            context
                                .assignmentPrincipalId,
                        approvalRequestId =
                            context.requestId,
                    ),
            desiredState =
                AuthorizationDesiredState
                    .ABSENT,
            sourceType =
                "ApprovalRequest",
            sourceId =
                context.requestId.toString(),
            sourceVersion =
                context.requestVersion + 1,
            eventType =
                "APPROVAL_AUTHORIZATION_CHANGED",
            occurredAt =
                occurredAt,
        )

    private fun decisionEvent(
        decision: ApprovalDecisionType,
        context: ApprovalDecisionContext,
        actorUserId: UUID,
        occurredAt: java.time.Instant,
        correlationId: String,
    ): ApprovalDecisionEvent {
        val commonId =
            UUID.randomUUID()
        return when (decision) {
            ApprovalDecisionType.APPROVE ->
                ApprovalApproved(
                    eventId = commonId,
                    approvalRequestId =
                        context.requestId,
                    organizationId =
                        context.organizationId,
                    subjectType =
                        context.subjectType,
                    subjectId =
                        context.subjectId,
                    subjectVersion =
                        context.subjectVersion,
                    actorIdentityId =
                        actorUserId,
                    occurredAt =
                        occurredAt,
                    correlationId =
                        correlationId,
                )

            ApprovalDecisionType.REJECT ->
                ApprovalRejected(
                    eventId = commonId,
                    approvalRequestId =
                        context.requestId,
                    organizationId =
                        context.organizationId,
                    subjectType =
                        context.subjectType,
                    subjectId =
                        context.subjectId,
                    subjectVersion =
                        context.subjectVersion,
                    actorIdentityId =
                        actorUserId,
                    occurredAt =
                        occurredAt,
                    correlationId =
                        correlationId,
                )

            ApprovalDecisionType.REQUEST_CHANGE ->
                ApprovalChangeRequested(
                    eventId = commonId,
                    approvalRequestId =
                        context.requestId,
                    organizationId =
                        context.organizationId,
                    subjectType =
                        context.subjectType,
                    subjectId =
                        context.subjectId,
                    subjectVersion =
                        context.subjectVersion,
                    actorIdentityId =
                        actorUserId,
                    occurredAt =
                        occurredAt,
                    correlationId =
                        correlationId,
                )
        }
    }

    private fun ApprovalDecisionType
        .toRequestState():
        ApprovalRequestState =
        when (this) {
            ApprovalDecisionType.APPROVE ->
                ApprovalRequestState.APPROVED

            ApprovalDecisionType.REJECT ->
                ApprovalRequestState.REJECTED

            ApprovalDecisionType.REQUEST_CHANGE ->
                ApprovalRequestState
                    .CHANGE_REQUESTED
        }

    private fun decisionPayload(
        requestId: UUID,
        state: ApprovalRequestState,
    ): String =
        buildJsonObject {
            put(
                "approvalRequestId",
                requestId.toString(),
            )
            put(
                "state",
                state.name,
            )
        }.toString()

    private fun requirePayload(
        raw: String?,
    ) =
        raw
            ?.let {
                json.parseToJsonElement(it)
                    .jsonObject
            }
            ?: error(
                "Approval command result payload is missing.",
            )

    private fun normalizeKey(
        raw: String,
        errorCode: String,
    ): String {
        val normalized =
            raw.trim().uppercase()
        if (
            !KEY_PATTERN.matches(
                normalized,
            )
        ) {
            throw ProductApiException(
                code = errorCode,
                message =
                    "Approval request contains an invalid key.",
                status =
                    HttpStatus.BAD_REQUEST,
            )
        }
        return normalized
    }

    private fun notVisible():
        ProductApiException =
        ProductApiException(
            code =
                "OBJECT_NOT_VISIBLE",
            message =
                "The requested approval is not available.",
            status =
                HttpStatus.NOT_FOUND,
        )

    companion object {
        private val KEY_PATTERN =
            Regex(
                "^[A-Z][A-Z0-9_]{2,95}$",
            )
    }
}

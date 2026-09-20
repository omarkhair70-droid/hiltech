package com.hiltech.server.people

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.util.Locale
import java.util.UUID

@Component
class OnboardingService(
    private val persistence:
        OnboardingPersistencePort,
    private val authorization:
        PeopleAuthorizationPort,
    private val idempotency:
        IdempotentCommandExecutor,
    private val audit:
        AuditEventWriter,
    private val events:
        ApplicationEventPublisher,
    private val clock: Clock,
) {
    fun start(
        command: StartOnboardingCommand,
    ): OnboardingCommandResult {
        requirePositiveVersion(
            command.baseEmployeeVersion,
            "baseEmployeeVersion",
        )

        val employee =
            requireEmployee(command.employeeId)
        requireManage(
            command.actorUserId,
            employee.organizationId,
        )
        requirePreboarding(employee)
        requireEmployeeVersion(
            command.baseEmployeeVersion,
            employee.employeeVersion,
        )

        val policy =
            requireApplicablePolicy(
                command.onboardingPolicyRevisionId,
                employee.organizationId,
            )
        val caseId =
            deterministicId(
                "onboarding-case",
                command.operationId,
            )
        val fingerprint =
            IdempotencyKeyContract.fingerprint(
                listOf(
                    employee.employeeId,
                    employee.organizationId,
                    employee.employeeVersion,
                    policy.configRevisionId,
                ).joinToString("|"),
            )

        val execution =
            idempotency.execute(
                IdempotentCommandSpec(
                    operationId =
                        command.operationId,
                    actorUserId =
                        command.actorUserId,
                    commandType =
                        "PEOPLE_START_ONBOARDING",
                    targetType =
                        "ONBOARDING_CASE",
                    targetId = caseId,
                    requestFingerprint =
                        fingerprint,
                    correlationId =
                        command.correlationId,
                ),
            ) {
                val current =
                    requireEmployee(
                        command.employeeId,
                    )
                requireManage(
                    command.actorUserId,
                    current.organizationId,
                )
                requirePreboarding(current)
                requireEmployeeVersion(
                    command.baseEmployeeVersion,
                    current.employeeVersion,
                )
                requireApplicablePolicy(
                    command.onboardingPolicyRevisionId,
                    current.organizationId,
                )

                persistence
                    .openCaseForEmployee(
                        current.employeeId,
                    )
                    ?.let {
                        existing ->
                        if (
                            existing.caseId !=
                            caseId
                        ) {
                            throw onboardingError(
                                code =
                                    "OPEN_ONBOARDING_CASE_EXISTS",
                                message =
                                    "The employee already has an open onboarding case.",
                                status =
                                    HttpStatus.CONFLICT,
                                currentVersion =
                                    existing.version,
                            )
                        }
                    }

                val now = clock.instant()
                try {
                    persistence.insertCase(
                        caseId = caseId,
                        organizationId =
                            current.organizationId,
                        employeeId =
                            current.employeeId,
                        policyConfigRevisionId =
                            policy.configRevisionId,
                        actorUserId =
                            command.actorUserId,
                        at = now,
                    )
                } catch (
                    failure:
                        DataIntegrityViolationException,
                ) {
                    throw onboardingError(
                        code =
                            "ONBOARDING_CASE_CONFLICT",
                        message =
                            "The onboarding case conflicts with current People state.",
                        status =
                            HttpStatus.CONFLICT,
                    )
                }

                audit.append(
                    AuditEventRecord(
                        actorUserId =
                            command.actorUserId,
                        action =
                            "ONBOARDING_STARTED",
                        targetType =
                            "ONBOARDING_CASE",
                        targetId = caseId,
                        newStateRef = "OPEN",
                        safeDiffJson =
                            """{"fields":["employee","policyRevision"]}""",
                        occurredAt = now,
                        correlationId =
                            command.correlationId,
                        configRevisionRefsJson =
                            buildJsonArray {
                                add(
                                    JsonPrimitive(
                                        policy
                                            .configRevisionId
                                            .toString(),
                                    ),
                                )
                            }.toString(),
                    ),
                )
                events.publishEvent(
                    OnboardingStarted(
                        onboardingCaseId =
                            caseId,
                        organizationId =
                            current.organizationId,
                        employeeId =
                            current.employeeId,
                        policyConfigRevisionId =
                            policy.configRevisionId,
                        sourceVersion = 1,
                        actorUserId =
                            command.actorUserId,
                        occurredAt = now,
                        correlationId =
                            command.correlationId,
                    ),
                )

                IdempotentCommandOutcome(
                    resultCode =
                        "ONBOARDING_STARTED",
                    resultPayloadJson =
                        buildJsonObject {
                            put(
                                "caseId",
                                caseId.toString(),
                            )
                        }.toString(),
                )
            }

        return OnboardingCommandResult(
            onboarding =
                adminCaseById(
                    actorUserId =
                        command.actorUserId,
                    caseId = caseId,
                ),
            replayed =
                execution.replayed,
        )
    }

    fun resolveRequirement(
        command:
            ResolveOnboardingRequirementCommand,
    ): OnboardingCommandResult {
        requirePositiveVersion(
            command.baseVersion,
            "baseVersion",
        )
        val key =
            normalizeRequirementKey(
                command.requirementKey,
            )
        val reason =
            optionalText(
                command.reason,
                500,
            )
        val initial =
            requireCase(
                command.onboardingCaseId,
            )
        requireManage(
            command.actorUserId,
            initial.organizationId,
        )
        val requirement =
            requireRequirement(
                initial,
                key,
            )
        validateManualResolution(
            requirement,
            command.resolution,
            reason,
        )

        val fingerprint =
            IdempotencyKeyContract.fingerprint(
                listOf(
                    initial.caseId,
                    command.baseVersion,
                    key,
                    command.resolution,
                    reason ?: "",
                ).joinToString("|"),
            )

        val execution =
            idempotency.execute(
                IdempotentCommandSpec(
                    operationId =
                        command.operationId,
                    actorUserId =
                        command.actorUserId,
                    commandType =
                        "PEOPLE_RESOLVE_ONBOARDING_REQUIREMENT",
                    targetType =
                        "ONBOARDING_CASE",
                    targetId =
                        initial.caseId,
                    requestFingerprint =
                        fingerprint,
                    correlationId =
                        command.correlationId,
                ),
            ) {
                val current =
                    requireCase(
                        command.onboardingCaseId,
                    )
                requireManage(
                    command.actorUserId,
                    current.organizationId,
                )
                requireOpenCase(current)

                if (
                    current.version !=
                    command.baseVersion
                ) {
                    throw onboardingError(
                        code =
                            "ONBOARDING_VERSION_CONFLICT",
                        message =
                            "The onboarding case has changed. Refresh before retrying.",
                        status =
                            HttpStatus.CONFLICT,
                        currentVersion =
                            current.version,
                    )
                }

                val currentRequirement =
                    requireRequirement(
                        current,
                        key,
                    )
                validateManualResolution(
                    currentRequirement,
                    command.resolution,
                    reason,
                )

                val now = clock.instant()
                persistence.upsertManualResolution(
                    resolutionId =
                        deterministicRequirementResolutionId(
                            current.caseId,
                            key,
                        ),
                    caseId = current.caseId,
                    requirementKey = key,
                    resolution =
                        command.resolution,
                    reason = reason,
                    actorUserId =
                        command.actorUserId,
                    at = now,
                )

                if (
                    !persistence.bumpCaseVersion(
                        caseId =
                            current.caseId,
                        expectedVersion =
                            command.baseVersion,
                        at = now,
                    )
                ) {
                    throw onboardingError(
                        code =
                            "ONBOARDING_VERSION_CONFLICT",
                        message =
                            "The onboarding case changed while resolving the requirement.",
                        status =
                            HttpStatus.CONFLICT,
                    )
                }

                val updated =
                    requireNotNull(
                        persistence.loadCase(
                            current.caseId,
                        ),
                    )

                audit.append(
                    AuditEventRecord(
                        actorUserId =
                            command.actorUserId,
                        action =
                            "ONBOARDING_REQUIREMENT_RESOLVED",
                        targetType =
                            "ONBOARDING_CASE",
                        targetId =
                            current.caseId,
                        safeDiffJson =
                            """{"fields":["requirementKey","resolution"]}""",
                        occurredAt = now,
                        correlationId =
                            command.correlationId,
                        reason =
                            if (
                                command.resolution ==
                                OnboardingManualResolutionType
                                    .WAIVED
                            ) {
                                reason
                            } else {
                                null
                            },
                    ),
                )
                events.publishEvent(
                    OnboardingRequirementResolved(
                        onboardingCaseId =
                            current.caseId,
                        organizationId =
                            current.organizationId,
                        employeeId =
                            current.employeeId,
                        requirementKey = key,
                        resolution =
                            command.resolution,
                        sourceVersion =
                            updated.version,
                        actorUserId =
                            command.actorUserId,
                        occurredAt = now,
                        correlationId =
                            command.correlationId,
                    ),
                )

                IdempotentCommandOutcome(
                    resultCode =
                        "ONBOARDING_REQUIREMENT_RESOLVED",
                    resultPayloadJson =
                        buildJsonObject {
                            put(
                                "caseId",
                                current.caseId
                                    .toString(),
                            )
                            put(
                                "version",
                                updated.version,
                            )
                        }.toString(),
                )
            }

        return OnboardingCommandResult(
            onboarding =
                adminCaseById(
                    actorUserId =
                        command.actorUserId,
                    caseId =
                        command.onboardingCaseId,
                ),
            replayed =
                execution.replayed,
        )
    }

    fun activate(
        command:
            ActivateEmployeeOnboardingCommand,
    ): OnboardingCommandResult {
        requirePositiveVersion(
            command.caseBaseVersion,
            "caseBaseVersion",
        )
        requirePositiveVersion(
            command.employeeBaseVersion,
            "employeeBaseVersion",
        )

        val initial =
            requireCase(
                command.onboardingCaseId,
            )
        requireManage(
            command.actorUserId,
            initial.organizationId,
        )

        val fingerprint =
            IdempotencyKeyContract.fingerprint(
                listOf(
                    initial.caseId,
                    command.caseBaseVersion,
                    command.employeeBaseVersion,
                ).joinToString("|"),
            )

        val execution =
            idempotency.execute(
                IdempotentCommandSpec(
                    operationId =
                        command.operationId,
                    actorUserId =
                        command.actorUserId,
                    commandType =
                        "PEOPLE_ACTIVATE_EMPLOYEE",
                    targetType =
                        "ONBOARDING_CASE",
                    targetId =
                        initial.caseId,
                    requestFingerprint =
                        fingerprint,
                    correlationId =
                        command.correlationId,
                ),
            ) {
                val currentCase =
                    requireCase(
                        command.onboardingCaseId,
                    )
                requireManage(
                    command.actorUserId,
                    currentCase.organizationId,
                )
                requireOpenCase(currentCase)

                if (
                    currentCase.version !=
                    command.caseBaseVersion
                ) {
                    throw onboardingError(
                        code =
                            "ONBOARDING_VERSION_CONFLICT",
                        message =
                            "The onboarding case has changed. Refresh before retrying.",
                        status =
                            HttpStatus.CONFLICT,
                        currentVersion =
                            currentCase.version,
                    )
                }

                val employee =
                    requireEmployee(
                        currentCase.employeeId,
                    )
                requirePreboarding(employee)

                if (
                    employee.employeeVersion !=
                    command.employeeBaseVersion
                ) {
                    throw onboardingError(
                        code =
                            "EMPLOYEE_VERSION_CONFLICT",
                        message =
                            "The employee has changed. Refresh before retrying.",
                        status =
                            HttpStatus.CONFLICT,
                        currentVersion =
                            employee.employeeVersion,
                    )
                }

                val evaluated =
                    evaluate(
                        currentCase,
                        employee,
                    )
                val blockers =
                    evaluated.requirements
                        .filter {
                            it.blocking &&
                                it.status !in
                                setOf(
                                    OnboardingRequirementStatus
                                        .SATISFIED,
                                    OnboardingRequirementStatus
                                        .WAIVED,
                                )
                        }

                if (blockers.isNotEmpty()) {
                    throw ProductApiException(
                        code =
                            "ONBOARDING_BLOCKERS_REMAIN",
                        message =
                            "Blocking onboarding requirements remain.",
                        status =
                            HttpStatus.CONFLICT,
                        details =
                            buildJsonObject {
                                putJsonArray(
                                    "blockingRequirementKeys",
                                ) {
                                    blockers.forEach {
                                        add(
                                            JsonPrimitive(
                                                it.requirementKey,
                                            ),
                                        )
                                    }
                                }
                            },
                        currentVersion =
                            currentCase.version,
                    )
                }

                val now = clock.instant()

                if (
                    !persistence.activateEmployee(
                        employeeId =
                            employee.employeeId,
                        expectedVersion =
                            command.employeeBaseVersion,
                        at = now,
                    )
                ) {
                    throw onboardingError(
                        code =
                            "EMPLOYEE_VERSION_CONFLICT",
                        message =
                            "The employee changed while activating.",
                        status =
                            HttpStatus.CONFLICT,
                    )
                }

                if (
                    !persistence.activateCase(
                        caseId =
                            currentCase.caseId,
                        expectedVersion =
                            command.caseBaseVersion,
                        actorUserId =
                            command.actorUserId,
                        at = now,
                    )
                ) {
                    throw onboardingError(
                        code =
                            "ONBOARDING_VERSION_CONFLICT",
                        message =
                            "The onboarding case changed while activating.",
                        status =
                            HttpStatus.CONFLICT,
                    )
                }

                val updatedCase =
                    requireNotNull(
                        persistence.loadCase(
                            currentCase.caseId,
                        ),
                    )
                val updatedEmployee =
                    requireEmployee(
                        employee.employeeId,
                    )

                audit.append(
                    AuditEventRecord(
                        actorUserId =
                            command.actorUserId,
                        action =
                            "EMPLOYEE_ACTIVATED",
                        targetType =
                            "EMPLOYEE",
                        targetId =
                            employee.employeeId,
                        previousStateRef =
                            "PREBOARDING",
                        newStateRef =
                            "ACTIVE",
                        safeDiffJson =
                            """{"fields":["employeeState","onboardingCase"]}""",
                        occurredAt = now,
                        correlationId =
                            command.correlationId,
                        configRevisionRefsJson =
                            buildJsonArray {
                                add(
                                    JsonPrimitive(
                                        currentCase
                                            .policyConfigRevisionId
                                            .toString(),
                                    ),
                                )
                            }.toString(),
                    ),
                )

                events.publishEvent(
                    EmployeeActivated(
                        onboardingCaseId =
                            currentCase.caseId,
                        organizationId =
                            currentCase.organizationId,
                        employeeId =
                            employee.employeeId,
                        sourceVersion =
                            updatedEmployee
                                .employeeVersion,
                        actorUserId =
                            command.actorUserId,
                        occurredAt = now,
                        correlationId =
                            command.correlationId,
                    ),
                )
                events.publishEvent(
                    OnboardingActivated(
                        onboardingCaseId =
                            currentCase.caseId,
                        organizationId =
                            currentCase.organizationId,
                        employeeId =
                            employee.employeeId,
                        sourceVersion =
                            updatedCase.version,
                        actorUserId =
                            command.actorUserId,
                        occurredAt = now,
                        correlationId =
                            command.correlationId,
                    ),
                )

                IdempotentCommandOutcome(
                    resultCode =
                        "EMPLOYEE_ACTIVATED",
                    resultPayloadJson =
                        buildJsonObject {
                            put(
                                "caseId",
                                currentCase.caseId
                                    .toString(),
                            )
                            put(
                                "employeeId",
                                employee.employeeId
                                    .toString(),
                            )
                        }.toString(),
                )
            }

        return OnboardingCommandResult(
            onboarding =
                adminCaseById(
                    actorUserId =
                        command.actorUserId,
                    caseId =
                        command.onboardingCaseId,
                ),
            replayed =
                execution.replayed,
        )
    }

    fun adminCase(
        actorUserId: UUID,
        employeeId: UUID,
    ): OnboardingCaseView {
        val employee =
            requireEmployee(employeeId)
        requireManage(
            actorUserId,
            employee.organizationId,
        )

        val case =
            persistence
                .latestCaseForEmployee(
                    employeeId,
                )
                ?: throw onboardingError(
                    code =
                        "ONBOARDING_CASE_NOT_FOUND",
                    message =
                        "No onboarding case exists for the employee.",
                    status =
                        HttpStatus.NOT_FOUND,
                )

        return evaluate(case, employee)
    }

    fun adminCaseById(
        actorUserId: UUID,
        caseId: UUID,
    ): OnboardingCaseView {
        val case = requireCase(caseId)
        requireManage(
            actorUserId,
            case.organizationId,
        )
        return evaluate(
            case,
            requireEmployee(
                case.employeeId,
            ),
        )
    }

    fun own(
        actorUserId: UUID,
        organizationId: UUID,
    ): OnboardingCaseView {
        val employee =
            persistence.ownEmployee(
                identityId =
                    actorUserId,
                organizationId =
                    organizationId,
                at = clock.instant(),
            )
                ?: throw onboardingError(
                    code =
                        "OWN_EMPLOYEE_NOT_FOUND",
                    message =
                        "No employee record is linked to this identity in the organization.",
                    status =
                        HttpStatus.NOT_FOUND,
                )

        val case =
            persistence
                .latestCaseForEmployee(
                    employee.employeeId,
                )
                ?: throw onboardingError(
                    code =
                        "ONBOARDING_CASE_NOT_FOUND",
                    message =
                        "No onboarding case exists for this employee.",
                    status =
                        HttpStatus.NOT_FOUND,
                )

        return evaluate(
            case,
            employee,
            selfServiceOnly = true,
        )
    }

    private fun evaluate(
        case: OnboardingCaseRecord,
        employee:
            OnboardingEmployeeContext,
        selfServiceOnly: Boolean = false,
    ): OnboardingCaseView {
        val policy =
            persistence.policy(
                case.policyConfigRevisionId,
            )
                ?: throw onboardingError(
                    code =
                        "ONBOARDING_POLICY_NOT_FOUND",
                    message =
                        "The onboarding policy revision no longer exists.",
                    status =
                        HttpStatus.CONFLICT,
                )
        val definitions =
            persistence.policyRequirements(
                policy.configRevisionId,
            )
        val evaluated =
            definitions.map {
                evaluateRequirement(
                    case = case,
                    employee = employee,
                    definition = it,
                )
            }
        val visible =
            if (selfServiceOnly) {
                evaluated.filterIndexed {
                    index, _ ->
                    definitions[index]
                        .selfServiceVisible
                }
            } else {
                evaluated
            }

        return OnboardingCaseView(
            caseId = case.caseId,
            organizationId =
                case.organizationId,
            employeeId =
                employee.employeeId,
            employeeCode =
                employee.employeeCode,
            employeeDisplayName =
                employee.displayName,
            employeeState =
                employee.employeeState,
            employeeVersion =
                employee.employeeVersion,
            policyConfigRevisionId =
                policy.configRevisionId,
            policyCode = policy.code,
            policyRevisionNumber =
                policy.revisionNumber,
            state = case.state,
            requirements = visible,
            blockingSatisfied =
                evaluated
                    .filterIndexed {
                        index, _ ->
                        definitions[index]
                            .blocking
                    }
                    .all {
                        it.status in
                            setOf(
                                OnboardingRequirementStatus
                                    .SATISFIED,
                                OnboardingRequirementStatus
                                    .WAIVED,
                            )
                    },
            caseVersion = case.version,
            startedAt = case.startedAt,
            activatedAt =
                case.activatedAt,
        )
    }

    private fun evaluateRequirement(
        case: OnboardingCaseRecord,
        employee:
            OnboardingEmployeeContext,
        definition:
            OnboardingRequirementDefinition,
    ): OnboardingRequirementView {
        persistence
            .manualResolution(
                case.caseId,
                definition.requirementKey,
            )
            ?.let {
                manual ->
                if (
                    manual.resolution ==
                    OnboardingManualResolutionType
                        .WAIVED
                ) {
                    return requirementView(
                        definition,
                        OnboardingRequirementStatus
                            .WAIVED,
                        null,
                        "WAIVED",
                    )
                }
                if (
                    definition.requirementType ==
                    OnboardingRequirementType
                        .MANUAL_CONFIRMATION
                ) {
                    return requirementView(
                        definition,
                        OnboardingRequirementStatus
                            .SATISFIED,
                        null,
                        "MANUAL_SATISFIED",
                    )
                }
            }

        return when (
            definition.requirementType
        ) {
            OnboardingRequirementType
                .PROFILE_FIELD ->
                evaluateProfile(
                    employee,
                    definition,
                )

            OnboardingRequirementType
                .IDENTITY_READY ->
                if (
                    persistence.identityReady(
                        employee.employeeId,
                        employee.organizationId,
                        clock.instant(),
                    )
                ) {
                    requirementView(
                        definition,
                        OnboardingRequirementStatus
                            .SATISFIED,
                        null,
                        "READY",
                    )
                } else {
                    requirementView(
                        definition,
                        OnboardingRequirementStatus
                            .WAITING_HILTECH,
                        null,
                        "NOT_READY",
                    )
                }

            OnboardingRequirementType
                .WORKFORCE_ASSIGNMENT ->
                if (
                    persistence
                        .hasCurrentWorkforceAssignment(
                            employee.employeeId,
                            clock.instant(),
                        )
                ) {
                    requirementView(
                        definition,
                        OnboardingRequirementStatus
                            .SATISFIED,
                        null,
                        "ASSIGNED",
                    )
                } else {
                    requirementView(
                        definition,
                        OnboardingRequirementStatus
                            .WAITING_HILTECH,
                        null,
                        "MISSING",
                    )
                }

            OnboardingRequirementType
                .EMPLOYEE_DOCUMENT ->
                evaluateDocument(
                    employee,
                    definition,
                )

            OnboardingRequirementType
                .CERTIFICATION ->
                evaluateCertification(
                    employee,
                    definition,
                )

            OnboardingRequirementType
                .MANUAL_CONFIRMATION ->
                requirementView(
                    definition,
                    when (
                        definition.responsibility
                    ) {
                        OnboardingResponsibility
                            .EMPLOYEE ->
                            OnboardingRequirementStatus
                                .BLOCKED

                        OnboardingResponsibility
                            .HILTECH ->
                            OnboardingRequirementStatus
                                .WAITING_HILTECH

                        OnboardingResponsibility
                            .SHARED ->
                            OnboardingRequirementStatus
                                .BLOCKED
                    },
                    null,
                    "UNRESOLVED",
                )
        }
    }

    private fun evaluateProfile(
        employee:
            OnboardingEmployeeContext,
        definition:
            OnboardingRequirementDefinition,
    ): OnboardingRequirementView {
        val value =
            when (
                definition.profileFieldCode
            ) {
                "MOBILE" -> employee.mobile
                "EMAIL" -> employee.email
                else -> null
            }

        return if (!value.isNullOrBlank()) {
            requirementView(
                definition,
                OnboardingRequirementStatus
                    .SATISFIED,
                null,
                "PRESENT",
            )
        } else {
            requirementView(
                definition,
                OnboardingRequirementStatus
                    .NEEDS_EMPLOYEE,
                "UPDATE_OWN_PROFILE",
                "MISSING",
            )
        }
    }

    private fun evaluateDocument(
        employee:
            OnboardingEmployeeContext,
        definition:
            OnboardingRequirementDefinition,
    ): OnboardingRequirementView {
        val facts =
            persistence.documentFacts(
                employee.employeeId,
                requireNotNull(
                    definition.documentTypeCode,
                ),
            )

        val satisfied =
            facts.any {
                it.verificationState ==
                    HrVerificationState.VERIFIED &&
                    (
                        !definition.evidenceRequired ||
                            it.evidenceStorageState ==
                            "READY"
                    )
            }
        if (satisfied) {
            return requirementView(
                definition,
                OnboardingRequirementStatus
                    .SATISFIED,
                null,
                "VERIFIED",
            )
        }

        val latest =
            facts.firstOrNull()
                ?: return documentActionState(
                    definition,
                    "MISSING",
                )

        if (
            latest.verificationState ==
            HrVerificationState.REJECTED
        ) {
            return documentActionState(
                definition,
                "REJECTED",
            )
        }

        if (
            definition.evidenceRequired &&
            latest.evidenceStorageState !=
            "READY"
        ) {
            return documentActionState(
                definition,
                latest.evidenceStorageState
                    ?: "EVIDENCE_MISSING",
            )
        }

        if (
            latest.verificationState ==
            HrVerificationState.UNVERIFIED
        ) {
            return requirementView(
                definition,
                OnboardingRequirementStatus
                    .WAITING_HILTECH,
                null,
                "WAITING_VERIFICATION",
            )
        }

        return requirementView(
            definition,
            OnboardingRequirementStatus
                .BLOCKED,
            null,
            "INVALID_DOCUMENT_STATE",
        )
    }

    private fun documentActionState(
        definition:
            OnboardingRequirementDefinition,
        sourceState: String,
    ): OnboardingRequirementView =
        if (definition.employeeMaySubmit) {
            requirementView(
                definition,
                OnboardingRequirementStatus
                    .NEEDS_EMPLOYEE,
                "SUBMIT_DOCUMENT",
                sourceState,
            )
        } else {
            requirementView(
                definition,
                OnboardingRequirementStatus
                    .WAITING_HILTECH,
                null,
                sourceState,
            )
        }

    private fun evaluateCertification(
        employee:
            OnboardingEmployeeContext,
        definition:
            OnboardingRequirementDefinition,
    ): OnboardingRequirementView {
        val now = clock.instant()
        val facts =
            persistence.certificationFacts(
                employee.employeeId,
                requireNotNull(
                    definition.certificationTypeCode,
                ),
            )
        val valid =
            facts.any {
                it.verificationState ==
                    HrVerificationState.VERIFIED &&
                    (
                        it.issuedAt == null ||
                            !it.issuedAt.isAfter(now)
                    ) &&
                    (
                        it.validUntil == null ||
                            !it.validUntil
                                .isBefore(now)
                    )
            }

        return if (valid) {
            requirementView(
                definition,
                OnboardingRequirementStatus
                    .SATISFIED,
                null,
                "VALID",
            )
        } else {
            requirementView(
                definition,
                OnboardingRequirementStatus
                    .WAITING_HILTECH,
                null,
                if (facts.isEmpty()) {
                    "MISSING"
                } else {
                    "NOT_VALID"
                },
            )
        }
    }

    private fun requirementView(
        definition:
            OnboardingRequirementDefinition,
        status: OnboardingRequirementStatus,
        actionCode: String?,
        sourceStateCode: String?,
    ): OnboardingRequirementView =
        OnboardingRequirementView(
            requirementKey =
                definition.requirementKey,
            label = definition.label,
            responsibility =
                definition.responsibility,
            blocking =
                definition.blocking,
            status = status,
            actionCode = actionCode,
            sourceStateCode =
                sourceStateCode,
            sortOrder =
                definition.sortOrder,
        )

    private fun requireApplicablePolicy(
        policyId: UUID,
        organizationId: UUID,
    ): OnboardingPolicyContext {
        val policy =
            persistence.policy(policyId)
                ?: throw onboardingError(
                    code =
                        "ONBOARDING_POLICY_NOT_FOUND",
                    message =
                        "The onboarding policy revision does not exist.",
                    status =
                        HttpStatus.NOT_FOUND,
                )

        if (policy.lifecycleState != "ACTIVE") {
            throw onboardingError(
                code =
                    "ONBOARDING_POLICY_NOT_ACTIVE",
                message =
                    "The onboarding policy revision is not active.",
                status =
                    HttpStatus.CONFLICT,
            )
        }

        val applicable =
            when (policy.scopeType) {
                "SYSTEM" ->
                    policy.scopeOrganizationId ==
                        null

                "ORGANIZATION" ->
                    policy.scopeOrganizationId ==
                        organizationId

                else -> false
            }

        if (!applicable) {
            throw onboardingError(
                code =
                    "ONBOARDING_POLICY_ORGANIZATION_MISMATCH",
                message =
                    "The onboarding policy does not apply to the employee organization.",
                status =
                    HttpStatus.CONFLICT,
            )
        }

        return policy
    }

    private fun requireRequirement(
        case: OnboardingCaseRecord,
        key: String,
    ): OnboardingRequirementDefinition =
        persistence
            .policyRequirements(
                case.policyConfigRevisionId,
            )
            .firstOrNull {
                it.requirementKey == key
            }
            ?: throw onboardingError(
                code =
                    "ONBOARDING_REQUIREMENT_NOT_FOUND",
                message =
                    "The onboarding requirement does not exist in this case policy.",
                status =
                    HttpStatus.NOT_FOUND,
            )

    private fun validateManualResolution(
        requirement:
            OnboardingRequirementDefinition,
        resolution:
            OnboardingManualResolutionType,
        reason: String?,
    ) {
        if (
            resolution ==
            OnboardingManualResolutionType.WAIVED
        ) {
            if (!requirement.waiverAllowed) {
                throw onboardingError(
                    code =
                        "ONBOARDING_WAIVER_NOT_ALLOWED",
                    message =
                        "This onboarding requirement cannot be waived.",
                    status =
                        HttpStatus.CONFLICT,
                )
            }
            if (reason.isNullOrBlank()) {
                throw onboardingError(
                    code =
                        "ONBOARDING_WAIVER_REASON_REQUIRED",
                    message =
                        "A waiver reason is required.",
                    status =
                        HttpStatus.BAD_REQUEST,
                )
            }
            return
        }

        if (
            requirement.requirementType !=
            OnboardingRequirementType
                .MANUAL_CONFIRMATION
        ) {
            throw onboardingError(
                code =
                    "ONBOARDING_MANUAL_RESOLUTION_NOT_ALLOWED",
                message =
                    "This source-backed requirement cannot be manually satisfied.",
                status =
                    HttpStatus.CONFLICT,
            )
        }
    }

    private fun requireEmployee(
        employeeId: UUID,
    ): OnboardingEmployeeContext =
        persistence.employee(employeeId)
            ?: throw onboardingError(
                code = "EMPLOYEE_NOT_FOUND",
                message =
                    "The employee does not exist.",
                status =
                    HttpStatus.NOT_FOUND,
            )

    private fun requireCase(
        caseId: UUID,
    ): OnboardingCaseRecord =
        persistence.loadCase(caseId)
            ?: throw onboardingError(
                code =
                    "ONBOARDING_CASE_NOT_FOUND",
                message =
                    "The onboarding case does not exist.",
                status =
                    HttpStatus.NOT_FOUND,
            )

    private fun requirePreboarding(
        employee: OnboardingEmployeeContext,
    ) {
        if (
            employee.employeeState !=
            EmployeeState.PREBOARDING
        ) {
            throw onboardingError(
                code =
                    "EMPLOYEE_NOT_PREBOARDING",
                message =
                    "Only a preboarding employee can use this onboarding transition.",
                status =
                    HttpStatus.CONFLICT,
            )
        }
    }

    private fun requireOpenCase(
        case: OnboardingCaseRecord,
    ) {
        if (
            case.state != OnboardingCaseState.OPEN
        ) {
            throw onboardingError(
                code =
                    "ONBOARDING_CASE_NOT_OPEN",
                message =
                    "The onboarding case is not open.",
                status =
                    HttpStatus.CONFLICT,
            )
        }
    }

    private fun requireManage(
        actorUserId: UUID,
        organizationId: UUID,
    ) {
        if (
            !authorization.canManagePeople(
                actorUserId =
                    actorUserId,
                organizationId =
                    organizationId,
            )
        ) {
            throw onboardingError(
                code =
                    "PEOPLE_ACCESS_DENIED",
                message =
                    "You are not authorized for this People action.",
                status =
                    HttpStatus.FORBIDDEN,
            )
        }
    }

    private fun requireEmployeeVersion(
        expected: Long,
        current: Long,
    ) {
        if (expected != current) {
            throw onboardingError(
                code =
                    "EMPLOYEE_VERSION_CONFLICT",
                message =
                    "The employee has changed. Refresh before retrying.",
                status =
                    HttpStatus.CONFLICT,
                currentVersion = current,
            )
        }
    }

    private fun requirePositiveVersion(
        value: Long,
        field: String,
    ) {
        if (value < 1) {
            throw onboardingError(
                code =
                    "INVALID_BASE_VERSION",
                message =
                    "$field must be positive.",
                status =
                    HttpStatus.BAD_REQUEST,
            )
        }
    }

    private fun normalizeRequirementKey(
        value: String,
    ): String {
        val normalized =
            value.trim()
                .uppercase(Locale.ROOT)
        if (
            !Regex(
                "^[A-Z][A-Z0-9_-]{0,63}$",
            ).matches(normalized)
        ) {
            throw onboardingError(
                code =
                    "INVALID_ONBOARDING_REQUIREMENT_KEY",
                message =
                    "The onboarding requirement key is invalid.",
                status =
                    HttpStatus.BAD_REQUEST,
            )
        }
        return normalized
    }

    private fun optionalText(
        value: String?,
        maxLength: Int,
    ): String? {
        val normalized =
            value?.trim()
                ?.takeIf {
                    it.isNotEmpty()
                }
                ?: return null

        if (normalized.length > maxLength) {
            throw onboardingError(
                code = "INVALID_TEXT",
                message =
                    "A text value exceeds its allowed length.",
                status =
                    HttpStatus.BAD_REQUEST,
            )
        }

        return normalized
    }

    private fun deterministicId(
        prefix: String,
        operationId: UUID,
    ): UUID =
        UUID.nameUUIDFromBytes(
            "$prefix:$operationId"
                .toByteArray(
                    StandardCharsets.UTF_8,
                ),
        )

    private fun deterministicRequirementResolutionId(
        caseId: UUID,
        requirementKey: String,
    ): UUID =
        UUID.nameUUIDFromBytes(
            (
                "onboarding-resolution:" +
                    caseId +
                    ":" +
                    requirementKey
            ).toByteArray(
                StandardCharsets.UTF_8,
            ),
        )

    private fun onboardingError(
        code: String,
        message: String,
        status: HttpStatus,
        currentVersion: Long? = null,
    ): ProductApiException =
        ProductApiException(
            code = code,
            message = message,
            status = status,
            currentVersion =
                currentVersion,
        )
}

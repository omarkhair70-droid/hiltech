package com.hiltech.shared.core.people

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechApiException
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable

@Serializable
enum class OnboardingWorkspaceAction { START, RESOLVE, ACTIVATE }

/** Captured command intent. A retry must preserve every field, including the source versions. */
@Serializable
data class OnboardingWorkspaceIntent(
    val operationId: String,
    val employeeId: String,
    val organizationId: String,
    val employeeDisplayName: String,
    val employeeVersion: Long,
    val policyConfigRevisionId: String,
    val action: OnboardingWorkspaceAction,
    val policyLabel: String,
    val caseId: String? = null,
    val caseVersion: Long? = null,
    val requirementKey: String? = null,
    val requirementLabel: String? = null,
    val resolution: String? = null,
    val reason: String? = null,
)

typealias OnboardingWorkspaceSubmit = suspend (
    intent: OnboardingWorkspaceIntent,
    priorOutcomeUnknown: Boolean,
    onCommandSending: () -> Unit,
) -> OnboardingCommandResponseDto

/** The mutation has not been sent. This never settles an earlier unknown outcome. */
class OnboardingPreparationException(val safeCode: String) : IllegalStateException(safeCode)

data class OnboardingFailureDisposition(val hasUnknownOutcome: Boolean, val rejectionConfirmed: Boolean)

fun onboardingFailureDisposition(hadUnknownOutcome: Boolean, failure: Throwable): OnboardingFailureDisposition {
    if (failure is OnboardingPreparationException) return OnboardingFailureDisposition(hadUnknownOutcome, !hadUnknownOutcome)
    val api = failure as? HiltechApiException
    val rejected = api != null && api.httpStatus?.let { it in 400..499 } == true &&
        api.envelope?.code == api.code && !api.retryable && api.code != "IDEMPOTENCY_KEY_REUSED"
    val storedFailure = rejected && api?.httpStatus == 409 && api?.code == "OPERATION_PREVIOUSLY_FAILED"
    return OnboardingFailureDisposition(!storedFailure && (hadUnknownOutcome || !rejected), rejected && (!hadUnknownOutcome || storedFailure))
}

fun onboardingWorkspaceMatchesEmployee(
    value: OnboardingWorkspaceDto,
    employeeId: String,
    organizationId: String,
): Boolean = value.employeeId == employeeId && value.organizationId == organizationId &&
    value.employeeVersion >= 1 && value.currentCase?.let {
        it.employeeId == employeeId && it.organizationId == organizationId &&
            it.employeeVersion == value.employeeVersion && it.employeeState == value.employeeState &&
            it.caseVersion >= 1
    } != false

fun onboardingIntentIsCurrent(intent: OnboardingWorkspaceIntent, current: OnboardingWorkspaceDto): Boolean {
    if (!onboardingWorkspaceMatchesEmployee(current, intent.employeeId, intent.organizationId) ||
        current.employeeVersion != intent.employeeVersion || current.employeeState != "PREBOARDING") return false
    if (intent.action == OnboardingWorkspaceAction.START) {
        return current.startAvailability == "AVAILABLE" && current.currentCase == null &&
            current.policies.any { it.configRevisionId == intent.policyConfigRevisionId }
    }
    val case = current.currentCase ?: return false
    if (case.caseId != intent.caseId || case.caseVersion != intent.caseVersion ||
        case.policyConfigRevisionId != intent.policyConfigRevisionId || case.state != "OPEN") return false
    return when (intent.action) {
        OnboardingWorkspaceAction.ACTIVATE -> case.canActivate && case.blockingSatisfied
        OnboardingWorkspaceAction.RESOLVE -> case.requirements.any {
            it.requirementKey == intent.requirementKey && intent.resolution in it.allowedResolutions
        }
        OnboardingWorkspaceAction.START -> false
    }
}

/** Receipt matching confirms this operation; it does not replace a fresh current-state read. */
fun onboardingReceiptMatches(intent: OnboardingWorkspaceIntent, response: OnboardingCommandResponseDto): Boolean {
    val case = response.onboarding
    if (response.operationId != intent.operationId || case.employeeId != intent.employeeId ||
        case.organizationId != intent.organizationId || case.policyConfigRevisionId != intent.policyConfigRevisionId ||
        case.employeeVersion < intent.employeeVersion || case.caseVersion < 1) return false
    if (intent.action == OnboardingWorkspaceAction.START) return true
    if (case.caseId != intent.caseId || case.caseVersion <= (intent.caseVersion ?: Long.MAX_VALUE)) return false
    return intent.action != OnboardingWorkspaceAction.ACTIVATE ||
        (case.state == "ACTIVATED" && case.employeeVersion > intent.employeeVersion)
}

/** Pin the initiating token; never acquire a later actor's token while resuming this command. */
suspend fun submitOnboardingInSession(
    httpClient: HttpClient,
    baseUrl: String,
    installationId: String,
    intent: OnboardingWorkspaceIntent,
    priorOutcomeUnknown: Boolean,
    sessionCurrent: () -> Boolean,
    accessToken: suspend () -> String?,
    correlationId: () -> String,
    onCommandSending: () -> Unit,
): OnboardingCommandResponseDto {
    currentCoroutineContext().ensureActive()
    if (!sessionCurrent()) throw OnboardingPreparationException("SESSION_CHANGED")
    val token = accessToken()?.takeIf { it.isNotBlank() }
        ?: throw OnboardingPreparationException("REAUTH_REQUIRED")
    currentCoroutineContext().ensureActive()
    if (!sessionCurrent()) throw OnboardingPreparationException("SESSION_CHANGED")
    val scoped = HiltechApiClient(
        client = httpClient,
        baseUrl = baseUrl,
        accessTokenProvider = {
            currentCoroutineContext().ensureActive()
            if (!sessionCurrent()) throw OnboardingPreparationException("SESSION_CHANGED")
            token
        },
        correlationIdProvider = correlationId,
    )
    return OnboardingApiClient(scoped).applyWorkspaceIntent(
        intent, installationId, priorOutcomeUnknown, sessionCurrent, onCommandSending,
    )
}

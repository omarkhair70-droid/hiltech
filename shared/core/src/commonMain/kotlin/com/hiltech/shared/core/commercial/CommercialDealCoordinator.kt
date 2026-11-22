package com.hiltech.shared.core.commercial

import com.hiltech.shared.core.CommercialDealAction
import com.hiltech.shared.core.validCommercialDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** A decimal must be a JSON number, NEVER Double or a quoted pseudo-amount. */
fun exactCommercialNumber(raw: String, positive: Boolean = false): JsonPrimitive {
    require(validCommercialDecimal(raw, positive)) { "Commercial numeric input is invalid." }
    return Json.parseToJsonElement(raw).jsonPrimitive
}

/**
 * Typed native command router. The caller owns durable, distinct UUID operation IDs.
 * Retrying a command must replay the SAME operationId, never generate another.
 * The server independently rechecks rights, revisions, and award evidence.
 */
class CommercialDealCoordinator(
    private val api: CommercialApiClient,
    private val installationId: String,
) {
    suspend fun loadOpportunity(opportunityId: String): CommercialDealDetailDto =
        api.detail(opportunityId, installationId)

    suspend fun loadPrivateCosting(opportunityId: String): List<CommercialPrivateCostLineDto> =
        api.privateCosting(opportunityId, installationId)

    suspend fun dispatch(action: CommercialDealAction, operationId: String): CommercialCommandResultDto {
        require(Regex("^[0-9a-fA-F-]{36}$").matches(operationId)) { "operationId must be a UUID." }
        return when (action) {
            is CommercialDealAction.Qualify -> api.qualify(
                action.opportunityId,
                QualifyCommercialOpportunityDto(
                    operationId,action.baseVersion,action.summary,action.questions,
                ),
                installationId,
            )
            is CommercialDealAction.SaveScope -> api.scope(
                action.opportunityId,
                CommercialScopeDto(
                    operationId,action.description,action.inclusions,action.exclusions,
                    action.assumptions,action.constraints,action.clientResponsibilities,
                ),
                installationId,
            )
            is CommercialDealAction.SaveBoq -> api.boq(
                action.opportunityId,
                CommercialBoqDto(
                    operationId=operationId,
                    scopeVersionId=action.scopeVersionId,
                    currency=action.currency,
                    lines=action.lines.map { line ->
                        CommercialBoqLineDto(
                            lineCode=line.lineCode, description=line.description,
                            category=line.category,
                            quantity=exactCommercialNumber(line.quantity,positive=true),
                            unit=line.unit,
                            internalUnitCost=line.internalUnitCost?.let(::exactCommercialNumber),
                            sellUnitPrice=exactCommercialNumber(line.sellUnitPrice),
                        )
                    },
                ),
                installationId,
            )
            is CommercialDealAction.DraftQuote -> api.draftQuote(
                action.opportunityId,
                CommercialQuoteDraftDto(
                    operationId,action.boqVersionId,action.paymentTerms,
                    action.deliveryTerms,action.exclusions,action.validityDate,
                ),
                installationId,
            )
            is CommercialDealAction.RequestApproval ->
                api.requestApproval(action.quoteId,operationId,installationId)
            is CommercialDealAction.ReleaseApprovedQuote ->
                api.releaseApprovedQuote(action.quoteId,operationId,installationId)
            is CommercialDealAction.DraftContract -> api.draftContract(
                action.opportunityId,
                CommercialContractDraftDto(
                    operationId=operationId,quoteVersionId=action.quoteId,
                    contractCode=action.code,warrantyTerms=action.warrantyTerms,
                    maintenanceTerms=action.maintenanceTerms,
                ),
                installationId,
            )
            is CommercialDealAction.ConfirmSigned ->
                api.confirmSigned(action.contractId,action.evidenceId,operationId,installationId)
            is CommercialDealAction.DraftClientPo -> api.draftClientPo(
                action.opportunityId,
                CommercialClientPoDraftDto(
                    operationId=operationId,contractId=action.contractId,
                    clientPoNumber=action.number,issuedOn=action.issuedOn,
                    amount=exactCommercialNumber(action.amount),currency=action.currency,
                ),
                installationId,
            )
            is CommercialDealAction.VerifyClientPo ->
                api.verifyClientPo(action.clientPoId,action.evidenceId,operationId,installationId)
            is CommercialDealAction.ConvertToProject -> api.convertToProject(
                action.opportunityId,
                CommercialConversionDto(operationId,action.contractId,action.clientPoId),
                installationId,
            )
        }
    }
}

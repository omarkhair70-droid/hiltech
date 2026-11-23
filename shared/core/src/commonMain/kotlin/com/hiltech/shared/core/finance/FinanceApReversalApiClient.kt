package com.hiltech.shared.core.finance

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class FinanceApReversalProposeDto(
    val operationId:String,
    val targetPeriodId:String,
    val reasonReference:String,
)
@Serializable
data class FinanceApReversalOperationDto(val operationId:String)
@Serializable
data class FinanceApReversalReceiptDto(
    val proposalId:String,
    val originalJournalId:String,
    val targetPeriodId:String,
    val sourceVersion:Long,
    val state:String,
    val currency:String,
    val grossAmount:String,
    val approvalRequestId:String?=null,
    val reversalJournalId:String?=null,
    val replayed:Boolean=false,
    val financialLedgerReversed:Boolean=false,
    val bankPaymentReversed:Boolean=false,
    val moneyTransferred:Boolean=false,
)

/**
 * Typed KMP client only; UI route is intentionally not mounted until
 * approved Finance company authority and end-to-end staging verification.
 */
class FinanceApReversalApiClient(
    private val api:HiltechApiClient,
    private val json:Json=Json { ignoreUnknownKeys=true; explicitNulls=false },
) {
    suspend fun propose(
        organizationId:String,originalJournalId:String,
        targetPeriodId:String,reasonReference:String,
        operationId:String,installationId:String,
    ):FinanceApReversalReceiptDto {
        require(reasonReference.trim().length in 8..180)
        val body=FinanceApReversalProposeDto(
            uuid(operationId),uuid(targetPeriodId),reasonReference.trim(),
        )
        return api.request(
            method=HttpMethod.Post,
            path=org(organizationId)+"/ap-journals/"+uuid(originalJournalId)+
                "/reversal-proposals",
            options=HiltechRequestOptions(
                installationId=installationId,idempotencyKey=uuid(operationId),
            ),
            requestBody=json.encodeToString(
                FinanceApReversalProposeDto.serializer(),body,
            ),
            decode={ json.decodeFromString(
                FinanceApReversalReceiptDto.serializer(),it,
            ) },
        )
    }
    suspend fun requestApproval(
        organizationId:String,proposalId:String,operationId:String,
        installationId:String,
    ):FinanceApReversalReceiptDto = command(
        organizationId,proposalId,operationId,installationId,"request-approval",
    )
    suspend fun post(
        organizationId:String,proposalId:String,operationId:String,
        installationId:String,
    ):FinanceApReversalReceiptDto = command(
        organizationId,proposalId,operationId,installationId,"post",
    )
    suspend fun status(
        organizationId:String,proposalId:String,installationId:String,
    ):FinanceApReversalReceiptDto=api.request(
        method=HttpMethod.Get,
        path=org(organizationId)+"/ap-reversal-proposals/"+uuid(proposalId),
        options=HiltechRequestOptions(installationId=installationId),
        decode={ json.decodeFromString(
            FinanceApReversalReceiptDto.serializer(),it,
        ) },
    )
    private suspend fun command(
        org:String,id:String,operation:String,installation:String,action:String,
    ):FinanceApReversalReceiptDto {
        require(action=="post" || action=="request-approval")
        return api.request(
            method=HttpMethod.Post,
            path=org(org)+"/ap-reversal-proposals/"+uuid(id)+"/"+action,
            options=HiltechRequestOptions(
                installationId=installation,idempotencyKey=uuid(operation),
            ),
            requestBody=json.encodeToString(
                FinanceApReversalOperationDto.serializer(),
                FinanceApReversalOperationDto(uuid(operation)),
            ),
            decode={ json.decodeFromString(
                FinanceApReversalReceiptDto.serializer(),it,
            ) },
        )
    }
    private fun org(id:String)="/v1/finance/organizations/"+uuid(id)
    private fun uuid(value:String):String {
        require(Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        ).matches(value)) { "Valid UUID required" }
        return value
    }
}

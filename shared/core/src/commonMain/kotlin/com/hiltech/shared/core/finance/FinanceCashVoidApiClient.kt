package com.hiltech.shared.core.finance

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class FinanceCashVoidPrepareDto(
    val operationId:String,val reasonReference:String,
)
@Serializable
data class FinanceCashVoidOperationDto(val operationId:String)
@Serializable
data class FinanceCashVoidReceiptDto(
    val proposalId:String,val organizationId:String,
    val originalAllocationId:String,val sourceKind:String,
    val sourceJournalId:String,val bankStatementLineId:String,
    val amount:String,val currency:String,val state:String,
    val approvalRequestId:String?=null,val correctionRecordId:String?=null,
    val replayed:Boolean=false,
    val originalRecordPreserved:Boolean=true,
    val originalMatchStillEffective:Boolean=true,
    val bankRefundInitiated:Boolean=false,
    val generalLedgerReversed:Boolean=false,
)

/** App-native correction of an accounting association, NEVER a bank refund. */
class FinanceCashVoidApiClient(
    private val api:HiltechApiClient,
    private val json:Json=Json {ignoreUnknownKeys=true;explicitNulls=false},
) {
    suspend fun prepare(
        org:String,original:String,body:FinanceCashVoidPrepareDto,
        installation:String,
    ):FinanceCashVoidReceiptDto {
        val operation=uuid(body.operationId)
        require(body.reasonReference.trim().length in 8..180)
        return api.request(
            method=HttpMethod.Post,
            path=base(org)+"/cash-allocations/"+uuid(original)+
                "/corrections/prepare",
            options=HiltechRequestOptions(
                installationId=installation,idempotencyKey=operation),
            requestBody=json.encodeToString(
                FinanceCashVoidPrepareDto.serializer(),body),
            decode={json.decodeFromString(
                FinanceCashVoidReceiptDto.serializer(),it)},
        )
    }
    suspend fun requestReview(
        org:String,proposal:String,operation:String,installation:String,
    ):FinanceCashVoidReceiptDto =
        command(org,proposal,operation,installation,"request-review")
    suspend fun record(
        org:String,proposal:String,operation:String,installation:String,
    ):FinanceCashVoidReceiptDto =
        command(org,proposal,operation,installation,"record")
    suspend fun operatorQueue(org:String,installation:String):List<FinanceCashVoidReceiptDto> =
        api.request(
            method=HttpMethod.Get,
            path=base(org)+"/cash-allocation-corrections/operator-queue",
            options=HiltechRequestOptions(installationId=installation),
            decode={json.decodeFromString(ListSerializer(FinanceCashVoidReceiptDto.serializer()),it)},
        )
    suspend fun status(
        org:String,proposal:String,installation:String,
    ):FinanceCashVoidReceiptDto = api.request(
        method=HttpMethod.Get,
        path=base(org)+"/cash-allocation-corrections/"+uuid(proposal),
        options=HiltechRequestOptions(installationId=installation),
        decode={json.decodeFromString(
            FinanceCashVoidReceiptDto.serializer(),it)},
    )
    private suspend fun command(
        org:String,proposal:String,operation:String,installation:String,
        action:String,
    ):FinanceCashVoidReceiptDto {
        require(action=="request-review" || action=="record")
        val key=uuid(operation)
        return api.request(
            method=HttpMethod.Post,
            path=base(org)+"/cash-allocation-corrections/"+uuid(proposal)+"/"+action,
            options=HiltechRequestOptions(
                installationId=installation,idempotencyKey=key),
            requestBody=json.encodeToString(
                FinanceCashVoidOperationDto.serializer(),
                FinanceCashVoidOperationDto(key)),
            decode={json.decodeFromString(
                FinanceCashVoidReceiptDto.serializer(),it)},
        )
    }
    private fun base(org:String)="/v1/finance/organizations/"+uuid(org)
    private fun uuid(value:String):String {
        require(Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        ).matches(value)){"Valid UUID required"}
        return value
    }
}

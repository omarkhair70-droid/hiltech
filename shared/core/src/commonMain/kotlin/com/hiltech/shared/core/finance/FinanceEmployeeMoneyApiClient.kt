package com.hiltech.shared.core.finance

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class FinanceEmployeeFundingPrepareDto(
    val operationId:String,
    val originalPolicyApprovalId:String,
    val bankStatementLineId:String,
)
@Serializable
data class FinanceEmployeeSettlementPrepareDto(
    val operationId:String,
    val action:String,
    val amount:String,
    val settlementEvidenceId:String,
    val settlementEvidenceSha256:String,
    val bankStatementLineId:String?=null,
)
@Serializable
data class FinanceEmployeeMoneyOperationDto(val operationId:String)
@Serializable
data class FinanceEmployeeFundingReceiptDto(
    val proposalId:String,val organizationId:String,
    val kind:String,val draftId:String,val employeeId:String,
    val amount:String,val currency:String,val state:String,
    val bankStatementLineId:String,val approvalRequestId:String?=null,
    val fundingRecordId:String?=null,
    val replayed:Boolean=false,
    val fundingEvidenceRecorded:Boolean=false,
    val bankTransferInitiated:Boolean=false,
    val personalLoanRecognized:Boolean=false,
    val cashCustodyRecognized:Boolean=false,
    val employeeSettled:Boolean=false,
)
@Serializable
data class FinanceEmployeeSettlementReceiptDto(
    val proposalId:String,val organizationId:String,
    val fundingRecordId:String,val employeeId:String,
    val action:String,val amount:String,val currency:String,
    val state:String,val approvalRequestId:String?=null,
    val settlementRecordId:String?=null,val replayed:Boolean=false,
    val settlementRecorded:Boolean=false,
    val bankTransferInitiated:Boolean=false,
    val payrollDeductionInitiated:Boolean=false,
)

/** Typed KMP transport, NOT a money transfer API or self-service approval. */
class FinanceEmployeeMoneyApiClient(
    private val api:HiltechApiClient,
    private val json:Json=Json {ignoreUnknownKeys=true;explicitNulls=false},
) {
    suspend fun prepareFunding(
        org:String,kind:String,draft:String,operation:String,
        policyApproval:String,bankLine:String,installation:String,
    ):FinanceEmployeeFundingReceiptDto {
        require(kind in setOf("EMPLOYEE_ADVANCE","FINANCIAL_IMPREST"))
        val id=uuid(operation)
        return api.request(
            method=HttpMethod.Post,
            path=base(org)+"/employee-funding/"+kind+"/"+uuid(draft)+"/prepare",
            options=options(installation,id),
            requestBody=json.encodeToString(
                FinanceEmployeeFundingPrepareDto.serializer(),
                FinanceEmployeeFundingPrepareDto(
                    id,uuid(policyApproval),uuid(bankLine))),
            decode={json.decodeFromString(
                FinanceEmployeeFundingReceiptDto.serializer(),it)},
        )
    }
    suspend fun requestFundingReview(
        org:String,proposal:String,operation:String,installation:String,
    ):FinanceEmployeeFundingReceiptDto =
        fundingCommand(org,proposal,operation,installation,"request-review")
    suspend fun recordFunding(
        org:String,proposal:String,operation:String,installation:String,
    ):FinanceEmployeeFundingReceiptDto =
        fundingCommand(org,proposal,operation,installation,"record")
    suspend fun fundingOperatorQueue(org:String,installation:String):List<FinanceEmployeeFundingReceiptDto> =
        api.request(
            method=HttpMethod.Get,
            path="/v1/finance/organizations/"+uuid(org)+"/employee-funding-proposals/operator-queue",
            options=HiltechRequestOptions(installationId=installation),
            decode={json.decodeFromString(ListSerializer(FinanceEmployeeFundingReceiptDto.serializer()),it)},
        )

    suspend fun fundingStatus(
        org:String,proposal:String,installation:String,
    ):FinanceEmployeeFundingReceiptDto = api.request(
        method=HttpMethod.Get,
        path=base(org)+"/employee-funding-proposals/"+uuid(proposal),
        options=HiltechRequestOptions(installationId=installation),
        decode={json.decodeFromString(
            FinanceEmployeeFundingReceiptDto.serializer(),it)},
    )
    suspend fun prepareSettlement(
        org:String,fundingId:String,body:FinanceEmployeeSettlementPrepareDto,
        installation:String,
    ):FinanceEmployeeSettlementReceiptDto {
        val id=uuid(body.operationId)
        require(body.action in setOf(
            "ADVANCE_RECOVERY_CONFIRMED",
            "IMPREST_CASH_RETURN_CONFIRMED","IMPREST_RECEIPTS_ACCEPTED",
        ))
        if(body.action=="IMPREST_RECEIPTS_ACCEPTED")
            require(body.bankStatementLineId==null)
        else require(body.bankStatementLineId!=null)
        body.bankStatementLineId?.let{uuid(it)}
        uuid(body.settlementEvidenceId)
        require(body.settlementEvidenceSha256.matches(Regex("^[a-f0-9]{64}$")))
        return api.request(
            method=HttpMethod.Post,
            path=base(org)+"/employee-funded/"+uuid(fundingId)+
                "/settlements/prepare",
            options=options(installation,id),
            requestBody=json.encodeToString(
                FinanceEmployeeSettlementPrepareDto.serializer(),body),
            decode={json.decodeFromString(
                FinanceEmployeeSettlementReceiptDto.serializer(),it)},
        )
    }
    suspend fun requestSettlementReview(
        org:String,proposal:String,operation:String,installation:String,
    ):FinanceEmployeeSettlementReceiptDto =
        settlementCommand(org,proposal,operation,installation,"request-review")
    suspend fun recordSettlement(
        org:String,proposal:String,operation:String,installation:String,
    ):FinanceEmployeeSettlementReceiptDto =
        settlementCommand(org,proposal,operation,installation,"record")
    suspend fun settlementOperatorQueue(org:String,installation:String):List<FinanceEmployeeSettlementReceiptDto> =
        api.request(
            method=HttpMethod.Get,
            path="/v1/finance/organizations/"+uuid(org)+"/employee-settlement-proposals/operator-queue",
            options=HiltechRequestOptions(installationId=installation),
            decode={json.decodeFromString(ListSerializer(FinanceEmployeeSettlementReceiptDto.serializer()),it)},
        )

    suspend fun settlementStatus(
        org:String,proposal:String,installation:String,
    ):FinanceEmployeeSettlementReceiptDto=api.request(
        method=HttpMethod.Get,
        path=base(org)+"/employee-settlement-proposals/"+uuid(proposal),
        options=HiltechRequestOptions(installationId=installation),
        decode={json.decodeFromString(
            FinanceEmployeeSettlementReceiptDto.serializer(),it)},
    )
    private suspend fun fundingCommand(
        org:String,proposal:String,operation:String,
        installation:String,action:String,
    ):FinanceEmployeeFundingReceiptDto {
        require(action=="request-review" || action=="record")
        val id=uuid(operation)
        return api.request(
            method=HttpMethod.Post,
            path=base(org)+"/employee-funding-proposals/"+uuid(proposal)+"/"+action,
            options=options(installation,id),
            requestBody=json.encodeToString(
                FinanceEmployeeMoneyOperationDto.serializer(),
                FinanceEmployeeMoneyOperationDto(id)),
            decode={json.decodeFromString(
                FinanceEmployeeFundingReceiptDto.serializer(),it)},
        )
    }
    private suspend fun settlementCommand(
        org:String,proposal:String,operation:String,
        installation:String,action:String,
    ):FinanceEmployeeSettlementReceiptDto {
        require(action=="request-review" || action=="record")
        val id=uuid(operation)
        return api.request(
            method=HttpMethod.Post,
            path=base(org)+"/employee-settlement-proposals/"+uuid(proposal)+"/"+action,
            options=options(installation,id),
            requestBody=json.encodeToString(
                FinanceEmployeeMoneyOperationDto.serializer(),
                FinanceEmployeeMoneyOperationDto(id)),
            decode={json.decodeFromString(
                FinanceEmployeeSettlementReceiptDto.serializer(),it)},
        )
    }
    private fun options(installation:String,id:String)=HiltechRequestOptions(
        installationId=installation,idempotencyKey=id,
    )
    private fun base(org:String)="/v1/finance/organizations/"+uuid(org)
    private fun uuid(id:String):String {
        require(Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        ).matches(id)){"Valid UUID required"}
        return id
    }
}

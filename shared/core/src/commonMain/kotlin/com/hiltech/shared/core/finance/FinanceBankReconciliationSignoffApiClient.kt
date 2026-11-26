package com.hiltech.shared.core.finance

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class FinanceBankReconciliationPrepareDto(
    val operationId:String,
    val bankStatementIntakeId:String,
    val companyReconciliationReference:String,
)
@Serializable
data class FinanceBankReconciliationOperationDto(val operationId:String)
@Serializable
data class FinanceBankReconciliationSignDto(
    val operationId:String, val signedEvidenceId:String,
)
@Serializable
data class FinanceBankReconciliationReceiptDto(
    val proposalId:String,val organizationId:String,val intakeId:String,
    val accountId:String,val statementSha256:String,val matchingSha256:String,
    val currency:String,val lineCount:Long,val totalCredit:String,
    val totalDebit:String,val totalMatched:String,val state:String,
    val approvalRequestId:String?=null,val reconciliationRecordId:String?=null,
    val replayed:Boolean=false,val internallyReconciled:Boolean=false,
    val verifiedByBankProvider:Boolean=false,
    val bankTransferInitiated:Boolean=false,
)

/** Native transport for company-side signing, never a bank transfer command. */
class FinanceBankReconciliationSignoffApiClient(
    private val api:HiltechApiClient,
    private val json:Json=Json{ignoreUnknownKeys=true;explicitNulls=false},
) {
    suspend fun prepare(
        org:String,request:FinanceBankReconciliationPrepareDto,
        installation:String,
    ):FinanceBankReconciliationReceiptDto {
        val op=uuid(request.operationId)
        uuid(request.bankStatementIntakeId)
        require(request.companyReconciliationReference.trim().length in 8..180)
        return api.request(
            method=HttpMethod.Post,
            path=base(org)+"/bank-reconciliations/prepare",
            options=command(installation,op),
            requestBody=json.encodeToString(
                FinanceBankReconciliationPrepareDto.serializer(),request,
            ),
            decode={json.decodeFromString(FinanceBankReconciliationReceiptDto.serializer(),it)},
        )
    }
    suspend fun requestReview(
        org:String,id:String,operation:String,installation:String,
    ):FinanceBankReconciliationReceiptDto=post(
        org,id,operation,null,installation,"request-review",
    )
    suspend fun sign(
        org:String,id:String,operation:String,evidence:String,installation:String,
    ):FinanceBankReconciliationReceiptDto=post(
        org,id,operation,uuid(evidence),installation,"sign",
    )
    suspend fun status(org:String,id:String,installation:String):
        FinanceBankReconciliationReceiptDto=api.request(
            method=HttpMethod.Get,
            path=base(org)+"/bank-reconciliations/"+uuid(id),
            options=HiltechRequestOptions(installationId=installation),
            decode={json.decodeFromString(FinanceBankReconciliationReceiptDto.serializer(),it)},
        )
   
 suspend fun operatorQueue(org:String,installation:String):List<FinanceBankReconciliationReceiptDto> =
    api.request(method=HttpMethod.Get,path=base(org)+"/bank-reconciliations/operator-queue",
      options=HiltechRequestOptions(installationId=installation),
      decode={json.decodeFromString(ListSerializer(FinanceBankReconciliationReceiptDto.serializer()),it)})
 suspend fun operatorStatus(org:String,id:String,installation:String):FinanceBankReconciliationReceiptDto =
    api.request(method=HttpMethod.Get,path=base(org)+"/bank-reconciliations/"+uuid(id)+"/operator-status",
      options=HiltechRequestOptions(installationId=installation),
      decode={json.decodeFromString(FinanceBankReconciliationReceiptDto.serializer(),it)})
 private suspend fun post(
        org:String,id:String,operation:String,evidence:String?,
        installation:String,action:String,
    ):FinanceBankReconciliationReceiptDto {
        require(action=="request-review" || action=="sign")
        val op=uuid(operation)
        val body=if(action=="sign")
            json.encodeToString(FinanceBankReconciliationSignDto.serializer(),
                FinanceBankReconciliationSignDto(op,requireNotNull(evidence)))
        else json.encodeToString(FinanceBankReconciliationOperationDto.serializer(),
                FinanceBankReconciliationOperationDto(op))
        return api.request(
            method=HttpMethod.Post,
            path=base(org)+"/bank-reconciliations/"+uuid(id)+"/"+action,
            options=command(installation,op),
            requestBody=body,
            decode={json.decodeFromString(FinanceBankReconciliationReceiptDto.serializer(),it)},
        )
    }
    private fun base(org:String)="/v1/finance/organizations/"+uuid(org)
    private fun command(installation:String,op:String)=HiltechRequestOptions(
        installationId=installation,idempotencyKey=op)
    private fun uuid(s:String):String {
        require(Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        ).matches(s)){"Valid UUID required"}
        return s
    }
}

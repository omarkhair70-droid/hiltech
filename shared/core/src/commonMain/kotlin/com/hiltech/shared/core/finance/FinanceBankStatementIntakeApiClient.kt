package com.hiltech.shared.core.finance

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class FinanceBankIntakeLineDto(
    val externalTransactionId:String,
    val bookedOn:String,
    val flow:String,
    val amount:String,
    val originalLineSha256:String,
    val currency:String?=null,
)
@Serializable
data class FinanceBankIntakePrepareDto(
    val operationId:String,
    val bankAccountId:String,
    val statementEvidenceId:String,
    val statementSha256:String,
    val periodStart:String,
    val periodEnd:String,
    val openingBalance:String,
    val closingBalance:String,
    val lines:List<FinanceBankIntakeLineDto>,
)
@Serializable
data class FinanceBankCsvIntakePrepareDto(
    val operationId:String,
    val bankAccountId:String,
    val statementEvidenceId:String,
    val statementSha256:String,
    val periodStart:String,
    val periodEnd:String,
    val openingBalance:String,
    val closingBalance:String,
    val csvUtf8Base64:String,
)
@Serializable
data class FinanceBankIntakeOperationDto(val operationId:String)
@Serializable
data class FinanceBankIntakeReceiptDto(
    val proposalId:String,
    val organizationId:String,
    val bankAccountId:String,
    val statementHash:String,
    val lineHash:String,
    val lineCount:Int,
    val state:String,
    val approvalRequestId:String?=null,
    val intakeId:String?=null,
    val replayed:Boolean=false,
    val internalBankStatementRecorded:Boolean=false,
    val bankTransactionInitiated:Boolean=false,
    val bankReconciled:Boolean=false,
)

/** Native transport for independent review of MANUALLY parsed bank sources. */
class FinanceBankStatementIntakeApiClient(
    private val api:HiltechApiClient,
    private val json:Json=Json { ignoreUnknownKeys=true; explicitNulls=false },
) {
    suspend fun prepare(
        org:String,request:FinanceBankIntakePrepareDto,installation:String,
    ):FinanceBankIntakeReceiptDto {
        require(request.lines.size in 1..10000)
        require(request.statementSha256.matches(Regex("^[a-f0-9]{64}$")))
        require(request.lines.all {
            it.flow in setOf("DEBIT","CREDIT") &&
                it.originalLineSha256.matches(Regex("^[a-f0-9]{64}$")) &&
                it.externalTransactionId.trim().length in 5..180
        })
        val operation=uuid(request.operationId)
        uuid(request.bankAccountId)
        uuid(request.statementEvidenceId)
        return api.request(
            method=HttpMethod.Post,
            path=base(org)+"/bank-statement-intakes/prepare",
            options=HiltechRequestOptions(
                installationId=installation,idempotencyKey=operation),
            requestBody=json.encodeToString(
                FinanceBankIntakePrepareDto.serializer(),request),
            decode={json.decodeFromString(
                FinanceBankIntakeReceiptDto.serializer(),it)},
        )
    }
    /** Canonical uploaded CSV only; server checks exact finalized Evidence SHA-256. */
    suspend fun prepareCsv(
        org:String,request:FinanceBankCsvIntakePrepareDto,installation:String,
    ):FinanceBankIntakeReceiptDto {
        require(request.csvUtf8Base64.isNotBlank() &&
            request.csvUtf8Base64.length<=((2*1024*1024+2)/3)*4+4)
        require(request.statementSha256.matches(Regex("^[a-f0-9]{64}$")))
        val id=uuid(request.operationId)
        uuid(request.bankAccountId)
        uuid(request.statementEvidenceId)
        return api.request(
            method=HttpMethod.Post,
            path=base(org)+"/bank-statement-intakes/prepare-csv",
            options=HiltechRequestOptions(
                installationId=installation,idempotencyKey=id),
            requestBody=json.encodeToString(
                FinanceBankCsvIntakePrepareDto.serializer(),request),
            decode={json.decodeFromString(
                FinanceBankIntakeReceiptDto.serializer(),it)},
        )
    }
    suspend fun requestReview(
        org:String,proposal:String,operation:String,installation:String,
    ):FinanceBankIntakeReceiptDto =
        command(org,proposal,operation,installation,"request-review")
    suspend fun record(
        org:String,proposal:String,operation:String,installation:String,
    ):FinanceBankIntakeReceiptDto =
        command(org,proposal,operation,installation,"record")
    suspend fun status(
        org:String,proposal:String,installation:String,
    ):FinanceBankIntakeReceiptDto=api.request(
        method=HttpMethod.Get,
        path=base(org)+"/bank-statement-intakes/"+uuid(proposal),
        options=HiltechRequestOptions(installationId=installation),
        decode={json.decodeFromString(
            FinanceBankIntakeReceiptDto.serializer(),it)},
    )
    private suspend fun command(
        org:String,proposal:String,operation:String,installation:String,
        action:String,
    ):FinanceBankIntakeReceiptDto {
        require(action=="request-review" || action=="record")
        val key=uuid(operation)
        return api.request(
            method=HttpMethod.Post,
            path=base(org)+"/bank-statement-intakes/"+uuid(proposal)+"/"+action,
            options=HiltechRequestOptions(
                installationId=installation,idempotencyKey=key),
            requestBody=json.encodeToString(
                FinanceBankIntakeOperationDto.serializer(),
                FinanceBankIntakeOperationDto(key)),
            decode={json.decodeFromString(
                FinanceBankIntakeReceiptDto.serializer(),it)},
        )
    }
    private fun base(org:String)="/v1/finance/organizations/"+uuid(org)
    private fun uuid(id:String):String {
        require(Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        ).matches(id)) { "Valid UUID required" }
        return id
    }
}

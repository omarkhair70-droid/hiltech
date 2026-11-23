package com.hiltech.shared.core.finance

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class FinanceGlPeriodCloseOperationDto(val operationId:String)

@Serializable
data class FinanceGlPeriodCloseReceiptDto(
    val organizationId:String,
    val periodId:String,
    val ledgerSnapshotSha256:String,
    val journalCount:Long,
    val sourceVersion:Long,
    val state:String,
    val approvalRequestId:String?=null,
    val periodClosureId:String?=null,
    val replayed:Boolean=false,
    val periodClosed:Boolean=false,
    val moneyTransferred:Boolean=false,
    val bankReconciled:Boolean=false,
)

/**
 * Native company-Finance period closure review and actual immutable
 * completion status. Never a bank operation or revenue assertion.
 */
class FinanceGlPeriodCloseApiClient(
    private val api:HiltechApiClient,
    private val json:Json=Json { ignoreUnknownKeys=true; explicitNulls=false },
) {
    suspend fun requestReview(
        organizationId:String,periodId:String,operationId:String,
        installationId:String,
    ):FinanceGlPeriodCloseReceiptDto = command(
        organizationId,periodId,operationId,installationId,"request-close-review",
    )

    suspend fun close(
        organizationId:String,periodId:String,operationId:String,
        installationId:String,
    ):FinanceGlPeriodCloseReceiptDto = command(
        organizationId,periodId,operationId,installationId,"close",
    )

    suspend fun status(
        organizationId:String,periodId:String,installationId:String,
    ):FinanceGlPeriodCloseReceiptDto =
        api.request(
            method=HttpMethod.Get,
            path=base(organizationId,periodId)+"/close",
            options=HiltechRequestOptions(installationId=installationId),
            decode={
                json.decodeFromString(
                    FinanceGlPeriodCloseReceiptDto.serializer(),it,
                )
            },
        )

    private suspend fun command(
        organizationId:String,periodId:String,operationId:String,
        installationId:String,action:String,
    ):FinanceGlPeriodCloseReceiptDto {
        require(action=="close" || action=="request-close-review")
        val operation=uuid(operationId)
        return api.request(
            method=HttpMethod.Post,
            path=base(organizationId,periodId)+"/"+action,
            options=HiltechRequestOptions(
                installationId=installationId,idempotencyKey=operation,
            ),
            requestBody=json.encodeToString(
                FinanceGlPeriodCloseOperationDto.serializer(),
                FinanceGlPeriodCloseOperationDto(operation),
            ),
            decode={
                json.decodeFromString(
                    FinanceGlPeriodCloseReceiptDto.serializer(),it,
                )
            },
        )
    }

    private fun base(org:String,period:String) =
        "/v1/finance/organizations/"+uuid(org)+"/periods/"+uuid(period)

    private fun uuid(value:String):String {
        require(Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        ).matches(value)) { "Valid UUID required." }
        return value
    }
}

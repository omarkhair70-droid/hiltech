package com.hiltech.shared.core.finance

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class FinanceExpenseReimbursementPrepareDto(
    val operationId:String,
    val documentationApprovalRequestId:String,
    val bankStatementLineId:String,
    val reimbursementAmount:String,
)
@Serializable
data class FinanceExpenseReimbursementOperationDto(val operationId:String)
@Serializable
data class FinanceExpenseReimbursementReceiptDto(
    val proposalId:String,val organizationId:String,
    val expenseDraftId:String,val employeeId:String,
    val bankStatementLineId:String,val amount:String,val currency:String,
    val state:String,val approvalRequestId:String?=null,
    val reimbursementRecordId:String?=null,val replayed:Boolean=false,
    val bankDebitAllocatedToEmployeeExpense:Boolean=false,
    val bankTransferInitiated:Boolean=false,
    val expenseRecognizedInGl:Boolean=false,
)

/** Shared native REST transport. No bank transfer or payroll call. */
class FinanceExpenseReimbursementApiClient(
    private val api:HiltechApiClient,
    private val json:Json=Json {ignoreUnknownKeys=true;explicitNulls=false},
) {
    suspend fun prepare(
        org:String,expense:String,body:FinanceExpenseReimbursementPrepareDto,
        installation:String,
    ):FinanceExpenseReimbursementReceiptDto{
        require(Regex("^[0-9]+(\\.[0-9]{1,6})?$")
          .matches(body.reimbursementAmount) &&
          body.reimbursementAmount.any {it in '1'..'9'})
        val op=uuid(body.operationId)
        uuid(body.documentationApprovalRequestId)
        uuid(body.bankStatementLineId)
        return api.request(
            method=HttpMethod.Post,
            path=base(org)+"/expenses/"+uuid(expense)+"/reimbursements/prepare",
            options=options(installation,op),
            requestBody=json.encodeToString(
                FinanceExpenseReimbursementPrepareDto.serializer(),body),
            decode={json.decodeFromString(
                FinanceExpenseReimbursementReceiptDto.serializer(),it)},
        )
    }
    suspend fun requestReview(
        org:String,proposal:String,operation:String,installation:String,
    ):FinanceExpenseReimbursementReceiptDto =
        command(org,proposal,operation,installation,"request-review")
    suspend fun record(
        org:String,proposal:String,operation:String,installation:String,
    ):FinanceExpenseReimbursementReceiptDto =
        command(org,proposal,operation,installation,"record")
    suspend fun status(
        org:String,proposal:String,installation:String,
    ):FinanceExpenseReimbursementReceiptDto=api.request(
        method=HttpMethod.Get,
        path=base(org)+"/expense-reimbursements/"+uuid(proposal),
        options=HiltechRequestOptions(installationId=installation),
        decode={json.decodeFromString(
            FinanceExpenseReimbursementReceiptDto.serializer(),it)},
    )
    private suspend fun command(
        org:String,proposal:String,operation:String,
        installation:String,action:String,
    ):FinanceExpenseReimbursementReceiptDto{
        require(action=="request-review" || action=="record")
        val op=uuid(operation)
        return api.request(
            method=HttpMethod.Post,
            path=base(org)+"/expense-reimbursements/"+uuid(proposal)+"/"+action,
            options=options(installation,op),
            requestBody=json.encodeToString(
                FinanceExpenseReimbursementOperationDto.serializer(),
                FinanceExpenseReimbursementOperationDto(op)),
            decode={json.decodeFromString(
                FinanceExpenseReimbursementReceiptDto.serializer(),it)},
        )
    }
    private fun options(inst:String,op:String)=HiltechRequestOptions(
        installationId=inst,idempotencyKey=op,
    )
    private fun base(org:String)="/v1/finance/organizations/"+uuid(org)
    private fun uuid(id:String):String{
        require(Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        ).matches(id)){"Valid UUID required"}
        return id
    }
}

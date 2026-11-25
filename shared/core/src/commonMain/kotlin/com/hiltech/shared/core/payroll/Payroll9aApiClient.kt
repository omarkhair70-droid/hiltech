package com.hiltech.shared.core.payroll

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.Json

/**
 * Restricted payroll reviewer API for Android/Windows.
 * The server is the only authority. Do not persist payroll records in offline
 * general-purpose caches or surface monetary amounts in client telemetry.
 */
class Payroll9aApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {
    suspend fun capabilities(organizationId: String, installationId: String): PayrollCapabilitiesDto {
        requireUuid(organizationId)
        return api.request(HttpMethod.Get, "/v1/payroll/capabilities?organizationId=$organizationId",
            options = HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(PayrollCapabilitiesDto.serializer(), it) })
    }

    suspend fun sources(periodId: String, installationId: String): PayrollSourcesDto {
        requireUuid(periodId)
        return api.request(HttpMethod.Get, "/v1/payroll/periods/$periodId/sources",
            options = HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(PayrollSourcesDto.serializer(), it).also { result ->
                require(result.periodId == periodId && result.records.all { record -> record.periodId == periodId })
            } })
    }

    suspend fun saveSource(periodId: String, request: PayrollSourceRequestDto, installationId: String): PayrollSourceCommandDto {
        requireUuid(periodId)
        requireUuid(request.operationId)
        require(validPayrollSource(request))
        return api.request(HttpMethod.Post, "/v1/payroll/periods/$periodId/sources",
            options = HiltechRequestOptions(installationId = installationId, idempotencyKey = request.operationId),
            requestBody = json.encodeToString(PayrollSourceRequestDto.serializer(), request),
            decode = { json.decodeFromString(PayrollSourceCommandDto.serializer(), it).also { result ->
                require(payrollSourceReceiptMatches(periodId, request, result.record))
            } })
    }

    suspend fun requestSourceApproval(recordId: String, operationId: String, installationId: String): PayrollSourceCommandDto {
        requireUuid(recordId)
        requireUuid(operationId)
        return api.request(HttpMethod.Post, "/v1/payroll/sources/$recordId/approval-requests",
            options = HiltechRequestOptions(installationId = installationId, idempotencyKey = operationId),
            requestBody = json.encodeToString(PayrollSourceApprovalRequestDto.serializer(), PayrollSourceApprovalRequestDto(operationId)),
            decode = { json.decodeFromString(PayrollSourceCommandDto.serializer(), it).also { result ->
                require(result.record.recordId == recordId && result.record.approvalRequestId != null)
            } })
    }

    suspend fun decideSource(recordId: String, request: PayrollSourceDecisionRequestDto, installationId: String): PayrollSourceCommandDto {
        requireUuid(recordId)
        requireUuid(request.operationId)
        return api.request(HttpMethod.Post, "/v1/payroll/sources/$recordId/decisions",
            options = HiltechRequestOptions(installationId = installationId, idempotencyKey = request.operationId),
            requestBody = json.encodeToString(PayrollSourceDecisionRequestDto.serializer(), request),
            decode = { json.decodeFromString(PayrollSourceCommandDto.serializer(), it).also { result ->
                val expected = when (request.decision) { "APPROVE" -> "APPROVED"; "REJECT" -> "REJECTED"; else -> "CHANGE_REQUESTED" }
                require(result.record.recordId == recordId && result.record.approvalState == expected)
            } })
    }

    suspend fun list(
        organizationId: String,
        installationId: String,
    ): PayrollPeriodListDto {
        requireUuid(organizationId)
        return api.request(
            method = HttpMethod.Get,
            path = "/v1/payroll/periods?organizationId=" + organizationId,
            options = HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(PayrollPeriodListDto.serializer(), it) },
        )
    }

    suspend fun detail(
        periodId: String,
        installationId: String,
    ): PayrollPeriodDetailDto {
        requireUuid(periodId)
        return api.request(
            method = HttpMethod.Get,
            path = "/v1/payroll/periods/" + periodId,
            options = HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(PayrollPeriodDetailDto.serializer(), it) },
        )
    }

    suspend fun create(
        request: CreatePayrollPeriodRequestDto,
        installationId: String,
    ): PayrollPeriodCommandDto {
        requireUuid(request.organizationId)
        requireUuid(request.operationId)
        return api.request(
            method = HttpMethod.Post,
            path = "/v1/payroll/periods",
            options = HiltechRequestOptions(
                installationId = installationId,
                idempotencyKey = request.operationId,
            ),
            requestBody = json.encodeToString(CreatePayrollPeriodRequestDto.serializer(), request),
            decode = { json.decodeFromString(PayrollPeriodCommandDto.serializer(), it) },
        )
    }

    suspend fun captureInputs(
        periodId: String,
        installationId: String,
    ): PayrollPeriodCommandDto {
        requireUuid(periodId)
        return api.request(
            method = HttpMethod.Post,
            path = "/v1/payroll/periods/" + periodId + "/capture-inputs",
            options = HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(PayrollPeriodCommandDto.serializer(), it) },
        )
    }

    suspend fun reviewException(
        periodId: String,
        exceptionId: String,
        request: ReviewPayrollExceptionRequestDto,
        installationId: String,
    ): PayrollExceptionReviewCommandDto {
        requireUuid(periodId)
        requireUuid(exceptionId)
        requireUuid(request.operationId)
        return api.request(
            method = HttpMethod.Post,
            path = "/v1/payroll/periods/" + periodId +
                "/exceptions/" + exceptionId + "/reviews",
            options = HiltechRequestOptions(
                installationId = installationId,
                idempotencyKey = request.operationId,
            ),
            requestBody = json.encodeToString(ReviewPayrollExceptionRequestDto.serializer(), request),
            decode = { json.decodeFromString(PayrollExceptionReviewCommandDto.serializer(), it) },
        )
    }

    private fun requireUuid(value: String) {
        require(value.length == 36 && value.all {
            it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == '-'
        }) {
            "Invalid payroll identifier"
        }
    }
}

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

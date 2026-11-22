package com.hiltech.shared.core.payroll

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.Json

/**
 * Native online only. Salaries and results are never saved to normal
 * offline field caches, general directory state or telemetry.
 */
class Payroll9b9cApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {
    suspend fun createDraft(
        periodId: String,
        operationId: String,
        installationId: String,
    ): PayrollRunDraftCommandDto =
        api.request(
            method = HttpMethod.Post,
            path = "/v1/payroll/periods/" + periodId + "/runs/drafts",
            options = HiltechRequestOptions(
                installationId = installationId,
                idempotencyKey = operationId,
            ),
            requestBody = json.encodeToString(
                CreatePayrollDraftRequestDto.serializer(),
                CreatePayrollDraftRequestDto(operationId),
            ),
            decode = { json.decodeFromString(PayrollRunDraftCommandDto.serializer(), it) },
        )

    suspend fun readDraft(
        runVersionId: String,
        installationId: String,
    ): PayrollRunDto =
        api.request(
            method = HttpMethod.Get,
            path = "/v1/payroll/runs/" + runVersionId,
            options = HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(PayrollRunDto.serializer(), it) },
        )

    suspend fun ownPayslips(
        organizationId: String,
        installationId: String,
    ): OwnPayslipsDto =
        api.request(
            method = HttpMethod.Get,
            path = "/v1/me/payroll/payslips?organizationId=" + organizationId,
            options = HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(OwnPayslipsDto.serializer(), it) },
        )
}

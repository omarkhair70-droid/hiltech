package com.hiltech.shared.core.procurement

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class ServicePurchaseTeamDto(
    val id: String, val organizationId: String, val name: String, val version: Long,
    val canRequest: Boolean, val canReview: Boolean,
)

@Serializable
data class ServicePurchaseNeedDto(
    val id: String, val organizationId: String, val teamId: String, val teamName: String,
    val teamVersion: Long, val specification: String, val acceptanceCriteria: String,
    val justification: String, val requestedBy: String,
    val state: String, val version: Long, val lastReviewReason: String? = null,
    val sourceCurrent: Boolean, val canSubmit: Boolean, val canRevise: Boolean,
    val canWithdraw: Boolean, val canTechnicalReview: Boolean,
    val supplierContacted: Boolean = false, val serviceOrdered: Boolean = false,
    val serviceAccepted: Boolean = false, val invoiceMatched: Boolean = false,
    val payableCreated: Boolean = false,
)
@Serializable
data class ServicePurchaseNeedReceiptDto(val need: ServicePurchaseNeedDto, val replayed: Boolean)

@Serializable
data class CreateServicePurchaseNeedDto(
    val operationId: String, val teamVersion: Long, val specification: String,
    val acceptanceCriteria: String, val justification: String,
)
@Serializable
data class ReviseServicePurchaseNeedDto(
    val operationId: String, val expectedVersion: Long, val specification: String,
    val acceptanceCriteria: String, val justification: String,
)
@Serializable
data class ServicePurchaseNeedActionDto(
    val operationId: String, val expectedVersion: Long, val reason: String? = null,
)
@Serializable
data class ServicePurchaseNeedReviewDto(
    val operationId: String, val expectedVersion: Long,
    val decision: String, val reason: String? = null,
)

/** Typed authenticated online access to non-stock internal service requests.
 * Neither this API nor its client may issue an order or create a payable. */
class ServicePurchaseNeedApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    suspend fun teams(org: String, install: String): List<ServicePurchaseTeamDto> =
        get("/v1/service-procurement/teams?organizationId=" + uuid(org),install,
            ListSerializer(ServicePurchaseTeamDto.serializer()))

    suspend fun list(team: String, install: String): List<ServicePurchaseNeedDto> =
        get("/v1/service-procurement/teams/" + uuid(team) + "/needs",install,
            ListSerializer(ServicePurchaseNeedDto.serializer()))

    suspend fun detail(id: String, install: String): ServicePurchaseNeedDto =
        get("/v1/service-procurement/needs/" + uuid(id),install,
            ServicePurchaseNeedDto.serializer())

    suspend fun create(team: String, req: CreateServicePurchaseNeedDto,
                       install: String): ServicePurchaseNeedReceiptDto =
        post("/v1/service-procurement/teams/" + uuid(team) + "/needs",
            req.operationId, json.encodeToString(CreateServicePurchaseNeedDto.serializer(),req),
            install,ServicePurchaseNeedReceiptDto.serializer())

    suspend fun submit(id: String, req: ServicePurchaseNeedActionDto,
                       install: String): ServicePurchaseNeedReceiptDto =
        post("/v1/service-procurement/needs/" + uuid(id) + "/submit",
            req.operationId,json.encodeToString(ServicePurchaseNeedActionDto.serializer(),req),
            install,ServicePurchaseNeedReceiptDto.serializer())

    suspend fun withdraw(id: String, req: ServicePurchaseNeedActionDto,
                         install: String): ServicePurchaseNeedReceiptDto =
        post("/v1/service-procurement/needs/" + uuid(id) + "/withdraw",
            req.operationId,json.encodeToString(ServicePurchaseNeedActionDto.serializer(),req),
            install,ServicePurchaseNeedReceiptDto.serializer())

    suspend fun revise(id: String, req: ReviseServicePurchaseNeedDto,
                       install: String): ServicePurchaseNeedReceiptDto =
        post("/v1/service-procurement/needs/" + uuid(id) + "/revise",
            req.operationId,json.encodeToString(ReviseServicePurchaseNeedDto.serializer(),req),
            install,ServicePurchaseNeedReceiptDto.serializer())

    suspend fun review(id: String, req: ServicePurchaseNeedReviewDto,
                       install: String): ServicePurchaseNeedReceiptDto =
        post("/v1/service-procurement/needs/" + uuid(id) + "/technical-review",
            req.operationId,json.encodeToString(ServicePurchaseNeedReviewDto.serializer(),req),
            install,ServicePurchaseNeedReceiptDto.serializer())

    private suspend fun <T> get(path: String, install: String,
                                serializer: KSerializer<T>): T = api.request(
        method = HttpMethod.Get,path = path,
        options = HiltechRequestOptions(installationId = install),
        decode = { json.decodeFromString(serializer,it) },
    )

    private suspend fun <T> post(path: String, op: String, body: String,
                                 install: String, serializer: KSerializer<T>): T = api.request(
        method = HttpMethod.Post,path = path,
        options = HiltechRequestOptions(installationId = install,idempotencyKey = uuid(op)),
        requestBody = body, decode = { json.decodeFromString(serializer,it) },
    )

    private fun uuid(value: String): String {
        require(Regex("^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$").matches(value))
        return value
    }
}

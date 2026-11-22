package com.hiltech.shared.core.work

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.warehouse.AssetTagLookupDto
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class FieldAssignedWorkApiClient(
    private val api: HiltechApiClient,
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
) {
    suspend fun today(
        installationId: String,
    ): List<FieldTodayItemDto> =
        api.request(
            HttpMethod.Get,
            "/v1/field/today",
            HiltechRequestOptions(
                installationId =
                    installationId,
            ),
            decode = {
                json.decodeFromString(
                    ListSerializer(
                        FieldTodayItemDto.serializer(),
                    ),
                    it,
                )
            },
        )

    suspend fun jobBundle(
        workOrderId: String,
        installationId: String,
    ): TechnicianJobBundleDto {
        require(
            UUID.matches(
                workOrderId.trim(),
            ),
        ) {
            "WorkOrder id must use canonical UUID format."
        }
        return api.request(
            HttpMethod.Get,
            "/v1/work-orders/" +
                workOrderId.trim() +
                "/job-bundle",
            HiltechRequestOptions(
                installationId =
                    installationId,
            ),
            decode = {
                json.decodeFromString(
                    TechnicianJobBundleDto.serializer(),
                    it,
                )
            },
        )
    }

    /** Live only: selected assigned WorkOrder narrows the canonical AssetTag lookup. */
    suspend fun assignedAssetTag(
        workOrderId: String,
        opaqueCode: String,
        installationId: String,
    ): AssetTagLookupDto {
        require(UUID.matches(workOrderId.trim())) {
            "WorkOrder id must use canonical UUID format."
        }
        val tag = opaqueCode.trim()
        require(Regex("[A-Za-z0-9_-]{1,128}").matches(tag)) {
            "Asset tag must be an opaque code."
        }
        return api.request(
            HttpMethod.Get,
            "/v1/field/work-orders/" + workOrderId.trim() + "/assets/by-tag/" + tag,
            HiltechRequestOptions(installationId = installationId),
            decode = {
                json.decodeFromString(
                    AssetTagLookupDto.serializer(),
                    it,
                )
            },
        )
    }

    /** No offline fallback or persisted personal stock: source auth is checked on every GET. */
    suspend fun personalIssuedStock(
        workOrderId: String,
        installationId: String,
    ): FieldPersonalIssuedStockSnapshotDto {
        require(UUID.matches(workOrderId.trim())) {
            "WorkOrder id must use canonical UUID format."
        }
        return api.request(
            HttpMethod.Get,
            "/v1/work-orders/" + workOrderId.trim() + "/my-issued-stock",
            HiltechRequestOptions(installationId = installationId),
            decode = {
                json.decodeFromString(
                    FieldPersonalIssuedStockSnapshotDto.serializer(),
                    it,
                )
            },
        )
    }

    private companion object {
        val UUID =
            Regex(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}",
            )
    }
}

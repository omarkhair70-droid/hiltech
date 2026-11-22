package com.hiltech.server.warehouse

import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.work.FieldAssignedAssetAvailabilityView
import com.hiltech.server.work.FieldAssignedAssetLookupView
import com.hiltech.server.work.FieldAssignedAssetSourcePort
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class WarehouseFieldAssignedAssetSource(
    private val service: AssetPassportService,
    private val assets: AssetPassportPersistence,
) : FieldAssignedAssetSourcePort {
    override fun lookupForWork(
        actor: UUID,
        opaqueCode: String,
        organizationId: UUID,
        workOrderId: UUID,
    ): FieldAssignedAssetLookupView? {
        val result =
            try {
                service.lookup(
                    actor,
                    opaqueCode,
                )
            } catch (_: ProductApiException) {
                return null
            }

        val identity =
            assets.asset(
                result.assetId,
            ) ?: return null
        val custody =
            assets.custody(
                result.assetId,
            ) ?: return null

        if (
            identity.organizationId !=
            organizationId ||
            custody.workOrderId !=
            workOrderId
        ) {
            return null
        }

        if (
            assets.activeTagAsset(
                opaqueCode,
            ) != identity.id ||
            assets.custody(
                identity.id,
            )?.sourceMovementId !=
            custody.sourceMovementId
        ) {
            return null
        }

        return FieldAssignedAssetLookupView(
            assetCode =
                result.assetCode,
            name = result.name,
            typeName =
                result.typeName,
            manufacturer =
                result.manufacturer,
            model = result.model,
            serialNumber =
                result.serialNumber,
            condition =
                result.condition.name,
            locationCode = null,
            employeeCode = null,
            custodySince = null,
            availability =
                result.availability?.let {
                    FieldAssignedAssetAvailabilityView(
                        state = it.state,
                        reasonCodes =
                            it.reasonCodes,
                        calibrationStatus =
                            it.calibrationStatus,
                        calibrationDueAt =
                            it.calibrationDueAt,
                        certificateOnFile =
                            it.certificateOnFile,
                        incidentTypes =
                            it.incidentTypes,
                    )
                },
            assetId =
                result.assetId,
        )
    }
}

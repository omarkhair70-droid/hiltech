package com.hiltech.server.warehouse

import com.hiltech.server.work.FieldEngineeringInstrumentSourcePort
import com.hiltech.server.work.FieldEngineeringInstrumentState
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
class WarehouseFieldEngineeringInstrumentSource(
    private val assets: AssetPassportPersistence,
    private val service: AssetServicePersistence,
) : FieldEngineeringInstrumentSourcePort {
    override fun state(
        assetId: UUID,
        at: Instant,
    ): FieldEngineeringInstrumentState? {
        val asset = assets.asset(assetId) ?: return null
        val availability = service.availability(assetId, at)
        return FieldEngineeringInstrumentState(
            organizationId = asset.organizationId,
            calibrationStatus = availability?.calibrationStatus,
        )
    }
}

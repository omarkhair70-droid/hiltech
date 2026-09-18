package com.hiltech.spike.warehouse

import org.springframework.stereotype.Service

@Service
class WarehouseService {
    fun assetLabel(assetId: String): String = "Asset " + assetId
}

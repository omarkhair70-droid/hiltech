package com.hiltech.spike.shared.local

import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "offline_bundle")
data class OfflineBundleEntity(
    @PrimaryKey
    val workOrderId: String,
    val projectId: String,
    val siteId: String,
    val assignee: String?,
    val state: String,
    val serverVersion: Long,
    val assetId: String,
    val evidenceRequired: Boolean,
    val fetchedAtEpochMs: Long,
)

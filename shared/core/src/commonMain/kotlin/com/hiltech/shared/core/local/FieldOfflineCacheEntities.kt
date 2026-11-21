package com.hiltech.shared.core.local

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "field_today_cache")
data class FieldTodayCacheEntity(
    @PrimaryKey
    @ColumnInfo(name = "identity_id")
    val identityId: String,
    @ColumnInfo(name = "payload_json")
    val payloadJson: String,
    @ColumnInfo(name = "fetched_at_epoch_ms")
    val fetchedAtEpochMs: Long,
)

@Entity(tableName = "field_job_bundle_cache", primaryKeys = ["identity_id", "work_order_id"])
data class FieldJobBundleCacheEntity(
    @ColumnInfo(name = "identity_id")
    val identityId: String,
    @ColumnInfo(name = "work_order_id")
    val workOrderId: String,
    @ColumnInfo(name = "payload_json")
    val payloadJson: String,
    @ColumnInfo(name = "fetched_at_epoch_ms")
    val fetchedAtEpochMs: Long,
    @ColumnInfo(name = "server_as_of")
    val serverAsOf: String,
    @ColumnInfo(name = "work_order_version")
    val workOrderVersion: Long,
    @ColumnInfo(name = "assignment_id")
    val assignmentId: String,
    @ColumnInfo(name = "assignment_version")
    val assignmentVersion: Long,
    @ColumnInfo(name = "binding_revision")
    val bindingRevision: Int?,
    @ColumnInfo(name = "instruction_revision")
    val instructionRevision: Int?,
)

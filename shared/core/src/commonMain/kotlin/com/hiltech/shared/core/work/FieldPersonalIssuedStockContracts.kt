package com.hiltech.shared.core.work

import kotlinx.serialization.Serializable

/** Live source, no Room persistence or offline fallback for personal custody. */
@Serializable
data class FieldPersonalIssuedStockSnapshotDto(
    val workOrderId: String,
    val workOrderVersion: Long,
    val status: String,
    val asOf: String,
    val items: List<FieldPersonalIssuedStockLineDto> = emptyList(),
)

@Serializable
data class FieldPersonalIssuedStockLineDto(
    val requirementId: String,
    val stockItemId: String,
    val stockItemCode: String,
    val stockItemName: String,
    val unitCode: String,
    val outstandingQuantity: String,
    val sourceVersion: Long,
    val sourceUpdatedAt: String,
)

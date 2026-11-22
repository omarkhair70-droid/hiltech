package com.hiltech.shared.core.procurement

import kotlinx.serialization.Serializable

/** Phase 7 S01 — source-bound, online-only internal demand; NOT purchase authority. */
@Serializable
data class PurchaseRequirementDraftDto(
    val id: String,
    val workOrderId: String,
    val workOrderVersionAtCreation: Long,
    val stockItemId: String,
    val stockItemVersionAtCreation: Long,
    val requestedQuantity: String,
    val unitCode: String,
    val justification: String,
    val state: String,
    val assessmentCode: String,
    val observedFreeQuantity: String? = null,
    val assessedAt: String,
    val createdBy: String,
    val createdAt: String,
    val sourceCurrent: Boolean,
    val assessmentRequiresRefresh: Boolean = true,
    val version: Long = 1,
    val canWithdraw: Boolean = false,
)

@Serializable
data class PurchaseRequirementDraftReceiptDto(
    val draft: PurchaseRequirementDraftDto,
    val replayed: Boolean,
)

@Serializable
data class CreatePurchaseRequirementDraftDto(
    val operationId: String,
    val baseWorkOrderVersion: Long,
    val stockItemId: String,
    val stockItemVersion: Long,
    val quantity: String,
    val unitCode: String,
    val justification: String,
)

@Serializable
data class WithdrawPurchaseRequirementDraftDto(
    val operationId: String,
    val expectedVersion: Long,
    val reason: String? = null,
)

/**
 * Live, read-only advisory. Duplicates are limited to the same WorkOrder and
 * StockItem; no price, vendor, authority or purchase order is inferred.
 */
@Serializable
data class PurchaseRequirementReviewPreviewDto(
    val requirementId: String,
    val state: String,
    val currentWorkOrderVersion: Long,
    val currentStockItemVersion: Long? = null,
    val sourceCurrent: Boolean,
    val assessmentCode: String,
    val visibleFreeQuantity: String? = null,
    val assessedAt: String,
    val otherOpenDraftIds: List<String> = emptyList(),
    val moreOpenDrafts: Boolean = false,
    val approvalOrPurchaseAuthorized: Boolean = false,
)

/** A visible stock snapshot never promises reservation or purchasing approval. */
fun PurchaseRequirementDraftDto.requiresWarehouseReview(): Boolean =
    assessmentCode == "WAREHOUSE_REVIEW_REQUIRED"

/** Readiness hint only, never proof the viewing actor can modify this demand. */
fun PurchaseRequirementDraftDto.hasCurrentDraftSource(): Boolean =
    state == "DRAFT" && sourceCurrent

package com.hiltech.shared.core.local

object OfflineSchemaContract {
    const val ROOM_SCHEMA_VERSION = 1
    const val MIN_DIRECT_MIGRATABLE_ROOM_SCHEMA_VERSION = 1
    const val CURRENT_COMMAND_CONTRACT_VERSION = 1
    const val CURRENT_PENDING_PAYLOAD_VERSION = 1

    val supportedPendingPayloadVersions: Set<Int> = setOf(CURRENT_PENDING_PAYLOAD_VERSION)
}

object PendingCommandStates {
    const val PENDING = "PENDING"
    const val RETRYABLE = "RETRYABLE"
    const val SYNCING = "SYNCING"
    const val APPLIED = "APPLIED"
    const val CONFLICT = "CONFLICT"
    const val BLOCKED_BY_CONFLICT = "BLOCKED_BY_CONFLICT"
    const val FAILED_TERMINAL = "FAILED_TERMINAL"
    const val CANCELLED_LOCAL = "CANCELLED_LOCAL"
}

object LocalEvidenceStates {
    const val LOCAL_READY = "LOCAL_READY"
    const val RESERVATION_REQUIRED = "RESERVATION_REQUIRED"
    const val RESERVED = "RESERVED"
    const val UPLOADING = "UPLOADING"
    const val UPLOADED_UNVERIFIED = "UPLOADED_UNVERIFIED"
    const val READY = "READY"
    const val RETRYABLE = "RETRYABLE"
    const val REJECTED = "REJECTED"
    const val DISCARDED_EXPLICITLY = "DISCARDED_EXPLICITLY"
}

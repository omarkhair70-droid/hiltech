package com.hiltech.shared.core.procurement

/**
 * Native, locally selected source document. Never carry a file-system path
 * or untrusted remote URL across the shared UI/API boundary.
 */
data class ServiceSelectedFile(
    val contentType: String,
    val originalFileName: String,
    val bytes: ByteArray,
    val sha256: String,
    val capturedAt: String,
)

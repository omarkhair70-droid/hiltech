package com.hiltech.server.warehouse

import com.hiltech.server.platform.IdempotencyKeyContract

internal fun warehouseCommandFingerprint(vararg fields: Any?): String =
    IdempotencyKeyContract.fingerprint(
        buildString {
            for (field in fields) {
                val text = field?.toString()
                append(text?.length ?: -1)
                append(':')
                if (text != null) append(text)
            }
        },
    )

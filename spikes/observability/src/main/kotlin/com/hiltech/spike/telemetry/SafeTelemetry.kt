package com.hiltech.spike.telemetry

import io.opentelemetry.api.common.Attributes

object SafeTelemetry {
    private val allowedKeys = setOf(
        "hiltech.correlation_id",
        "hiltech.operation_id",
        "hiltech.object_type",
        "hiltech.command_type",
        "hiltech.error_code",
        "hiltech.module",
    )

    fun attributes(
        raw: Map<String, String>,
    ): Attributes {
        val builder = Attributes.builder()

        raw.forEach { (key, value) ->
            if (key in allowedKeys) {
                builder.put(key, value.take(160))
            }
        }

        return builder.build()
    }
}

data class ClientDiagnostic(
    val eventName: String,
    val attributes: Map<String, String>,
)

class ClientDiagnostics {
    private val events = mutableListOf<ClientDiagnostic>()

    fun recordSyncFailure(
        correlationId: String,
        operationId: String,
        errorCode: String,
        rawContext: Map<String, String>,
    ) {
        val safe = buildMap {
            put("hiltech.correlation_id", correlationId)
            put("hiltech.operation_id", operationId)
            put("hiltech.error_code", errorCode)
            putAll(
                rawContext.filterKeys {
                    it == "hiltech.object_type" ||
                        it == "hiltech.command_type"
                },
            )
        }

        events += ClientDiagnostic(
            eventName = "sync.failure",
            attributes = safe,
        )
    }

    fun snapshot(): List<ClientDiagnostic> = events.toList()
}

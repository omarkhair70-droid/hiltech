package com.hiltech.server.telemetry

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TelemetryFoundationContractTest {
    private lateinit var exporter: InMemorySpanExporter
    private lateinit var provider: SdkTracerProvider
    private lateinit var openTelemetry: OpenTelemetry

    @BeforeEach
    fun setUp() {
        exporter = InMemorySpanExporter.create()
        provider = SdkTracerProvider.builder()
            .addSpanProcessor(
                SimpleSpanProcessor.create(exporter),
            )
            .build()

        openTelemetry = OpenTelemetrySdk.builder()
            .setTracerProvider(provider)
            .setPropagators(
                ContextPropagators.create(
                    W3CTraceContextPropagator.getInstance(),
                ),
            )
            .build()
    }

    @AfterEach
    fun tearDown() {
        provider.close()
        exporter.reset()
    }

    @Test
    fun w3cTraceContextSurvivesClientToServerBoundary() {
        val tracer = openTelemetry.getTracer("hiltech-test")
        val carrier = mutableMapOf<String, String>()

        val client = tracer.spanBuilder("client.sync")
            .setSpanKind(SpanKind.CLIENT)
            .setAllAttributes(
                SafeTelemetry.attributes(
                    mapOf(
                        "hiltech.correlation_id" to "corr-42",
                        "hiltech.operation_id" to "op-42",
                        "authorization" to "VALUE_THAT_MUST_NOT_EXPORT",
                    ),
                ),
            )
            .startSpan()

        openTelemetry.propagators.textMapPropagator.inject(
            Context.root().with(client),
            carrier,
        ) { map, key, value ->
            map?.set(key, value)
        }
        client.end()

        assertTrue(carrier.containsKey("traceparent"))

        val parent =
            openTelemetry.propagators.textMapPropagator.extract(
                Context.root(),
                carrier,
                MapGetter,
            )

        val server = tracer.spanBuilder("api.command")
            .setSpanKind(SpanKind.SERVER)
            .setParent(parent)
            .setAllAttributes(
                SafeTelemetry.attributes(
                    mapOf(
                        "hiltech.correlation_id" to "corr-42",
                        "hiltech.module" to "work",
                        "payload" to "VALUE_THAT_MUST_NOT_EXPORT",
                    ),
                ),
            )
            .startSpan()
        server.end()

        provider.forceFlush().join(
            5,
            java.util.concurrent.TimeUnit.SECONDS,
        )

        val spans = exporter.finishedSpanItems
        assertEquals(2, spans.size)

        val clientData = spans.single {
            it.name == "client.sync"
        }
        val serverData = spans.single {
            it.name == "api.command"
        }

        assertEquals(clientData.traceId, serverData.traceId)
        assertEquals(clientData.spanId, serverData.parentSpanId)

        val flattened = spans
            .flatMap { span ->
                span.attributes.asMap().flatMap { (key, value) ->
                    listOf(key.key, value.toString())
                }
            }
            .joinToString("|")

        assertTrue(flattened.contains("corr-42"))
        assertFalse(
            flattened.contains("VALUE_THAT_MUST_NOT_EXPORT"),
        )
        assertTrue(
            spans.all { span ->
                span.attributes.asMap().keys.all {
                    it.key.startsWith("hiltech.")
                }
            },
        )
    }

    @Test
    fun safeTelemetryCapsValuesAndDropsUnknownKeys() {
        val attributes = SafeTelemetry.attributes(
            mapOf(
                "hiltech.correlation_id" to "c".repeat(300),
                "hiltech.module" to "work",
                "hiltech.result_code" to "ACCEPTED",
                "payload" to "VALUE_THAT_MUST_NOT_EXPORT",
                "unknown.private" to "VALUE_THAT_MUST_NOT_EXPORT",
            ),
        )

        val map = attributes.asMap()
        assertEquals(3, map.size)

        val correlation = map.entries
            .single {
                it.key.key == "hiltech.correlation_id"
            }
            .value
            .toString()

        assertEquals(160, correlation.length)
        assertFalse(
            map.toString().contains(
                "VALUE_THAT_MUST_NOT_EXPORT",
            ),
        )
    }

    @Test
    fun disabledObservabilityRequiresNoCollectorConfiguration() {
        HiltechObservabilityProperties(
            enabled = false,
            otlpEndpoint = "",
        ).validateEnabledConfiguration()
    }

    private object MapGetter :
        TextMapGetter<Map<String, String>> {
        override fun keys(
            carrier: Map<String, String>,
        ): Iterable<String> =
            carrier.keys

        override fun get(
            carrier: Map<String, String>?,
            key: String,
        ): String? =
            carrier?.get(key)
    }
}

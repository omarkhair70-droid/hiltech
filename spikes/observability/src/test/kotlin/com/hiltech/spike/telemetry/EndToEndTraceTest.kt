package com.hiltech.spike.telemetry

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EndToEndTraceTest {
    private lateinit var exporter: InMemorySpanExporter
    private lateinit var provider: SdkTracerProvider
    private lateinit var otel: OpenTelemetry

    @BeforeEach
    fun setup() {
        exporter = InMemorySpanExporter.create()
        provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build()

        otel = OpenTelemetrySdk.builder()
            .setTracerProvider(provider)
            .setPropagators(
                ContextPropagators.create(
                    W3CTraceContextPropagator.getInstance(),
                ),
            )
            .build()
    }

    @AfterEach
    fun close() {
        provider.close()
        exporter.reset()
    }

    @Test
    fun one_failed_workflow_is_traceable_end_to_end_without_sensitive_data() {
        val tracer = otel.getTracer("hiltech-spike")
        val correlationId = "corr-wo42-001"
        val operationId = "op-complete-001"

        val sensitiveRawContext = mapOf(
            "hiltech.correlation_id" to correlationId,
            "hiltech.operation_id" to operationId,
            "hiltech.object_type" to "WorkOrder",
            "hiltech.command_type" to "CompleteWorkOrder",
            "employee.email" to "employee.secret@example.com",
            "employee.phone" to "+201111111111",
            "payroll.net" to "999999.99",
            "authorization" to "Bearer super-secret-token",
            "payload" to """{"nationalId":"SECRET-NID"}""",
        )

        val safe = SafeTelemetry.attributes(sensitiveRawContext)

        val carrier = mutableMapOf<String, String>()

        val client = tracer.spanBuilder("client.sync")
            .setSpanKind(SpanKind.CLIENT)
            .setAllAttributes(safe)
            .startSpan()

        val clientContext = Context.root().with(client)

        otel.propagators.textMapPropagator.inject(
            clientContext,
            carrier,
        ) { map, key, value ->
            map[key] = value
        }

        client.end()

        assertTrue(carrier.containsKey("traceparent"))

        val extracted = otel.propagators.textMapPropagator.extract(
            Context.root(),
            carrier,
            MapGetter,
        )

        val api = tracer.spanBuilder("api.command")
            .setSpanKind(SpanKind.SERVER)
            .setParent(extracted)
            .setAllAttributes(safe)
            .startSpan()

        val apiContext = extracted.with(api)

        val command = tracer.spanBuilder("command.complete_work")
            .setSpanKind(SpanKind.INTERNAL)
            .setParent(apiContext)
            .setAllAttributes(
                SafeTelemetry.attributes(
                    sensitiveRawContext + ("hiltech.module" to "work"),
                ),
            )
            .startSpan()

        val commandContext = apiContext.with(command)

        val db = tracer.spanBuilder("db.transaction")
            .setSpanKind(SpanKind.CLIENT)
            .setParent(commandContext)
            .setAllAttributes(
                SafeTelemetry.attributes(
                    mapOf(
                        "hiltech.correlation_id" to correlationId,
                        "hiltech.operation_id" to operationId,
                        "hiltech.module" to "work",
                    ),
                ),
            )
            .startSpan()

        db.end()

        val event = tracer.spanBuilder("event.listener.audit")
            .setSpanKind(SpanKind.CONSUMER)
            .setParent(commandContext)
            .setAllAttributes(
                SafeTelemetry.attributes(
                    mapOf(
                        "hiltech.correlation_id" to correlationId,
                        "hiltech.operation_id" to operationId,
                        "hiltech.module" to "audit",
                        "hiltech.error_code" to "AUDIT_WRITE_FAILED",
                        "payload" to "SECRET-NID",
                    ),
                ),
            )
            .startSpan()

        val failure = IllegalStateException("sanitized listener failure")
        event.recordException(failure)
        event.setStatus(StatusCode.ERROR, "AUDIT_WRITE_FAILED")
        event.end()

        command.end()
        api.end()

        provider.forceFlush().join(5_000)

        val spans = exporter.finishedSpanItems
        assertEquals(5, spans.size)

        val byName = spans.associateBy { it.name }

        val clientData = assertNotNull(byName["client.sync"])
        val apiData = assertNotNull(byName["api.command"])
        val commandData = assertNotNull(byName["command.complete_work"])
        val dbData = assertNotNull(byName["db.transaction"])
        val eventData = assertNotNull(byName["event.listener.audit"])

        val traceId = clientData.traceId
        assertTrue(
            spans.all { it.traceId == traceId },
            "All spans must share one W3C trace ID",
        )

        assertEquals(clientData.spanId, apiData.parentSpanId)
        assertEquals(apiData.spanId, commandData.parentSpanId)
        assertEquals(commandData.spanId, dbData.parentSpanId)
        assertEquals(commandData.spanId, eventData.parentSpanId)

        assertEquals(StatusCode.ERROR, eventData.status.statusCode)

        val flattenedTelemetry = spans
            .flatMap { span ->
                span.attributes.asMap().flatMap { (key, value) ->
                    listOf(key.key, value.toString())
                }
            }
            .joinToString("|")

        listOf(
            "employee.secret@example.com",
            "+201111111111",
            "999999.99",
            "super-secret-token",
            "SECRET-NID",
        ).forEach { secret ->
            assertFalse(
                flattenedTelemetry.contains(secret),
                "Sensitive value leaked into telemetry: $secret",
            )
        }

        assertTrue(
            spans.all {
                it.attributes.asMap().keys.all { key ->
                    key.key.startsWith("hiltech.")
                }
            },
        )

        println(
            "SPIKE-14 PASS traceId=$traceId " +
                "spans=" + spans.map { it.name },
        )
    }

    @Test
    fun client_sync_failure_diagnostic_uses_safe_context_only() {
        val diagnostics = ClientDiagnostics()

        diagnostics.recordSyncFailure(
            correlationId = "corr-123",
            operationId = "op-123",
            errorCode = "NETWORK_TIMEOUT",
            rawContext = mapOf(
                "hiltech.object_type" to "WorkOrder",
                "hiltech.command_type" to "CompleteWorkOrder",
                "employee.email" to "private@example.com",
                "payload" to "SECRET_PAYLOAD",
            ),
        )

        val event = diagnostics.snapshot().single()

        assertEquals("sync.failure", event.eventName)
        assertEquals("corr-123", event.attributes["hiltech.correlation_id"])
        assertEquals("op-123", event.attributes["hiltech.operation_id"])
        assertEquals("NETWORK_TIMEOUT", event.attributes["hiltech.error_code"])
        assertEquals("WorkOrder", event.attributes["hiltech.object_type"])
        assertFalse(event.attributes.toString().contains("private@example.com"))
        assertFalse(event.attributes.toString().contains("SECRET_PAYLOAD"))
    }

    private object MapGetter : TextMapGetter<Map<String, String>> {
        override fun keys(carrier: Map<String, String>): Iterable<String> =
            carrier.keys

        override fun get(
            carrier: Map<String, String>?,
            key: String,
        ): String? = carrier?.get(key)
    }
}

package com.hiltech.server.telemetry

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@ConfigurationProperties(prefix = "hiltech.observability")
data class HiltechObservabilityProperties(
    var enabled: Boolean = false,
    var serviceName: String = "hiltech-server",
    var otlpEndpoint: String = "http://127.0.0.1:4317",
    var exporterTimeoutMs: Long = 5_000,
) {
    fun validateEnabledConfiguration() {
        if (!enabled) {
            return
        }

        require(serviceName.isNotBlank()) {
            "hiltech.observability.service-name must not be blank."
        }
        require(
            otlpEndpoint.startsWith("http://") ||
                otlpEndpoint.startsWith("https://"),
        ) {
            "hiltech.observability.otlp-endpoint must use HTTP(S)."
        }
        require(exporterTimeoutMs in 100..30_000) {
            "hiltech.observability.exporter-timeout-ms must be between 100 and 30000."
        }
    }
}

object SafeTelemetry {
    private val allowedKeys = setOf(
        "hiltech.correlation_id",
        "hiltech.operation_id",
        "hiltech.object_type",
        "hiltech.command_type",
        "hiltech.error_code",
        "hiltech.result_code",
        "hiltech.module",
    )

    fun attributes(
        raw: Map<String, String?>,
    ): Attributes {
        val builder = Attributes.builder()

        raw.forEach { (key, value) ->
            if (key in allowedKeys && value != null) {
                builder.put(
                    AttributeKey.stringKey(key),
                    value.take(MAX_ATTRIBUTE_LENGTH),
                )
            }
        }

        return builder.build()
    }

    private const val MAX_ATTRIBUTE_LENGTH = 160
}

class HiltechTelemetryRuntime(
    val openTelemetry: OpenTelemetry,
    private val closeAction: () -> Unit,
) : AutoCloseable {
    val tracer: Tracer =
        openTelemetry.getTracer("com.hiltech.server")

    override fun close() {
        closeAction()
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HiltechObservabilityProperties::class)
class HiltechTelemetryConfiguration {
    @Bean(destroyMethod = "close")
    fun hiltechTelemetryRuntime(
        properties: HiltechObservabilityProperties,
    ): HiltechTelemetryRuntime {
        properties.validateEnabledConfiguration()

        if (!properties.enabled) {
            return HiltechTelemetryRuntime(
                openTelemetry = OpenTelemetry.noop(),
                closeAction = {},
            )
        }

        val exporter = OtlpGrpcSpanExporter.builder()
            .setEndpoint(properties.otlpEndpoint)
            .setTimeout(
                Duration.ofMillis(properties.exporterTimeoutMs),
            )
            .build()

        val provider = SdkTracerProvider.builder()
            .setResource(
                Resource.getDefault().merge(
                    Resource.create(
                        Attributes.of(
                            AttributeKey.stringKey("service.name"),
                            properties.serviceName,
                        ),
                    ),
                ),
            )
            .addSpanProcessor(
                BatchSpanProcessor.builder(exporter).build(),
            )
            .build()

        val sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(provider)
            .setPropagators(
                ContextPropagators.create(
                    W3CTraceContextPropagator.getInstance(),
                ),
            )
            .build()

        return HiltechTelemetryRuntime(
            openTelemetry = sdk,
            closeAction = {
                provider.close()
            },
        )
    }
}

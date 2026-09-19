package com.hiltech.server.documents.storage

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.ChecksumMode
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.net.URI
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

@ConfigurationProperties(prefix = "hiltech.evidence.storage")
data class EvidenceStorageProperties(
    var enabled: Boolean = false,
    var endpoint: String = "",
    var region: String = "",
    var accessKey: String = "",
    var secretKey: String = "",
    var bucket: String = "",
    var uploadExpirySeconds: Long = 600,
    var downloadExpirySeconds: Long = 300,
) {
    fun validateEnabledConfiguration() {
        if (!enabled) {
            return
        }

        require(endpoint.isNotBlank()) {
            "hiltech.evidence.storage.endpoint must be configured when evidence storage is enabled."
        }
        require(region.isNotBlank()) {
            "hiltech.evidence.storage.region must be configured when evidence storage is enabled."
        }
        require(accessKey.isNotBlank()) {
            "hiltech.evidence.storage.access-key must be configured when evidence storage is enabled."
        }
        require(secretKey.isNotBlank()) {
            "hiltech.evidence.storage.secret-key must be configured when evidence storage is enabled."
        }
        require(bucket.isNotBlank()) {
            "hiltech.evidence.storage.bucket must be configured when evidence storage is enabled."
        }
        require(uploadExpirySeconds in 30..900) {
            "hiltech.evidence.storage.upload-expiry-seconds must be between 30 and 900."
        }
        require(downloadExpirySeconds in 1..300) {
            "hiltech.evidence.storage.download-expiry-seconds must be between 1 and 300."
        }
    }
}

data class EvidenceUploadSpec(
    val evidenceId: UUID,
    val organizationId: UUID,
    val objectVersionId: UUID,
    val contentType: String,
    val expectedSizeBytes: Long,
    val expectedSha256Hex: String,
) {
    init {
        require(contentType.isNotBlank())
        require(expectedSizeBytes in 1..MAX_EVIDENCE_OBJECT_BYTES)
        require(SHA256_HEX.matches(expectedSha256Hex)) {
            "expectedSha256Hex must be a lowercase 64-character SHA-256 hex digest."
        }
    }

    val objectKey: String
        get() =
            "org/$organizationId/evidence/$evidenceId/objects/$objectVersionId"

    companion object {
        const val MAX_EVIDENCE_OBJECT_BYTES: Long = 16L * 1024L * 1024L
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
    }
}

data class SignedEvidenceUploadTarget(
    val objectKey: String,
    val uploadUrl: String,
    val requiredHeaders: Map<String, List<String>>,
    val expiresAt: Instant,
    val maxSizeBytes: Long = EvidenceUploadSpec.MAX_EVIDENCE_OBJECT_BYTES,
)

data class SignedEvidenceDownloadTarget(
    val downloadUrl: String,
    val expiresAt: Instant,
)

sealed interface EvidenceFinalizeVerification {
    data class Verified(
        val objectKey: String,
        val sizeBytes: Long,
        val sha256Hex: String,
        val providerChecksumSha256: String?,
        val detectedContentType: String? = null,
    ) : EvidenceFinalizeVerification

    data class Rejected(
        val code: String,
    ) : EvidenceFinalizeVerification

    data class Retryable(
        val code: String,
    ) : EvidenceFinalizeVerification
}

interface EvidenceObjectStoragePort {
    fun createUploadTarget(spec: EvidenceUploadSpec): SignedEvidenceUploadTarget

    fun verifyUploadedObject(spec: EvidenceUploadSpec): EvidenceFinalizeVerification

    fun createDownloadTarget(objectKey: String): SignedEvidenceDownloadTarget
}

class S3CompatibleEvidenceObjectStorage(
    private val properties: EvidenceStorageProperties,
    private val clock: Clock = Clock.systemUTC(),
) : EvidenceObjectStoragePort, AutoCloseable {
    private val credentials: StaticCredentialsProvider
    private val s3: S3Client
    private val presigner: S3Presigner

    init {
        properties.validateEnabledConfiguration()

        credentials = StaticCredentialsProvider.create(
            AwsBasicCredentials.create(
                properties.accessKey,
                properties.secretKey,
            ),
        )

        val endpoint = URI.create(properties.endpoint)
        val region = Region.of(properties.region)

        s3 = S3Client.builder()
            .endpointOverride(endpoint)
            .region(region)
            .credentialsProvider(credentials)
            .forcePathStyle(true)
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build()

        presigner = S3Presigner.builder()
            .endpointOverride(endpoint)
            .region(region)
            .credentialsProvider(credentials)
            .build()
    }

    override fun createUploadTarget(
        spec: EvidenceUploadSpec,
    ): SignedEvidenceUploadTarget {
        val checksumBase64 = Base64.getEncoder().encodeToString(
            sha256HexToBytes(spec.expectedSha256Hex),
        )

        val putRequest = PutObjectRequest.builder()
            .bucket(properties.bucket)
            .key(spec.objectKey)
            .contentType(spec.contentType)
            .contentLength(spec.expectedSizeBytes)
            .checksumSHA256(checksumBase64)
            .metadata(
                mapOf(
                    "hiltech-evidence-id" to spec.evidenceId.toString(),
                    "hiltech-sha256-hex" to spec.expectedSha256Hex,
                ),
            )
            .build()

        val signed = presigner.presignPutObject(
            PutObjectPresignRequest.builder()
                .signatureDuration(
                    Duration.ofSeconds(properties.uploadExpirySeconds),
                )
                .putObjectRequest(putRequest)
                .build(),
        )

        val requiredHeaders = signed.signedHeaders()
            .filterKeys {
                !it.equals("host", ignoreCase = true) &&
                    !it.equals("content-length", ignoreCase = true)
            }

        return SignedEvidenceUploadTarget(
            objectKey = spec.objectKey,
            uploadUrl = signed.url().toString(),
            requiredHeaders = requiredHeaders,
            expiresAt = clock.instant()
                .plusSeconds(properties.uploadExpirySeconds),
        )
    }

    override fun verifyUploadedObject(
        spec: EvidenceUploadSpec,
    ): EvidenceFinalizeVerification =
        try {
            val head = s3.headObject(
                HeadObjectRequest.builder()
                    .bucket(properties.bucket)
                    .key(spec.objectKey)
                    .checksumMode(ChecksumMode.ENABLED)
                    .build(),
            )

            if (head.contentLength() != spec.expectedSizeBytes) {
                return EvidenceFinalizeVerification.Rejected(
                    code = "EVIDENCE_SIZE_MISMATCH",
                )
            }

            val metadata = head.metadata()
            if (
                metadata["hiltech-evidence-id"] != spec.evidenceId.toString() ||
                metadata["hiltech-sha256-hex"] != spec.expectedSha256Hex
            ) {
                return EvidenceFinalizeVerification.Rejected(
                    code = "EVIDENCE_METADATA_MISMATCH",
                )
            }

            val bytes = s3.getObjectAsBytes(
                GetObjectRequest.builder()
                    .bucket(properties.bucket)
                    .key(spec.objectKey)
                    .checksumMode(ChecksumMode.ENABLED)
                    .build(),
            ).asByteArray()

            if (bytes.size.toLong() != spec.expectedSizeBytes) {
                return EvidenceFinalizeVerification.Rejected(
                    code = "EVIDENCE_SIZE_MISMATCH",
                )
            }

            val actualSha256 = sha256Hex(bytes)
            if (actualSha256 != spec.expectedSha256Hex) {
                return EvidenceFinalizeVerification.Rejected(
                    code = "EVIDENCE_SHA256_MISMATCH",
                )
            }

            EvidenceFinalizeVerification.Verified(
                objectKey = spec.objectKey,
                sizeBytes = bytes.size.toLong(),
                sha256Hex = actualSha256,
                providerChecksumSha256 = head.checksumSHA256(),
                detectedContentType =
                    detectContentType(bytes),
            )
        } catch (failure: S3Exception) {
            when {
                failure.statusCode() == 404 ->
                    EvidenceFinalizeVerification.Retryable(
                        code = "EVIDENCE_OBJECT_NOT_FOUND",
                    )

                failure.statusCode() == 408 ||
                    failure.statusCode() == 429 ||
                    failure.statusCode() >= 500 ->
                    EvidenceFinalizeVerification.Retryable(
                        code = "EVIDENCE_STORAGE_RETRYABLE",
                    )

                else ->
                    EvidenceFinalizeVerification.Rejected(
                        code = "EVIDENCE_STORAGE_REJECTED",
                    )
            }
        } catch (_: Throwable) {
            EvidenceFinalizeVerification.Retryable(
                code = "EVIDENCE_STORAGE_UNAVAILABLE",
            )
        }

    override fun createDownloadTarget(
        objectKey: String,
    ): SignedEvidenceDownloadTarget {
        require(objectKey.isNotBlank())

        val signed = presigner.presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(
                    Duration.ofSeconds(properties.downloadExpirySeconds),
                )
                .getObjectRequest(
                    GetObjectRequest.builder()
                        .bucket(properties.bucket)
                        .key(objectKey)
                        .build(),
                )
                .build(),
        )

        return SignedEvidenceDownloadTarget(
            downloadUrl = signed.url().toString(),
            expiresAt = clock.instant()
                .plusSeconds(properties.downloadExpirySeconds),
        )
    }

    override fun close() {
        presigner.close()
        s3.close()
    }

    private fun detectContentType(
        bytes: ByteArray,
    ): String? {
        if (
            bytes.size >= 3 &&
            bytes[0] == 0xff.toByte() &&
            bytes[1] == 0xd8.toByte() &&
            bytes[2] == 0xff.toByte()
        ) {
            return "image/jpeg"
        }

        val png =
            byteArrayOf(
                0x89.toByte(),
                0x50,
                0x4e,
                0x47,
                0x0d,
                0x0a,
                0x1a,
                0x0a,
            )
        if (
            bytes.size >= png.size &&
            png.indices.all {
                bytes[it] == png[it]
            }
        ) {
            return "image/png"
        }

        if (
            bytes.size >= 12 &&
            bytes.copyOfRange(0, 4)
                .decodeToString() == "RIFF" &&
            bytes.copyOfRange(8, 12)
                .decodeToString() == "WEBP"
        ) {
            return "image/webp"
        }

        return null
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun sha256HexToBytes(value: String): ByteArray =
        ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2)
                .toInt(16)
                .toByte()
        }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EvidenceStorageProperties::class)
class EvidenceObjectStorageConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(
        prefix = "hiltech.evidence.storage",
        name = ["enabled"],
        havingValue = "true",
    )
    fun evidenceObjectStorage(
        properties: EvidenceStorageProperties,
    ): S3CompatibleEvidenceObjectStorage =
        S3CompatibleEvidenceObjectStorage(properties)
}

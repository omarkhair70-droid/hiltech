package com.hiltech.spike.evidence

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.ChecksumMode
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64

data class UploadTicket(
    val evidenceId: String,
    val objectKey: String,
    val uploadUrl: String,
    val signedHeaders: Map<String, List<String>>,
    val sha256Base64: String,
    val sha256Hex: String,
    val contentType: String,
)

data class FinalizedEvidence(
    val evidenceId: String,
    val objectKey: String,
    val sizeBytes: Long,
    val sha256Hex: String,
    val providerChecksumSha256: String?,
)

class EvidenceStorage(
    endpoint: URI,
    regionName: String,
    accessKey: String,
    secretKey: String,
    private val bucket: String,
) : AutoCloseable {
    private val region = Region.of(regionName)
    private val credentials = StaticCredentialsProvider.create(
        AwsBasicCredentials.create(accessKey, secretKey),
    )

    private val s3 = S3Client.builder()
        .endpointOverride(endpoint)
        .region(region)
        .credentialsProvider(credentials)
        .forcePathStyle(true)
        .httpClientBuilder(UrlConnectionHttpClient.builder())
        .build()

    private val presigner = S3Presigner.builder()
        .endpointOverride(endpoint)
        .region(region)
        .credentialsProvider(credentials)
        .build()

    fun ensureBucket() {
        val existing = s3.listBuckets().buckets().any { it.name() == bucket }
        if (!existing) {
            s3.createBucket(
                CreateBucketRequest.builder()
                    .bucket(bucket)
                    .build(),
            )
        }
    }

    fun requestUpload(
        evidenceId: String,
        bytes: ByteArray,
        contentType: String,
    ): UploadTicket {
        val digest = sha256(bytes)
        val base64 = Base64.getEncoder().encodeToString(digest)
        val hex = digest.joinToString("") { "%02x".format(it) }
        val key = "evidence/$evidenceId/original.bin"

        val put = PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .contentType(contentType)
            .checksumSHA256(base64)
            .metadata(
                mapOf(
                    "hiltech-evidence-id" to evidenceId,
                    "hiltech-sha256-hex" to hex,
                ),
            )
            .build()

        val presigned = presigner.presignPutObject(
            PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(10))
                .putObjectRequest(put)
                .build(),
        )

        return UploadTicket(
            evidenceId = evidenceId,
            objectKey = key,
            uploadUrl = presigned.url().toString(),
            signedHeaders = presigned.signedHeaders(),
            sha256Base64 = base64,
            sha256Hex = hex,
            contentType = contentType,
        )
    }

    fun upload(
        ticket: UploadTicket,
        bytes: ByteArray,
    ): Int {
        val connection = URI(ticket.uploadUrl).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "PUT"
        connection.doOutput = true
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000

        ticket.signedHeaders.forEach { (name, values) ->
            if (
                !name.equals("host", ignoreCase = true) &&
                !name.equals("content-length", ignoreCase = true)
            ) {
                values.forEach { value ->
                    connection.addRequestProperty(name, value)
                }
            }
        }

        connection.setRequestProperty("Content-Type", ticket.contentType)
        connection.setFixedLengthStreamingMode(bytes.size)

        connection.outputStream.use { it.write(bytes) }

        val status = connection.responseCode
        if (status >= 400) {
            connection.errorStream?.use { it.readBytes() }
        } else {
            connection.inputStream?.use { it.readBytes() }
        }
        connection.disconnect()
        return status
    }

    fun finalizeEvidence(
        ticket: UploadTicket,
    ): FinalizedEvidence {
        val head = s3.headObject(
            HeadObjectRequest.builder()
                .bucket(bucket)
                .key(ticket.objectKey)
                .checksumMode(ChecksumMode.ENABLED)
                .build(),
        )

        val metadataEvidenceId = head.metadata()["hiltech-evidence-id"]
        val metadataSha = head.metadata()["hiltech-sha256-hex"]

        require(metadataEvidenceId == ticket.evidenceId) {
            "Evidence metadata ID mismatch"
        }
        require(metadataSha == ticket.sha256Hex) {
            "Stored expected checksum metadata mismatch"
        }

        val bytes = s3.getObjectAsBytes(
            GetObjectRequest.builder()
                .bucket(bucket)
                .key(ticket.objectKey)
                .checksumMode(ChecksumMode.ENABLED)
                .build(),
        ).asByteArray()

        val actualHex = sha256(bytes).joinToString("") { "%02x".format(it) }
        require(actualHex == ticket.sha256Hex) {
            "Final object bytes do not match requested SHA-256"
        }

        return FinalizedEvidence(
            evidenceId = ticket.evidenceId,
            objectKey = ticket.objectKey,
            sizeBytes = head.contentLength(),
            sha256Hex = actualHex,
            providerChecksumSha256 = head.checksumSHA256(),
        )
    }

    fun presignedDownloadUrl(
        objectKey: String,
    ): String =
        presigner.presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(5))
                .getObjectRequest(
                    GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(objectKey)
                        .build(),
                )
                .build(),
        ).url().toString()

    fun exists(objectKey: String): Boolean =
        try {
            s3.headObject(
                HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .build(),
            )
            true
        } catch (_: NoSuchKeyException) {
            false
        } catch (exception: Exception) {
            val status = (exception as? software.amazon.awssdk.services.s3.model.S3Exception)
                ?.statusCode()
            if (status == 404) false else throw exception
        }

    override fun close() {
        presigner.close()
        s3.close()
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)
}

package com.hiltech.server.documents.storage

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

class EvidenceObjectStorageContractTest {
    companion object {
        private val enabled =
            System.getenv("HILTECH_EVIDENCE_STORAGE_CONTRACT_TEST") == "1"
        private val endpoint =
            System.getenv("S3_ENDPOINT") ?: "http://127.0.0.1:5000"
        private val region =
            System.getenv("AWS_REGION") ?: "us-east-1"
        private val accessKey =
            System.getenv("AWS_ACCESS_KEY_ID") ?: "test"
        private val secretKey =
            System.getenv("AWS_SECRET_ACCESS_KEY") ?: "test"
        private const val bucket = "hiltech-evidence-contract"

        private var bucketClient: S3Client? = null

        @JvmStatic
        @BeforeAll
        fun createPrivateBucket() {
            if (!enabled) {
                return
            }

            val client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(
                    StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                            accessKey,
                            secretKey,
                        ),
                    ),
                )
                .forcePathStyle(true)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build()

            client.createBucket(
                CreateBucketRequest.builder()
                    .bucket(bucket)
                    .build(),
            )

            bucketClient = client
        }

        @JvmStatic
        @AfterAll
        fun closeBucketClient() {
            bucketClient?.close()
        }
    }

    @Test
    fun signedPutFinalizeRetryAndPrivateDownloadMatchFrozenEvidenceContract() {
        assumeTrue(enabled)

        val storage = S3CompatibleEvidenceObjectStorage(
            properties = EvidenceStorageProperties(
                enabled = true,
                endpoint = endpoint,
                region = region,
                accessKey = accessKey,
                secretKey = secretKey,
                bucket = bucket,
                uploadExpirySeconds = 600,
                downloadExpirySeconds = 300,
            ),
        )

        storage.use {
            val bytes = representativeEvidence()
            val spec = evidenceSpec(
                bytes = bytes,
                evidenceId = UUID.randomUUID(),
            )
            val upload = storage.createUploadTarget(spec)

            assertTrue(
                upload.requiredHeaders.keys.any {
                    it.equals(
                        "x-amz-checksum-sha256",
                        ignoreCase = true,
                    )
                },
                "SHA-256 checksum must be part of the signed PUT contract.",
            )
            assertTrue(
                upload.objectKey.startsWith(
                    "org/${spec.organizationId}/evidence/${spec.evidenceId}/objects/",
                ),
            )
            assertEquals(
                EvidenceUploadSpec.MAX_EVIDENCE_OBJECT_BYTES,
                upload.maxSizeBytes,
            )

            val truncated = bytes.copyOf(bytes.size / 3)
            val firstStatus = httpPut(
                upload = upload,
                contentType = spec.contentType,
                bytes = truncated,
            )

            if (firstStatus in 200..299) {
                val rejected = storage.verifyUploadedObject(spec)
                assertTrue(
                    rejected is EvidenceFinalizeVerification.Rejected,
                    "Truncated evidence must never finalize as verified.",
                )
            } else {
                assertTrue(firstStatus >= 400)
            }

            val retryStatus = httpPut(
                upload = upload,
                contentType = spec.contentType,
                bytes = bytes,
            )
            assertTrue(retryStatus in 200..299)

            val finalized = storage.verifyUploadedObject(spec)
            assertTrue(
                finalized is EvidenceFinalizeVerification.Verified,
            )
            finalized as EvidenceFinalizeVerification.Verified
            assertEquals(bytes.size.toLong(), finalized.sizeBytes)
            assertEquals(spec.expectedSha256Hex, finalized.sha256Hex)

            val unsignedUrl =
                endpoint.trimEnd('/') +
                    "/$bucket/${upload.objectKey}"
            val unsigned = httpGet(unsignedUrl)
            assertTrue(
                unsigned.first == 401 || unsigned.first == 403,
                "Private evidence was readable without a signed GET: HTTP ${unsigned.first}",
            )

            val download = storage.createDownloadTarget(upload.objectKey)
            val signed = httpGet(download.downloadUrl)
            assertEquals(200, signed.first)
            assertArrayEquals(bytes, signed.second)
        }
    }

    @Test
    fun pdfSignatureIsDetectedForSignedDocumentEvidence() {
        assumeTrue(enabled)

        val storage =
            S3CompatibleEvidenceObjectStorage(
                properties =
                    EvidenceStorageProperties(
                        enabled = true,
                        endpoint = endpoint,
                        region = region,
                        accessKey = accessKey,
                        secretKey = secretKey,
                        bucket = bucket,
                        uploadExpirySeconds = 600,
                        downloadExpirySeconds = 300,
                    ),
            )

        storage.use {
            val bytes =
                (
                    "%PDF-1.7\n" +
                        "HILTECH HR document fixture\n" +
                        "%%EOF\n"
                ).toByteArray()
            val spec =
                evidenceSpec(
                    bytes = bytes,
                    evidenceId =
                        UUID.randomUUID(),
                    contentType =
                        "application/pdf",
                )
            val upload =
                storage.createUploadTarget(
                    spec,
                )

            val status =
                httpPut(
                    upload = upload,
                    contentType =
                        spec.contentType,
                    bytes = bytes,
                )
            assertTrue(
                status in 200..299,
            )

            val finalized =
                storage.verifyUploadedObject(
                    spec,
                )
            assertTrue(
                finalized is
                    EvidenceFinalizeVerification.Verified,
            )
            finalized as
                EvidenceFinalizeVerification.Verified

            assertEquals(
                "application/pdf",
                finalized.detectedContentType,
            )
            assertEquals(
                spec.expectedSha256Hex,
                finalized.sha256Hex,
            )
        }
    }

    @Test
    fun evidenceSpecRejectsObjectsBeyondFrozenSixteenMiBLimit() {
        val digest = "a".repeat(64)

        var rejected = false
        try {
            EvidenceUploadSpec(
                evidenceId = UUID.randomUUID(),
                organizationId = UUID.randomUUID(),
                objectVersionId = UUID.randomUUID(),
                contentType = "application/octet-stream",
                expectedSizeBytes =
                    EvidenceUploadSpec.MAX_EVIDENCE_OBJECT_BYTES + 1,
                expectedSha256Hex = digest,
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
    }

    private fun evidenceSpec(
        bytes: ByteArray,
        evidenceId: UUID,
        contentType: String =
            "application/octet-stream",
    ): EvidenceUploadSpec =
        EvidenceUploadSpec(
            evidenceId = evidenceId,
            organizationId = UUID.randomUUID(),
            objectVersionId = UUID.randomUUID(),
            contentType = contentType,
            expectedSizeBytes = bytes.size.toLong(),
            expectedSha256Hex = sha256Hex(bytes),
        )

    private fun representativeEvidence(): ByteArray =
        ByteArray(1024 * 1024) { index ->
            ((index * 31 + 17) and 0xff).toByte()
        }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun httpPut(
        upload: SignedEvidenceUploadTarget,
        contentType: String,
        bytes: ByteArray,
    ): Int {
        val connection =
            URI(upload.uploadUrl).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "PUT"
        connection.doOutput = true
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000

        upload.requiredHeaders.forEach { (name, values) ->
            if (
                !name.equals("host", ignoreCase = true) &&
                !name.equals("content-length", ignoreCase = true)
            ) {
                values.forEach { value ->
                    connection.addRequestProperty(name, value)
                }
            }
        }

        connection.setRequestProperty("Content-Type", contentType)
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

    private fun httpGet(
        url: String,
    ): Pair<Int, ByteArray> {
        val connection =
            URI(url).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000

        val status = connection.responseCode
        val body = if (status >= 400) {
            connection.errorStream?.use { it.readBytes() } ?: byteArrayOf()
        } else {
            connection.inputStream.use { it.readBytes() }
        }

        connection.disconnect()
        return status to body
    }
}

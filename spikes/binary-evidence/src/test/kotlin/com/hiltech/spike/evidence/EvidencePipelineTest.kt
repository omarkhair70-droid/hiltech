package com.hiltech.spike.evidence

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EvidencePipelineTest {
    companion object {
        private lateinit var storage: EvidenceStorage

        @JvmStatic
        @BeforeAll
        fun setup() {
            storage = EvidenceStorage(
                endpoint = URI(
                    System.getenv("S3_ENDPOINT")
                        ?: "http://127.0.0.1:5000",
                ),
                regionName = "us-east-1",
                accessKey = System.getenv("AWS_ACCESS_KEY_ID") ?: "test",
                secretKey = System.getenv("AWS_SECRET_ACCESS_KEY") ?: "test",
                bucket = "hiltech-evidence-spike",
            )
            storage.ensureBucket()
        }

        @JvmStatic
        @AfterAll
        fun close() {
            storage.close()
        }
    }

    @Test
    fun presigned_upload_finalizes_only_when_bytes_match_expected_sha256() {
        val bytes = representativeEvidence()
        val ticket = storage.requestUpload(
            evidenceId = "ev-001",
            bytes = bytes,
            contentType = "application/octet-stream",
        )

        assertTrue(
            ticket.signedHeaders.keys.any {
                it.equals("x-amz-checksum-sha256", ignoreCase = true)
            },
            "Checksum must be part of the signed upload contract",
        )

        val uploadStatus = storage.upload(ticket, bytes)
        assertTrue(uploadStatus in 200..299)

        val finalized = storage.finalizeEvidence(ticket)
        assertEquals(bytes.size.toLong(), finalized.sizeBytes)
        assertEquals(ticket.sha256Hex, finalized.sha256Hex)

        val download = httpGet(storage.presignedDownloadUrl(ticket.objectKey))
        assertEquals(200, download.first)
        assertContentEquals(bytes, download.second)

        println(
            "SPIKE-12 finalized evidence=" + finalized +
                " providerChecksumPresent=" +
                (finalized.providerChecksumSha256 != null),
        )
    }

    @Test
    fun corrupt_or_interrupted_payload_is_rejected_then_full_retry_succeeds() {
        val bytes = representativeEvidence()
        val ticket = storage.requestUpload(
            evidenceId = "ev-002",
            bytes = bytes,
            contentType = "application/octet-stream",
        )

        val truncated = bytes.copyOf(bytes.size / 3)
        val firstStatus = storage.upload(ticket, truncated)

        if (firstStatus in 200..299) {
            // Some S3-compatible emulators/providers may accept the PUT even
            // when they do not enforce x-amz-checksum-sha256 on ingest.
            // HILTECH finalization must still reject the stored object after
            // recomputing the real bytes.
            assertFailsWith<IllegalArgumentException> {
                storage.finalizeEvidence(ticket)
            }
        } else {
            assertTrue(firstStatus >= 400)
        }

        val retryStatus = storage.upload(ticket, bytes)
        assertTrue(retryStatus in 200..299)

        val finalized = storage.finalizeEvidence(ticket)
        assertEquals(ticket.sha256Hex, finalized.sha256Hex)
        assertEquals(bytes.size.toLong(), finalized.sizeBytes)
    }

    @Test
    fun object_is_not_readable_without_a_presigned_get() {
        val bytes = "restricted-site-evidence".encodeToByteArray()
        val ticket = storage.requestUpload(
            evidenceId = "ev-private",
            bytes = bytes,
            contentType = "application/octet-stream",
        )

        assertTrue(storage.upload(ticket, bytes) in 200..299)
        storage.finalizeEvidence(ticket)

        val unsignedUrl = (
            System.getenv("S3_ENDPOINT") ?: "http://127.0.0.1:5000"
        ).trimEnd('/') + "/hiltech-evidence-spike/" + ticket.objectKey

        val unsigned = httpGet(unsignedUrl)
        assertTrue(
            unsigned.first == 401 || unsigned.first == 403,
            "Private evidence was readable without a signed request: HTTP " + unsigned.first,
        )

        val signed = httpGet(storage.presignedDownloadUrl(ticket.objectKey))
        assertEquals(200, signed.first)
        assertContentEquals(bytes, signed.second)
    }

    private fun representativeEvidence(): ByteArray =
        ByteArray(1024 * 1024) { index ->
            ((index * 31 + 17) and 0xff).toByte()
        }

    private fun httpGet(url: String): Pair<Int, ByteArray> {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
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

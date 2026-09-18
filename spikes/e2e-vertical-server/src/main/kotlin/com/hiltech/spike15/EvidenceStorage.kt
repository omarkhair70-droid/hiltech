package com.hiltech.spike15

import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.net.URI
import java.security.MessageDigest
import java.time.Duration

data class ReservedEvidence(
    val objectKey: String,
    val uploadUrl: String,
)

@Component
class EvidenceStorage(
    @Value("\${S3_BACKEND_ENDPOINT:http://127.0.0.1:9000}")
    backendEndpoint: String,
    @Value("\${S3_PRESIGN_ENDPOINT:http://10.0.2.2:9000}")
    presignEndpoint: String,
    @Value("\${S3_ACCESS_KEY:minio}")
    accessKey: String,
    @Value("\${S3_SECRET_KEY:minio123}")
    secretKey: String,
    @Value("\${S3_BUCKET:hiltech-spike15}")
    private val bucket: String,
) : AutoCloseable {
    private val region = Region.US_EAST_1
    private val credentials = StaticCredentialsProvider.create(
        AwsBasicCredentials.create(accessKey, secretKey),
    )

    private val s3 = S3Client.builder()
        .endpointOverride(URI.create(backendEndpoint))
        .region(region)
        .credentialsProvider(credentials)
        .forcePathStyle(true)
        .httpClientBuilder(UrlConnectionHttpClient.builder())
        .build()

    private val presigner = S3Presigner.builder()
        .endpointOverride(URI.create(presignEndpoint))
        .region(region)
        .credentialsProvider(credentials)
        .build()

    @PostConstruct
    fun ensureBucket() {
        if (s3.listBuckets().buckets().none { it.name() == bucket }) {
            s3.createBucket(
                CreateBucketRequest.builder()
                    .bucket(bucket)
                    .build(),
            )
        }
    }

    fun reserve(
        evidenceId: String,
        contentType: String,
    ): ReservedEvidence {
        val objectKey = "evidence/$evidenceId/original.bin"
        val put = PutObjectRequest.builder()
            .bucket(bucket)
            .key(objectKey)
            .contentType(contentType)
            .build()

        val signed = presigner.presignPutObject(
            PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(10))
                .putObjectRequest(put)
                .build(),
        )

        return ReservedEvidence(
            objectKey = objectKey,
            uploadUrl = signed.url().toString(),
        )
    }

    fun verifySha256(
        objectKey: String,
        expectedHex: String,
    ): Long {
        val bytes = s3.getObjectAsBytes(
            GetObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build(),
        ).asByteArray()

        val actual = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

        require(actual == expectedHex) {
            "Evidence SHA-256 mismatch expected=$expectedHex actual=$actual"
        }

        return bytes.size.toLong()
    }

    override fun close() {
        presigner.close()
        s3.close()
    }
}

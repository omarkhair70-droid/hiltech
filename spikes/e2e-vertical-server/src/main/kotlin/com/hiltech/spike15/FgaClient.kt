package com.hiltech.spike15

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@Component
class FgaClient(
    @Value("\${FGA_API_URL:http://127.0.0.1:8082}")
    private val baseUrl: String,
    @Value("\${FGA_STORE_ID:}")
    private val storeId: String,
    @Value("\${FGA_MODEL_ID:}")
    private val modelId: String,
) {
    private val client = HttpClient.newHttpClient()

    fun allowed(
        actor: String,
        relation: String,
        objectRef: String,
    ): Boolean {
        require(storeId.isNotBlank()) { "FGA_STORE_ID is required" }
        require(modelId.isNotBlank()) { "FGA_MODEL_ID is required" }

        val body =
            """{"tuple_key":{"user":"user:$actor","relation":"$relation","object":"$objectRef"},"authorization_model_id":"$modelId"}"""

        val request = HttpRequest.newBuilder(
            URI.create(baseUrl.trimEnd('/') + "/stores/$storeId/check"),
        )
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = client.send(
            request,
            HttpResponse.BodyHandlers.ofString(),
        )

        check(response.statusCode() == 200) {
            "OpenFGA check failed: ${response.statusCode()} ${response.body()}"
        }

        return Regex("\\"allowed\\"\\s*:\\s*(true|false)")
            .find(response.body())
            ?.groupValues
            ?.get(1)
            ?.toBooleanStrict()
            ?: error("OpenFGA response missing allowed: ${response.body()}")
    }
}

package com.zhousl.aether.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleClientVertexTest {
    private val client = OpenAiCompatibleClient()

    @Test
    fun vertexStreamEntryPointUsesGenerateContentToAvoidBlockedStreamMethod() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "candidates": [
                        {
                          "content": {
                            "role": "model",
                            "parts": [
                              { "text": "fallback ok" }
                            ]
                          },
                          "finishReason": "STOP"
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )
        server.start()

        try {
            val result = client.streamChatCompletion(
                settings = AppSettings(
                    provider = LlmProvider.VertexExpress,
                    apiKey = "test-key",
                    baseUrl = server.url("/v1").toString(),
                    modelId = "gemini-2.5-flash",
                ),
                systemPrompt = "",
                conversation = listOf(
                    JSONObject(
                        """
                        {
                          "role": "user",
                          "parts": [
                            { "text": "Hello" }
                          ]
                        }
                        """.trimIndent()
                    )
                ),
            ).getOrThrow()

            assertEquals("fallback ok", result.assistantText)

            val request = server.takeRequest()
            assertTrue(request.path!!.contains(":generateContent"))
            assertFalse(request.path!!.contains(":streamGenerateContent"))
            assertFalse(request.path!!.contains("alt=sse"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun vertexGenerateContentBlockedFallsBackToGeminiDeveloperApi() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "error": {
                        "message": "Requests to this API aiplatform.googleapis.com method google.cloud.aiplatform.v1.PredictionService.GenerateContent are blocked."
                      }
                    }
                    """.trimIndent()
                )
        )
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "candidates": [
                        {
                          "content": {
                            "role": "model",
                            "parts": [
                              { "text": "developer api ok" }
                            ]
                          },
                          "finishReason": "STOP"
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )
        server.start()

        try {
            val result = client.createChatCompletion(
                settings = AppSettings(
                    provider = LlmProvider.VertexExpress,
                    apiKey = "test-key",
                    baseUrl = server.url("/v1").toString(),
                    modelId = "publishers/google/models/gemini-2.5-flash",
                ),
                systemPrompt = "",
                conversation = listOf(
                    JSONObject(
                        """
                        {
                          "role": "user",
                          "parts": [
                            { "text": "Hello" }
                          ]
                        }
                        """.trimIndent()
                    )
                ),
            ).getOrThrow()

            assertEquals("developer api ok", result.assistantText)

            val vertexRequest = server.takeRequest()
            val developerApiRequest = server.takeRequest()
            assertTrue(vertexRequest.path!!.contains("/v1/publishers/google/models/gemini-2.5-flash:generateContent"))
            assertTrue(developerApiRequest.path!!.contains("/v1beta/models/gemini-2.5-flash:generateContent"))
            assertFalse(developerApiRequest.path!!.contains("publishers/google"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun vertexRequestsStripFunctionIdsFromConversationPayload() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "candidates": [
                        {
                          "content": {
                            "role": "model",
                            "parts": [
                              { "text": "ok" }
                            ]
                          }
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )
        server.start()

        try {
            val settings = AppSettings(
                provider = LlmProvider.VertexExpress,
                apiKey = "test-key",
                baseUrl = server.url("/v1").toString(),
                modelId = "gemini-2.5-flash",
            )
            val conversation = listOf(
                JSONObject(
                    """
                    {
                      "role": "user",
                      "parts": [
                        { "text": "Inspect README" }
                      ]
                    }
                    """.trimIndent()
                ),
                JSONObject(
                    """
                    {
                      "role": "model",
                      "parts": [
                        {
                          "functionCall": {
                            "id": "call-1",
                            "name": "read",
                            "args": { "path": "README.md" }
                          }
                        }
                      ]
                    }
                    """.trimIndent()
                ),
                JSONObject(
                    """
                    {
                      "role": "user",
                      "parts": [
                        {
                          "functionResponse": {
                            "id": "call-1",
                            "name": "read",
                            "response": {
                              "output": "hello"
                            }
                          }
                        }
                      ]
                    }
                    """.trimIndent()
                ),
            )

            val result = client.createChatCompletion(
                settings = settings,
                systemPrompt = "",
                conversation = conversation,
            ).getOrThrow()

            assertEquals("ok", result.assistantText)

            val request = server.takeRequest()
            val payload = JSONObject(request.body.readUtf8())
            val contents = payload.getJSONArray("contents")
            val functionCall = contents
                .getJSONObject(1)
                .getJSONArray("parts")
                .getJSONObject(0)
                .getJSONObject("functionCall")
            val functionResponse = contents
                .getJSONObject(2)
                .getJSONArray("parts")
                .getJSONObject(0)
                .getJSONObject("functionResponse")

            assertFalse(functionCall.has("id"))
            assertFalse(functionResponse.has("id"))
            assertEquals("read", functionCall.getString("name"))
            assertEquals("read", functionResponse.getString("name"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun vertexToolResultMessagesDoNotIncludeIdField() {
        val settings = AppSettings(
            provider = LlmProvider.VertexExpress,
            apiKey = "test-key",
            baseUrl = "https://aiplatform.googleapis.com/v1",
            modelId = "gemini-2.5-flash",
        )

        val message = client.buildToolResultMessage(
            settings = settings,
            callId = "call-1",
            name = "read",
            output = """{"text":"hello"}""",
        )

        val functionResponse = message
            .getJSONArray("parts")
            .getJSONObject(0)
            .getJSONObject("functionResponse")

        assertFalse(functionResponse.has("id"))
        assertEquals("read", functionResponse.getString("name"))
        assertEquals("hello", functionResponse.getJSONObject("response").getJSONObject("output").getString("text"))
    }

    @Test
    fun vertexRequestsDropEmptyInlineDataParts() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "candidates": [
                        {
                          "content": {
                            "role": "model",
                            "parts": [
                              { "text": "ok" }
                            ]
                          }
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )
        server.start()

        try {
            val settings = AppSettings(
                provider = LlmProvider.VertexExpress,
                apiKey = "test-key",
                baseUrl = server.url("/v1").toString(),
                modelId = "gemini-2.5-flash",
            )
            val conversation = listOf(
                JSONObject(
                    """
                    {
                      "role": "user",
                      "parts": [
                        { "text": "Inspect this image" },
                        { "inlineData": { "mimeType": "image/png", "data": "" } },
                        { "inlineData": { "mimeType": "image/png", "data": "YWJj" } }
                      ]
                    }
                    """.trimIndent()
                )
            )

            val result = client.createChatCompletion(
                settings = settings,
                systemPrompt = "",
                conversation = conversation,
            ).getOrThrow()

            assertEquals("ok", result.assistantText)

            val request = server.takeRequest()
            val payload = JSONObject(request.body.readUtf8())
            val parts = payload
                .getJSONArray("contents")
                .getJSONObject(0)
                .getJSONArray("parts")

            assertEquals(2, parts.length())
            assertFalse(parts.getJSONObject(0).has("inlineData"))
            assertEquals("YWJj", parts.getJSONObject(1).getJSONObject("inlineData").getString("data"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun vertexRequestsDropBlankTextWhenPartCarriesFunctionCall() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "candidates": [
                        {
                          "content": {
                            "role": "model",
                            "parts": [
                              { "text": "ok" }
                            ]
                          }
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )
        server.start()

        try {
            val settings = AppSettings(
                provider = LlmProvider.VertexExpress,
                apiKey = "test-key",
                baseUrl = server.url("/v1").toString(),
                modelId = "gemini-2.5-flash",
            )
            val conversation = listOf(
                JSONObject(
                    """
                    {
                      "role": "model",
                      "parts": [
                        {
                          "text": "",
                          "functionCall": {
                            "id": "call-1",
                            "name": "read",
                            "args": { "path": "README.md" }
                          }
                        }
                      ]
                    }
                    """.trimIndent()
                )
            )

            val result = client.createChatCompletion(
                settings = settings,
                systemPrompt = "",
                conversation = conversation,
            ).getOrThrow()

            assertEquals("ok", result.assistantText)

            val request = server.takeRequest()
            val payload = JSONObject(request.body.readUtf8())
            val parts = payload
                .getJSONArray("contents")
                .getJSONObject(0)
                .getJSONArray("parts")

            assertEquals(1, parts.length())
            assertFalse(parts.getJSONObject(0).has("text"))
            assertEquals("read", parts.getJSONObject(0).getJSONObject("functionCall").getString("name"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun vertexRequestsDropBlankTextWhenPartCarriesInlineData() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "candidates": [
                        {
                          "content": {
                            "role": "model",
                            "parts": [
                              { "text": "ok" }
                            ]
                          }
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )
        server.start()

        try {
            val settings = AppSettings(
                provider = LlmProvider.VertexExpress,
                apiKey = "test-key",
                baseUrl = server.url("/v1").toString(),
                modelId = "gemini-2.5-flash",
            )
            val conversation = listOf(
                JSONObject(
                    """
                    {
                      "role": "user",
                      "parts": [
                        {
                          "text": "",
                          "inlineData": { "mimeType": "image/png", "data": "YWJj" }
                        }
                      ]
                    }
                    """.trimIndent()
                )
            )

            val result = client.createChatCompletion(
                settings = settings,
                systemPrompt = "",
                conversation = conversation,
            ).getOrThrow()

            assertEquals("ok", result.assistantText)

            val request = server.takeRequest()
            val payload = JSONObject(request.body.readUtf8())
            val parts = payload
                .getJSONArray("contents")
                .getJSONObject(0)
                .getJSONArray("parts")

            assertEquals(1, parts.length())
            assertFalse(parts.getJSONObject(0).has("text"))
            assertEquals("YWJj", parts.getJSONObject(0).getJSONObject("inlineData").getString("data"))
        } finally {
            server.shutdown()
        }
    }
}

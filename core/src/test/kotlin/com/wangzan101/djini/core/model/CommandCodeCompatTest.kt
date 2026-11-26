package com.wangzan101.djini.core.model

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CommandCodeCompatTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    @Test fun stripBearerPrefixAvoidsDoubleBearer() {
        assertEquals("user_abc123", ApiKeyText.stripBearerPrefix("user_abc123"))
        assertEquals("user_abc123", ApiKeyText.stripBearerPrefix("Bearer user_abc123"))
        assertEquals("user_abc123", ApiKeyText.stripBearerPrefix("bearer  Bearer user_abc123 "))
        assertEquals("user_abc123", ApiKeyText.stripBearerPrefix("Authorization: Bearer user_abc123"))
    }

    @Test fun authErrorPreservesServerReason() {
        val err = ModelHttp.openAiError(
            401,
            null,
            """{"error":{"message":"Invalid 'Authorization' header or token.","type":"authentication_error"}}""",
            "deepseek-v4.1-flash",
        )
        assertTrue(err is ModelError.Auth)
        val auth = err as ModelError.Auth
        assertEquals("Invalid 'Authorization' header or token.", auth.detail)
        assertTrue(auth.message!!.contains("Invalid 'Authorization' header or token."))
    }

    @Test fun anthropicModelSendsBearerHeaderOnlyWhenEnabled() = runTest {
        val base = server.url("/provider").toString().trimEnd('/')
        val caps = Capabilities(toolCalls = true, vision = true, contextTokens = 200_000, reasoning = false)
        val okJson = """{"id":"m1","type":"message","role":"assistant","content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}"""

        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(okJson))
        AnthropicModel(base, "Bearer user_secret", "claude-sonnet-5-5", caps, OkHttpClient(), bearerAuth = true)
            .complete("sys", listOf(Message(Role.User, listOf(Part.Text("hi")), Author.User)), emptyList())
        val gatewayReq = server.takeRequest()
        assertEquals("/provider/v1/messages", gatewayReq.path)
        assertEquals("Bearer user_secret", gatewayReq.getHeader("Authorization"))
        assertEquals("user_secret", gatewayReq.getHeader("x-api-key"))

        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(okJson))
        AnthropicModel(base, "sk-ant-123", "claude-sonnet-5-5", caps, OkHttpClient(), bearerAuth = false)
            .complete("sys", listOf(Message(Role.User, listOf(Part.Text("hi")), Author.User)), emptyList())
        val directReq = server.takeRequest()
        assertNull(directReq.getHeader("Authorization"))
        assertEquals("sk-ant-123", directReq.getHeader("x-api-key"))
    }
}

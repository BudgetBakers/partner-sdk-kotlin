// Transport behavior through the public client against a loopback stub:
// headers, retries with backoff on 429/5xx, Retry-After, and which methods
// may be replayed. Waits are asserted on the injected sleeper, never on the
// wall clock.

package com.budgetbakers.partner

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class TransportTest {

    private val server = StubServer()
    private val sleeper = RecordingSleeper()

    @AfterEach
    fun stop() = server.close()

    @Test
    fun `sends the auth, accept and user agent headers`() {
        server.reply(200, CONFIG_BODY)
        stubClient(server).partner.getConfig()
        val call = server.requests.single()
        assertEquals(API_KEY, call.header("X-Api-Key"))
        assertEquals("application/json", call.header("Accept"))
        assertEquals("budgetbakers-partner-sdk-kotlin/$SDK_VERSION", call.header("User-Agent"))
    }

    @Test
    fun `sends a JSON content type with a body`() {
        server.reply(201, """{"data":$CLIENT_JSON}""")
        stubClient(server).clients.create(ClientCreateRequest("u@x.test", "CZ", "u1"))
        assertEquals("application/json", server.requests.single().header("Content-Type")?.substringBefore(';'))
    }

    @Test
    fun `429 with Retry-After of one second waits 1000 ms then succeeds`() {
        server.reply(429, errorBody("rate_limited"), mapOf("Retry-After" to "1")).reply(200, CONFIG_BODY)
        val config = stubClient(server, sleeper, maxRetries = 3).partner.getConfig()
        assertEquals("p1", config.partnerId)
        assertEquals(listOf(1000L), sleeper.waits)
        assertEquals(2, server.requests.size)
    }

    @Test
    fun `503 twice then 200 backs off base then twice base`() {
        server.reply(503, errorBody("internal_error")).reply(503, errorBody("internal_error")).reply(200, CONFIG_BODY)
        stubClient(server, sleeper, maxRetries = 3, retryBaseMs = 100, random = 0.5).partner.getConfig()
        assertEquals(listOf(100L, 200L), sleeper.waits)
        assertEquals(3, server.requests.size)
    }

    @Test
    fun `jitter stays within 25 percent of the exponential delay`() {
        server.reply(500, errorBody("internal_error")).reply(500, errorBody("internal_error")).reply(200, CONFIG_BODY)
        val low = RecordingSleeper()
        stubClient(server, low, maxRetries = 3, retryBaseMs = 100, random = 0.0).partner.getConfig()
        assertEquals(listOf(75L, 150L), low.waits)

        server.reply(500, errorBody("internal_error")).reply(500, errorBody("internal_error")).reply(200, CONFIG_BODY)
        val high = RecordingSleeper()
        stubClient(server, high, maxRetries = 3, retryBaseMs = 100, random = 0.999).partner.getConfig()
        assertEquals(2, high.waits.size)
        assertTrue(high.waits[0] in 120L..125L, "first wait ${high.waits[0]}")
        assertTrue(high.waits[1] in 240L..250L, "second wait ${high.waits[1]}")
    }

    @Test
    fun `a Retry-After that is not whole seconds falls back to the backoff`() {
        server.reply(429, errorBody("rate_limited"), mapOf("Retry-After" to "Wed, 21 Oct 2026 07:28:00 GMT"))
            .reply(200, CONFIG_BODY)
        stubClient(server, sleeper, maxRetries = 3, retryBaseMs = 100, random = 0.5).partner.getConfig()
        assertEquals(listOf(100L), sleeper.waits)
    }

    @Test
    fun `exhausted retries surface the last error after maxRetries plus one attempts`() {
        server.fallback = Reply(500, errorBody("internal_error"))
        val e = assertFailsWith<PartnerApiException> {
            stubClient(server, sleeper, maxRetries = 3, retryBaseMs = 100, random = 0.5).partner.getConfig()
        }
        assertEquals(ErrorCode.INTERNAL_ERROR, e.code)
        assertEquals(500, e.httpStatus)
        assertEquals(4, server.requests.size)
        assertEquals(listOf(100L, 200L, 400L), sleeper.waits)
    }

    @Test
    fun `maxRetries zero sends one attempt`() {
        server.fallback = Reply(503, errorBody("internal_error"))
        assertFailsWith<PartnerApiException> { stubClient(server, sleeper, maxRetries = 0).partner.getConfig() }
        assertEquals(1, server.requests.size)
        assertEquals(emptyList(), sleeper.waits)
    }

    @Test
    fun `a 4xx other than 429 is never retried`() {
        server.fallback = Reply(406, errorBody("background_refresh_not_allowed"))
        val e = assertFailsWith<PartnerApiException> {
            stubClient(server, sleeper, maxRetries = 3).client("c1").connections.get("x1")
        }
        assertEquals(ErrorCode.BACKGROUND_REFRESH_NOT_ALLOWED, e.code)
        assertEquals(406, e.httpStatus)
        assertEquals(1, server.requests.size)
    }

    @Test
    fun `DELETE and PATCH are retried`() {
        server.reply(503, errorBody("internal_error")).reply(204)
        stubClient(server, sleeper, maxRetries = 3).client("c1").connections.delete("x1")
        server.reply(503, errorBody("internal_error")).reply(200)
        stubClient(server, sleeper, maxRetries = 3).client("c1").connections.revoke("x1")
        assertEquals(listOf("DELETE", "DELETE", "PATCH", "PATCH"), server.requests.map { it.method })
    }

    @Test
    fun `POST without an Idempotency-Key is sent exactly once`() {
        server.fallback = Reply(500, errorBody("internal_error"))
        val bb = stubClient(server, sleeper, maxRetries = 3)
        assertFailsWith<PartnerApiException> { bb.clients.create(ClientCreateRequest("u@x.test", "CZ")) }
        assertFailsWith<PartnerApiException> { bb.client("c1").connections.refresh("x1") }
        assertEquals(listOf("POST", "POST"), server.requests.map { it.method })
        assertTrue(server.requests.all { it.header("Idempotency-Key") == null })
        assertEquals(emptyList(), sleeper.waits)
    }

    @Test
    fun `POST with an Idempotency-Key is retried with the same key`() {
        server.reply(500, errorBody("internal_error")).reply(201, CREATE_CONNECTION_BODY)
        val created = stubClient(server, sleeper, maxRetries = 3).client("c1").connections.create("p1", "k1")
        assertEquals("x1", created.connectionId)
        assertEquals(listOf("k1", "k1"), server.requests.map { it.header("Idempotency-Key") })
        assertEquals(listOf(100L), sleeper.waits)
    }

    @Test
    fun `X-Request-Id lands on the exception`() {
        server.reply(404, errorBody("not_found", requestId = "req_body"), mapOf("X-Request-Id" to "req_hdr"))
        val e = assertFailsWith<PartnerApiException> { stubClient(server).client("c1").connections.get("x1") }
        assertEquals("req_hdr", e.requestId)
    }

    @Test
    fun `the body requestId is the fallback without the header`() {
        server.reply(404, errorBody("not_found", requestId = "req_body"))
        val e = assertFailsWith<PartnerApiException> { stubClient(server).client("c1").connections.get("x1") }
        assertEquals("req_body", e.requestId)
    }

    @Test
    fun `no request id anywhere leaves it null`() {
        server.reply(404, errorBody("not_found", requestId = null))
        val e = assertFailsWith<PartnerApiException> { stubClient(server).client("c1").connections.get("x1") }
        assertNull(e.requestId)
    }

    @Test
    fun `an unreachable endpoint throws PartnerApiUnreachable with the cause`() {
        val bb = BudgetBakers(API_KEY, closedPortUrl(), 100, 0, null, sleeper)
        val e = assertFailsWith<PartnerApiUnreachable> { bb.partner.getConfig() }
        assertNotNull(e.cause)
        assertFalse(PartnerApiException::class.java.isInstance(e))
    }
}

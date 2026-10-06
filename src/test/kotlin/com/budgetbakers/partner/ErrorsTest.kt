// Error envelope mapping: callers branch on `code` only, so a code outside
// the published set and a gateway body without an envelope both fall back
// to a status-derived code.

package com.budgetbakers.partner

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class ErrorsTest {

    private val server = StubServer()

    @AfterEach
    fun stop() = server.close()

    private fun failGet(status: Int, body: String, headers: Map<String, String> = emptyMap()): PartnerApiException {
        server.reply(status, body, headers)
        return assertFailsWith<PartnerApiException> { stubClient(server).client("c1").connections.get("x1") }
    }

    @Test
    fun `the published code set`() {
        assertEquals(
            setOf(
                "validation_error", "unauthorized", "capability_disabled", "operation_temporarily_unavailable",
                "connection_not_recoverable", "consent_inactive", "not_found", "refresh_in_progress",
                "refresh_cooldown", "refresh_quota_exceeded", "background_refresh_not_allowed", "rate_limited",
                "internal_error",
            ),
            ErrorCode.KNOWN,
        )
    }

    @Test
    fun `a refresh cooldown carries every envelope field`() {
        server.reply(
            400,
            """{"errorDesc":"x","error":{"code":"refresh_cooldown","message":"Refresh not possible yet.",""" +
                """"nextRefreshPossibleAt":"2026-07-15T10:20:00.000+00:00"},"requestId":"req_8f3a2c1e"}""",
        )
        val e = assertFailsWith<PartnerApiException> { stubClient(server).client("c1").connections.refresh("x1") }
        assertEquals(ErrorCode.REFRESH_COOLDOWN, e.code)
        assertEquals(400, e.httpStatus)
        assertEquals("req_8f3a2c1e", e.requestId)
        assertEquals("Refresh not possible yet.", e.message)
        assertEquals("2026-07-15T10:20:00.000+00:00", e.nextRefreshPossibleAt)
    }

    @Test
    fun `nextRefreshPossibleAt is read from inside error only`() {
        val e = failGet(
            400,
            """{"error":{"code":"refresh_quota_exceeded","message":"m"},"nextRefreshPossibleAt":"2026-07-16T00:00:00Z"}""",
        )
        assertEquals(ErrorCode.REFRESH_QUOTA_EXCEEDED, e.code)
        assertNull(e.nextRefreshPossibleAt)
    }

    @Test
    fun `every published code maps to itself`() {
        for (code in ErrorCode.KNOWN) {
            assertEquals(code, failGet(409, errorBody(code)).code, code)
        }
    }

    @Test
    fun `an unknown code falls back to the status`() {
        assertEquals(ErrorCode.INTERNAL_ERROR, failGet(409, errorBody("some_future_code")).code)
        assertEquals(ErrorCode.NOT_FOUND, failGet(404, errorBody("some_future_code")).code)
        assertEquals(ErrorCode.UNAUTHORIZED, failGet(401, errorBody("some_future_code")).code)
        val e = failGet(409, errorBody("some_future_code", message = "kept"))
        assertEquals("kept", e.message)
        assertEquals(409, e.httpStatus)
    }

    @Test
    fun `a non-JSON 502 becomes internal_error`() {
        val e = failGet(502, "<html>Bad Gateway</html>")
        assertEquals(ErrorCode.INTERNAL_ERROR, e.code)
        assertEquals(502, e.httpStatus)
        assertEquals("HTTP 502", e.message)
        assertNull(e.nextRefreshPossibleAt)
    }

    @Test
    fun `gateway bodies without an envelope map by status`() {
        assertEquals(ErrorCode.NOT_FOUND, failGet(404, "no route").code)
        assertEquals(ErrorCode.UNAUTHORIZED, failGet(401, """{"message":"No API key found in request"}""").code)
        assertEquals(ErrorCode.RATE_LIMITED, failGet(429, """{"message":"API rate limit exceeded"}""").code)
        assertEquals(ErrorCode.INTERNAL_ERROR, failGet(400, "").code)
    }

    @Test
    fun `an envelope without a message defaults to the status line`() {
        val e = failGet(403, """{"error":{"code":"consent_inactive"}}""")
        assertEquals(ErrorCode.CONSENT_INACTIVE, e.code)
        assertEquals("HTTP 403", e.message)
    }
}

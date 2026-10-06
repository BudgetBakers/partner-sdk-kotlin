// Webhook signing and event parsing pinned by the language-neutral vectors in
// contract-tests/fixtures/webhooksig.json and events.json, plus the header
// shapes the vectors do not spell out.

package com.budgetbakers.partner

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class WebhooksTest {

    // Test working directory is the module dir: sdks/kotlin -> repo root.
    private fun fixture(name: String): JsonNode =
        ObjectMapper().readTree(File("../../contract-tests/fixtures/$name"))

    private val sig = fixture("webhooksig.json")
    private val events = fixture("events.json")

    private fun JsonNode.text(field: String): String? = get(field)?.takeUnless { it.isNull }?.asText()

    @Test
    fun `constants match the fixture`() {
        assertEquals(sig["header"].asText(), Webhooks.SIGNATURE_HEADER)
        assertEquals(sig["toleranceSeconds"].asLong(), Webhooks.TOLERANCE_SECONDS)
    }

    @TestFactory
    fun signVectors(): List<DynamicTest> = sig["signVectors"].map { v ->
        DynamicTest.dynamicTest(v["name"].asText()) {
            val body = v["body"].asText()
            val expected = v["expectedHeader"].asText()
            assertEquals(expected, Webhooks.sign(v["secret"].asText(), v["timestamp"].asLong(), body))
            assertEquals(
                expected,
                Webhooks.sign(v["secret"].asText(), v["timestamp"].asLong(), body.toByteArray(StandardCharsets.UTF_8)),
            )
        }
    }

    @TestFactory
    fun verifyVectors(): List<DynamicTest> = sig["verifyVectors"].map { v ->
        DynamicTest.dynamicTest(v["name"].asText()) {
            val secrets = v["secrets"].map { it.asText() }
            val header = v["header"].asText()
            val body = v["body"].asText()
            val now = v["now"].asLong()
            val expect = v["expect"].asText()
            assertEquals(expect, Webhooks.verify(secrets, header, body, now).value, "string body, epoch seconds")
            assertEquals(
                expect,
                Webhooks.verify(secrets, header, body.toByteArray(StandardCharsets.UTF_8), now).value,
                "byte body, epoch seconds",
            )
            assertEquals(expect, Webhooks.verify(secrets, header, body, Instant.ofEpochSecond(now)).value, "Instant")
        }
    }

    @Test
    fun `a freshly signed delivery verifies against the current clock`() {
        val now = Instant.now().epochSecond
        val header = Webhooks.sign("whsec_test_fresh", now, "{}")
        assertEquals(VerifyResult.VALID, Webhooks.verify(listOf("whsec_test_fresh"), header, "{}"))
        assertEquals(
            VerifyResult.TIMESTAMP_OUT_OF_TOLERANCE,
            Webhooks.verify(listOf("whsec_test_fresh"), Webhooks.sign("whsec_test_fresh", now - 301, "{}"), "{}"),
        )
    }

    @Test
    fun `malformed header shapes`() {
        val good = sig["verifyVectors"][0]
        val secrets = good["secrets"].map { it.asText() }
        val body = good["body"].asText()
        val now = good["now"].asLong()
        val v1 = good["header"].asText().substringAfter("v1=")
        for (header in listOf(
            "t=1784102400,t=1784102400,v1=$v1",
            "t=1784102400,v1",
            "t=1784102400,v1=",
            "t=,v1=$v1",
            "=1784102400,v1=$v1",
            "t=-1784102400,v1=$v1",
            "t=99999999999999999999999999,v1=$v1",
            "t=1784102400,v1=${v1}00",
        )) {
            assertEquals(VerifyResult.MALFORMED_HEADER, Webhooks.verify(secrets, header, body, now), header)
        }
    }

    @Test
    fun `verify results print as their wire value`() {
        assertEquals(
            listOf("valid", "invalid_signature", "timestamp_out_of_tolerance", "malformed_header"),
            VerifyResult.entries.map { it.toString() },
        )
    }

    @Test
    fun `the event type enum covers exactly the fixture types`() {
        val fixtureTypes = events["vectors"]
            .filter { it["name"].asText().startsWith("type-") }
            .map { it["expect"]["type"].asText() }
            .toSet()
        assertEquals(fixtureTypes, WebhookEventType.entries.map { it.name }.toSet())
        assertEquals(19, WebhookEventType.entries.size)
    }

    @TestFactory
    fun eventVectors(): List<DynamicTest> = events["vectors"].map { v ->
        DynamicTest.dynamicTest(v["name"].asText()) {
            val expect = v["expect"]
            val body = v["body"].asText()
            for (parsed in listOf(Webhooks.parseEvent(body), Webhooks.parseEvent(body.toByteArray(StandardCharsets.UTF_8)))) {
                assertEquals(expect["kind"].asText(), parsed.kind)
                when (expect["kind"].asText()) {
                    "event" -> {
                        val event = assertIs<WebhookEvent>(parsed)
                        assertEquals(expect["type"].asText(), event.type.name)
                        expect.text("eventId")?.let { assertEquals(it, event.eventId) }
                        expect.text("clientId")?.let { assertEquals(it, event.clientId) }
                        expect.text("connectionId")?.let { assertEquals(it, event.connectionId) }
                        assertEquals(expect.text("reasonCode"), event.reason?.code)
                        assertEquals("2026-07-15T08:10:30.000Z", event.createdAt)
                        expect["extra"]?.properties()?.forEach { (key, value) ->
                            assertEquals(value.asLong(), (event.extra[key] as Number).toLong(), key)
                        }
                    }
                    "unknown" -> {
                        val unknown = assertIs<UnknownEvent>(parsed)
                        assertEquals(expect["type"].asText(), unknown.type)
                        assertEquals(expect["type"].asText(), unknown.raw["type"])
                    }
                    else -> assertIs<WebhookParseError>(parsed)
                }
            }
        }
    }

    @Test
    fun `known fields stay out of extra and the reason message is kept`() {
        val parsed = Webhooks.parseEvent(
            """{"eventId":"e1","type":"ConnectionRefreshFailed","clientId":"c1","connectionId":"x1",""" +
                """"createdAt":"2026-07-15T08:10:30.000Z","reason":{"code":"provider_error","message":"Bank down."},"attempt":2}""",
        )
        val event = assertIs<WebhookEvent>(parsed)
        assertEquals(WebhookEventType.ConnectionRefreshFailed, event.type)
        assertEquals(WebhookReason("provider_error", "Bank down."), event.reason)
        assertEquals(setOf("attempt"), event.extra.keys)
    }

    @Test
    fun `an event without a reason has a null reason`() {
        val event = assertIs<WebhookEvent>(
            Webhooks.parseEvent(
                """{"eventId":"e1","type":"ConnectionDeleted","clientId":"c1","connectionId":"x1","createdAt":"2026-07-15T08:10:30.000Z"}""",
            ),
        )
        assertNull(event.reason)
        assertEquals(emptyMap(), event.extra)
    }

    @Test
    fun `non-object JSON is a parse error, never an exception`() {
        for (body in listOf("[1,2]", "42", "\"text\"", "null", "", "{")) {
            assertIs<WebhookParseError>(Webhooks.parseEvent(body), body)
            assertEquals("parse_error", Webhooks.parseEvent(body).kind, body)
        }
    }
}

// Webhook signature verification and typed event parsing.
//
// X-BB-Signature: t=<unix-ts>,v1=<hex HMAC_SHA256(secret, "{t}." + raw_body)>.
// Constant-time comparison against every active secret (two during rotation),
// +-300 s timestamp window, every v1 entry collected, unknown scheme keys
// ignored.

package com.budgetbakers.partner

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class VerifyResult(
    /** The wire spelling shared by every BudgetBakers SDK. */
    val value: String,
) {
    VALID("valid"),
    INVALID_SIGNATURE("invalid_signature"),
    TIMESTAMP_OUT_OF_TOLERANCE("timestamp_out_of_tolerance"),
    MALFORMED_HEADER("malformed_header");

    override fun toString(): String = value
}

private val HEX_32_BYTES = Regex("^[0-9a-fA-F]{64}$")
private val DIGITS = Regex("^[0-9]+$")
private val MAX_SAFE_INTEGER = BigInteger.valueOf(9007199254740991L)
private val KNOWN_FIELDS = setOf("eventId", "type", "clientId", "connectionId", "createdAt", "reason")
private val EVENT_TYPES: Map<String, WebhookEventType> = WebhookEventType.entries.associateBy { it.name }

private class ParsedHeader(val ts: String, val sigs: List<ByteArray>)

private fun parseHeader(header: String): ParsedHeader? {
    if (header.isEmpty()) return null
    var ts = ""
    val sigs = mutableListOf<ByteArray>()
    for (element in header.split(',')) {
        val eq = element.indexOf('=')
        if (eq <= 0) return null
        val key = element.substring(0, eq)
        val value = element.substring(eq + 1)
        if (value.isEmpty()) return null
        if (key == "t") {
            if (ts.isNotEmpty()) return null // duplicate t
            if (!DIGITS.matches(value)) return null
            ts = value
        } else if (key == "v1") {
            if (!HEX_32_BYTES.matches(value)) return null
            sigs.add(hexToBytes(value))
        }
        // Unknown scheme keys are ignored (forward compatibility).
    }
    if (ts.isEmpty() || sigs.isEmpty()) return null
    return ParsedHeader(ts, sigs)
}

private fun hexToBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }

private fun digest(secret: String, ts: String, rawBody: ByteArray): ByteArray {
    // HMAC zero-pads the key, so an empty secret equals a single zero byte
    // (the JDK refuses an empty key).
    val key = secret.toByteArray(StandardCharsets.UTF_8).let { if (it.isEmpty()) ByteArray(1) else it }
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    mac.update(ts.toByteArray(StandardCharsets.US_ASCII))
    mac.update('.'.code.toByte())
    mac.update(rawBody)
    return mac.doFinal()
}

private fun stringOf(node: JsonNode?): String = when {
    node == null || node.isNull || node.isMissingNode -> ""
    node.isValueNode -> node.asText()
    else -> node.toString()
}

object Webhooks {
    const val SIGNATURE_HEADER = "X-BB-Signature"
    const val TOLERANCE_SECONDS = 300L

    /**
     * Verify a delivery. Pass ALL currently active secrets (the portal shows two
     * during rotation) and the raw body bytes exactly as received.
     */
    @JvmStatic
    @JvmOverloads
    fun verify(secrets: List<String>, header: String, rawBody: ByteArray, now: Instant = Instant.now()): VerifyResult =
        verify(secrets, header, rawBody, now.epochSecond)

    @JvmStatic
    @JvmOverloads
    fun verify(secrets: List<String>, header: String, rawBody: String, now: Instant = Instant.now()): VerifyResult =
        verify(secrets, header, rawBody.toByteArray(StandardCharsets.UTF_8), now.epochSecond)

    /** [now] as unix seconds. */
    @JvmStatic
    fun verify(secrets: List<String>, header: String, rawBody: ByteArray, nowEpochSeconds: Long): VerifyResult {
        val parsed = parseHeader(header) ?: return VerifyResult.MALFORMED_HEADER
        val t = BigInteger(parsed.ts)
        if (t > MAX_SAFE_INTEGER) return VerifyResult.MALFORMED_HEADER
        if ((BigInteger.valueOf(nowEpochSeconds) - t).abs() > BigInteger.valueOf(TOLERANCE_SECONDS)) {
            return VerifyResult.TIMESTAMP_OUT_OF_TOLERANCE
        }
        for (secret in secrets) {
            val expected = digest(secret, parsed.ts, rawBody)
            for (sig in parsed.sigs) {
                if (MessageDigest.isEqual(sig, expected)) return VerifyResult.VALID
            }
        }
        return VerifyResult.INVALID_SIGNATURE
    }

    @JvmStatic
    fun verify(secrets: List<String>, header: String, rawBody: String, nowEpochSeconds: Long): VerifyResult =
        verify(secrets, header, rawBody.toByteArray(StandardCharsets.UTF_8), nowEpochSeconds)

    /** Sign [rawBody] at unix time [timestamp]; returns the full header value (tests and tooling). */
    @JvmStatic
    fun sign(secret: String, timestamp: Long, rawBody: ByteArray): String {
        val t = timestamp.toString()
        return "t=$t,v1=" + digest(secret, t, rawBody).joinToString("") { "%02x".format(it) }
    }

    @JvmStatic
    fun sign(secret: String, timestamp: Long, rawBody: String): String =
        sign(secret, timestamp, rawBody.toByteArray(StandardCharsets.UTF_8))

    /**
     * Parse a delivery body into a typed event. Never throws: unknown types pass
     * through as [UnknownEvent], a body that is not a JSON object yields [WebhookParseError].
     */
    @JvmStatic
    fun parseEvent(rawBody: ByteArray): ParsedWebhook = parseEvent(String(rawBody, StandardCharsets.UTF_8))

    @JvmStatic
    fun parseEvent(rawBody: String): ParsedWebhook {
        val raw: JsonNode? = try {
            json.readTree(rawBody)
        } catch (e: JacksonException) {
            return WebhookParseError(e.originalMessage ?: e.toString())
        }
        if (raw == null || !raw.isObject) return WebhookParseError("webhook body is not a JSON object")
        val typeNode = raw.get("type")
        val type = if (typeNode != null && typeNode.isTextual) typeNode.asText() else ""
        val rawMap = toMap(raw)
        val known = EVENT_TYPES[type] ?: return UnknownEvent(type, rawMap)
        val reasonNode = raw.get("reason")
        val reason = if (reasonNode != null && reasonNode.isObject) {
            val message = reasonNode.get("message")
            WebhookReason(stringOf(reasonNode.get("code")), if (message != null && message.isTextual) message.asText() else null)
        } else {
            null
        }
        return WebhookEvent(
            type = known,
            eventId = stringOf(raw.get("eventId")),
            clientId = stringOf(raw.get("clientId")),
            connectionId = stringOf(raw.get("connectionId")),
            createdAt = stringOf(raw.get("createdAt")),
            reason = reason,
            extra = rawMap.filterKeys { it !in KNOWN_FIELDS },
        )
    }

    private fun toMap(node: JsonNode): Map<String, Any?> =
        json.convertValue(node, json.typeFactory.constructMapType(LinkedHashMap::class.java, String::class.java, Any::class.java))
}

// HTTP transport: auth headers, BigDecimal-safe body parsing, typed errors,
// and retries with exponential backoff and jitter on 429/5xx honoring
// Retry-After. POST is retried only when an Idempotency-Key makes the replay
// safe.

package com.budgetbakers.partner

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import java.io.IOException
import java.math.BigDecimal
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Properties
import java.util.function.DoubleSupplier

internal object SdkVersion {
    val value: String by lazy {
        val props = Properties()
        SdkVersion::class.java.getResourceAsStream("sdk-version.properties")?.use { props.load(it) }
        props.getProperty("version") ?: "unknown"
    }
}

// Money fields arrive as decimal strings (v2) or bare numbers; both go through
// the canonical form from the raw token, never through a double.
private object MoneyDeserializer : JsonDeserializer<BigDecimal>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): BigDecimal? = when (p.currentToken) {
        JsonToken.VALUE_STRING, JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT ->
            BigDecimal(Money.toDecimalString(p.text))
        JsonToken.VALUE_NULL -> null
        else -> ctxt.handleUnexpectedToken(BigDecimal::class.java, p) as BigDecimal?
    }
}

internal val json: ObjectMapper = JsonMapper.builder()
    .addModule(kotlinModule())
    .addModule(SimpleModule().addDeserializer(BigDecimal::class.java, MoneyDeserializer))
    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
    .build()

// The single-resource v2 envelope `{ "data": ... }`.
internal class Envelope<T>(val data: T)

private val RETRYABLE_METHODS = setOf("GET", "DELETE", "PATCH", "PUT")
private val DIGITS = Regex("^[0-9]+$")

/** encodeURIComponent: everything but A-Z a-z 0-9 - _ . ! ~ * ' ( ) is percent-encoded. */
internal fun encodePathSegment(value: String): String {
    val out = StringBuilder()
    for (b in value.toByteArray(StandardCharsets.UTF_8)) {
        val c = b.toInt() and 0xff
        val ch = c.toChar()
        if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-_.!~*'()") {
            out.append(ch)
        } else {
            out.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xf])
        }
    }
    return out.toString()
}

internal fun <T> pagesOf(fetchPage: (String?) -> Page<T>): Iterable<Page<T>> = Iterable {
    iterator {
        var cursor: String? = null
        while (true) {
            val page = fetchPage(cursor)
            yield(page)
            cursor = page.nextCursor ?: break
        }
    }
}

internal fun <T> itemsOf(pages: Iterable<Page<T>>): Iterable<T> = Iterable {
    pages.asSequence().flatMap { it.data.asSequence() }.iterator()
}

internal class Transport(
    private val baseUrl: String,
    private val apiKey: String,
    private val retryBaseMs: Long,
    private val maxRetries: Int,
    private val http: HttpClient,
    val sleeper: Sleeper,
    private val random: DoubleSupplier,
) {
    private val userAgent = "budgetbakers-partner-sdk-kotlin/${SdkVersion.value}"

    fun type(raw: Class<*>, vararg params: Class<*>): JavaType =
        if (params.isEmpty()) json.typeFactory.constructType(raw)
        else json.typeFactory.constructParametricType(raw, *params)

    fun pageType(item: Class<*>): JavaType = type(Page::class.java, item)

    /** request() for v2 single-resource operations: returns the envelope's `data`. */
    fun <T> requestData(
        method: String,
        path: String,
        type: JavaType,
        clientId: String? = null,
        body: Any? = null,
    ): T {
        val text = send(method, path, clientId, emptyList(), body, null)
        val envelope: Envelope<T> = try {
            json.readValue(text ?: "", json.typeFactory.constructParametricType(Envelope::class.java, type))
        } catch (e: IOException) {
            throw IllegalStateException("partner API: expected a { data } envelope", e)
        }
        return envelope.data
    }

    fun <T> request(
        method: String,
        path: String,
        type: JavaType,
        clientId: String? = null,
        query: List<Pair<String, String?>> = emptyList(),
        body: Any? = null,
        idempotencyKey: String? = null,
    ): T {
        val text = send(method, path, clientId, query, body, idempotencyKey)
        return json.readValue(text ?: "null", type)
    }

    /** Sends without reading a typed body (deletes, revoke). */
    fun requestUnit(method: String, path: String, clientId: String? = null) {
        send(method, path, clientId, emptyList(), null, null)
    }

    private fun send(
        method: String,
        path: String,
        clientId: String?,
        query: List<Pair<String, String?>>,
        body: Any?,
        idempotencyKey: String?,
    ): String? {
        val queryString = query
            .filter { it.second != null }
            .joinToString("&") { (k, v) -> "${formEncode(k)}=${formEncode(v!!)}" }
        val uri = URI.create(baseUrl + path + if (queryString.isEmpty()) "" else "?$queryString")

        val builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .header("X-Api-Key", apiKey)
            .header("User-Agent", userAgent)
        if (clientId != null) builder.header("X-Client-Id", clientId)
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey)
        val publisher = if (body != null) {
            builder.header("Content-Type", "application/json")
            HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8)
        } else {
            HttpRequest.BodyPublishers.noBody()
        }
        val httpRequest = builder.method(method, publisher).build()

        val canRetry = method in RETRYABLE_METHODS || idempotencyKey != null
        var attempt = 0
        while (true) {
            val response = try {
                http.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            } catch (e: IOException) {
                // HttpTimeoutException included.
                throw PartnerApiUnreachable(e)
            }
            val status = response.statusCode()
            val text = response.body() ?: ""
            if (status in 200..299) {
                return text.ifEmpty { null }
            }

            val retryable = status == 429 || status >= 500
            if (retryable && canRetry && attempt < maxRetries) {
                val retryAfter = response.headers().firstValue("Retry-After").orElse(null)
                val delayMs = if (retryAfter != null && DIGITS.matches(retryAfter)) {
                    retryAfter.toLong() * 1000
                } else {
                    // Exponential backoff with +-25% jitter to avoid thundering herds.
                    (retryBaseMs * Math.pow(2.0, attempt.toDouble()) * (0.75 + random.asDouble * 0.5)).toLong()
                }
                attempt += 1
                sleeper.sleep(delayMs)
                continue
            }

            throw parseErrorEnvelope(status, text, response.headers().firstValue("X-Request-Id").orElse(null))
        }
    }

    private fun formEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}

// Fallbacks for gateway-shaped errors that lack the envelope (401/429 from the gateway).
private fun statusFallback(status: Int): String = when (status) {
    401 -> ErrorCode.UNAUTHORIZED
    404 -> ErrorCode.NOT_FOUND
    429 -> ErrorCode.RATE_LIMITED
    else -> ErrorCode.INTERNAL_ERROR
}

internal fun parseErrorEnvelope(status: Int, bodyText: String, headerRequestId: String?): PartnerApiException {
    var code = statusFallback(status)
    var message = "HTTP $status"
    var requestId = headerRequestId
    var nextRefreshPossibleAt: String? = null
    val body: JsonNode? = try {
        json.readTree(bodyText)
    } catch (_: IOException) {
        null // non-JSON error body (gateway): keep the status-derived fallback
    }
    if (body != null && body.isObject) {
        val error = body.get("error")
        if (error != null && error.isObject) {
            val errorCode = error.get("code")
            if (errorCode != null && errorCode.isTextual && errorCode.asText() in ErrorCode.KNOWN) code = errorCode.asText()
            val errorMessage = error.get("message")
            if (errorMessage != null && errorMessage.isTextual) message = errorMessage.asText()
            val next = error.get("nextRefreshPossibleAt")
            if (next != null && next.isTextual) nextRefreshPossibleAt = next.asText()
        }
        val bodyRequestId = body.get("requestId")
        if (requestId == null && bodyRequestId != null && bodyRequestId.isTextual) requestId = bodyRequestId.asText()
    }
    return PartnerApiException(code, status, requestId, message, nextRefreshPossibleAt)
}

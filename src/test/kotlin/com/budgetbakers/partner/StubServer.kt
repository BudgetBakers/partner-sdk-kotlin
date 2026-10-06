package com.budgetbakers.partner

import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.DoubleSupplier

const val API_KEY = "bb_test_unit"

// Version the build publishes; read from the module so a version bump needs no test edit.
val SDK_VERSION: String =
    File("gradle.properties").readLines().first { it.startsWith("version=") }.removePrefix("version=").trim()

data class Reply(val status: Int, val body: String = "", val headers: Map<String, String> = emptyMap())

class Recorded(val method: String, val path: String, val rawQuery: String?, private val headers: Headers, val body: String) {
    fun header(name: String): String? = headers.getFirst(name)

    fun query(name: String): List<String> =
        (rawQuery ?: "").split('&').filter { it.isNotEmpty() }.mapNotNull { pair ->
            val key = URLDecoder.decode(pair.substringBefore('='), StandardCharsets.UTF_8)
            if (key == name) URLDecoder.decode(pair.substringAfter('=', ""), StandardCharsets.UTF_8) else null
        }
}

// Answers queued replies in order, then `fallback`; records every request.
class StubServer : AutoCloseable {
    private val replies = ConcurrentLinkedQueue<Reply>()
    val requests = CopyOnWriteArrayList<Recorded>()
    var fallback = Reply(500, errorBody("internal_error"))

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            requests += Recorded(
                exchange.requestMethod,
                exchange.requestURI.rawPath,
                exchange.requestURI.rawQuery,
                exchange.requestHeaders,
                body,
            )
            val reply = replies.poll() ?: fallback
            reply.headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
            val bytes = reply.body.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(reply.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        start()
    }

    val baseUrl: String = "http://127.0.0.1:${server.address.port}"

    fun reply(status: Int, body: String = "", headers: Map<String, String> = emptyMap()): StubServer {
        replies += Reply(status, body, headers)
        return this
    }

    override fun close() = server.stop(0)
}

class RecordingSleeper : Sleeper {
    val waits = CopyOnWriteArrayList<Long>()
    override fun sleep(millis: Long) {
        waits += millis
    }
}

fun errorBody(code: String, message: String = "msg $code", requestId: String? = "req_body_1"): String {
    val rid = if (requestId == null) "" else ""","requestId":"$requestId""""
    return """{"errorDesc":"x","error":{"code":"$code","message":"$message"}$rid}"""
}

fun stubClient(
    server: StubServer,
    sleeper: Sleeper = RecordingSleeper(),
    maxRetries: Int = 0,
    retryBaseMs: Long = 100,
    random: Double = 0.5,
): BudgetBakers = BudgetBakers(
    apiKey = API_KEY,
    baseUrl = server.baseUrl,
    retryBaseMs = retryBaseMs,
    maxRetries = maxRetries,
    sleeper = sleeper,
    random = DoubleSupplier { random },
)

// A loopback port nothing listens on.
fun closedPortUrl(): String {
    val port = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
    return "http://127.0.0.1:$port"
}

const val CONFIG_BODY =
    """{"partnerId":"p1","name":"Acme","mode":"sandbox","capabilities":{"refresh":true,"reconnect":true,""" +
        """"enrichment":false,"autoRevokeAfterCreate":false,"nonRegulatedProviders":false},""" +
        """"webhook":{"signatureVersion":"v1"},"consentDuration":"P90D","countries":["CZ"]}"""
const val CLIENT_JSON = """{"id":"c1","externalId":"u1","email":"u@x.test","countryCode":"CZ"}"""
const val CONNECTION_JSON = """{"id":"x1","state":"Active","providerId":"p1","consentExpiresAt":"2026-10-27T00:00:00Z"}"""
const val ACCOUNT_JSON =
    """{"id":"a1","subscriptionStatus":"Active","name":"Main","type":"Current","balance":"1234.56","currencyCode":"CZK","iban":null}"""
const val CREATE_CONNECTION_BODY = """{"connectionId":"x1","redirectUrl":"https://bank.test/auth","expiresAt":"2026-10-01T10:00:00Z"}"""
const val SESSION_CREATE_BODY = """{"sessionId":"s1","hostedUrl":"https://connect.test/s1","expiresAt":"2026-10-01T10:00:00Z"}"""
const val EMPTY_PAGE = """{"limit":10,"nextCursor":null,"data":[]}"""

fun sessionBody(state: String, connectionId: String? = null): String {
    val conn = if (connectionId == null) "null" else "\"$connectionId\""
    return """{"sessionId":"s1","state":"$state","connectionId":$conn,"resultCode":null,"error":null}"""
}

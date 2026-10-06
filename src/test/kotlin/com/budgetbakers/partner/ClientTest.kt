// The client surface against a loopback stub: paths and methods per
// operation, X-Client-Id scoping, automatic Idempotency-Key, envelope
// unwrapping, cursor walks and the connect-session poll helper.

package com.budgetbakers.partner

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class ClientTest {

    private val server = StubServer()
    private val sleeper = RecordingSleeper()
    private val bb get() = stubClient(server, sleeper)

    @AfterEach
    fun stop() = server.close()

    private val uuid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)

    private fun json(body: String) = ObjectMapper().readTree(body)

    private class Op(val name: String, val method: String, val path: String, val reply: Reply, val call: (BudgetBakers) -> Unit)

    private val operations = listOf(
        Op("partner.getConfig", "GET", "/v2/partner/config", Reply(200, CONFIG_BODY)) { it.partner.getConfig() },
        Op("providers.pages", "GET", "/v2/providers", Reply(200, EMPTY_PAGE)) { it.providers.pages().first() },
        Op("clients.create", "POST", "/v2/clients", Reply(201, """{"data":$CLIENT_JSON}""")) {
            it.clients.create(ClientCreateRequest("u@x.test", "CZ"))
        },
        Op("clients.get", "GET", "/v2/clients/c1", Reply(200, """{"data":$CLIENT_JSON}""")) { it.clients.get("c1") },
        Op("clients.getByExternalId", "GET", "/v2/clients", Reply(200, EMPTY_PAGE)) { it.clients.getByExternalId("u1") },
        Op("client.delete", "DELETE", "/v2/clients/c1", Reply(204)) { it.client("c1").delete() },
        Op("connections.get", "GET", "/v2/connections/x1", Reply(200, """{"data":$CONNECTION_JSON}""")) {
            it.client("c1").connections.get("x1")
        },
        Op("connections.accountPages", "GET", "/v2/connections/x1/accounts", Reply(200, EMPTY_PAGE)) {
            it.client("c1").connections.accountPages("x1").first()
        },
        Op("accounts.get", "GET", "/v2/accounts/a1", Reply(200, """{"data":$ACCOUNT_JSON}""")) {
            it.client("c1").accounts.get("a1")
        },
        Op("accounts.transactionPages", "GET", "/v2/accounts/a1/transactions", Reply(200, EMPTY_PAGE)) {
            it.client("c1").accounts.transactionPages("a1").first()
        },
        Op("connectSessions.create", "POST", "/v2/connect-sessions", Reply(201, SESSION_CREATE_BODY)) {
            it.client("c1").connectSessions.create("https://partner.test/cb")
        },
        Op("connectSessions.get", "GET", "/v2/connect-sessions/s1", Reply(200, sessionBody("Fetching"))) {
            it.client("c1").connectSessions.get("s1")
        },
        Op("connections.create", "POST", "/v1/connections", Reply(201, CREATE_CONNECTION_BODY)) {
            it.client("c1").connections.create("p1")
        },
        Op("connections.delete", "DELETE", "/v1/connections/x1", Reply(204)) { it.client("c1").connections.delete("x1") },
        Op("connections.refresh", "POST", "/v1/connections/x1/refresh", Reply(202, """{"status":"Accepted"}""")) {
            it.client("c1").connections.refresh("x1")
        },
        Op("connections.reconnect", "POST", "/v1/connections/x1/reconnect", Reply(201, CREATE_CONNECTION_BODY)) {
            it.client("c1").connections.reconnect("x1")
        },
        Op("connections.revoke", "PATCH", "/v1/connections/x1/revoke", Reply(200)) { it.client("c1").connections.revoke("x1") },
    )

    private fun run(op: Op): Recorded {
        server.reply(op.reply.status, op.reply.body)
        op.call(bb)
        return server.requests.last()
    }

    @TestFactory
    fun `reads and creates on v2, lifecycle actions on v1`(): List<DynamicTest> = operations.map { op ->
        DynamicTest.dynamicTest("${op.name} calls ${op.method} ${op.path}") {
            val call = run(op)
            assertEquals(op.method, call.method)
            assertEquals(op.path, call.path)
        }
    }

    @Test
    fun `X-Client-Id rides on client-scoped calls and clients get only`() {
        val unscoped = setOf("partner.getConfig", "providers.pages", "clients.create", "clients.getByExternalId")
        for (op in operations) {
            val sent = run(op).header("X-Client-Id")
            if (op.name in unscoped) assertNull(sent, op.name) else assertEquals("c1", sent, op.name)
        }
    }

    @Test
    fun `Idempotency-Key is sent on the three creates only`() {
        val keyed = setOf("connections.create", "connections.reconnect", "connectSessions.create")
        for (op in operations) {
            val sent = run(op).header("Idempotency-Key")
            if (op.name in keyed) assertTrue(sent != null && uuid.matches(sent), "${op.name}: $sent") else assertNull(sent, op.name)
        }
    }

    @Test
    fun `an automatic Idempotency-Key is fresh per call and an explicit key wins`() {
        server.reply(201, SESSION_CREATE_BODY).reply(201, SESSION_CREATE_BODY).reply(201, SESSION_CREATE_BODY)
            .reply(201, CREATE_CONNECTION_BODY).reply(201, CREATE_CONNECTION_BODY)
        val scope = bb.client("c1")
        scope.connectSessions.create("https://partner.test/cb")
        scope.connectSessions.create("https://partner.test/cb")
        scope.connectSessions.create("https://partner.test/cb", idempotencyKey = "mine")
        scope.connections.create("p1", "conn-key")
        scope.connections.reconnect("x1", "reconnect-key")
        val keys = server.requests.map { it.header("Idempotency-Key") }
        assertNotEquals(keys[0], keys[1])
        assertEquals(listOf("mine", "conn-key", "reconnect-key"), keys.drop(2))
    }

    @Test
    fun `request bodies carry the documented fields`() {
        server.reply(201, """{"data":$CLIENT_JSON}""").reply(201, CREATE_CONNECTION_BODY)
            .reply(201, SESSION_CREATE_BODY).reply(201, SESSION_CREATE_BODY)
        bb.clients.create(ClientCreateRequest("u@x.test", "CZ", "u1"))
        bb.client("c1").connections.create("p1")
        bb.client("c1").connectSessions.create("https://partner.test/cb", connectionId = "x1")
        bb.client("c1").connectSessions.create("https://partner.test/cb", providerId = "p1")
        val (client, connection, reconnectSession, session) = server.requests.map { json(it.body) }
        assertEquals("u@x.test", client["email"].asText())
        assertEquals("CZ", client["countryCode"].asText())
        assertEquals("u1", client["externalId"].asText())
        assertEquals("p1", connection["providerId"].asText())
        assertEquals("https://partner.test/cb", reconnectSession["returnUrl"].asText())
        assertEquals("x1", reconnectSession["connectionId"].asText())
        assertFalse(reconnectSession.has("providerId"))
        assertEquals("p1", session["providerId"].asText())
        assertFalse(session.has("connectionId"))
    }

    @Test
    fun `single resources are unwrapped from data`() {
        server.reply(201, """{"data":$CLIENT_JSON}""").reply(200, """{"data":$CLIENT_JSON}""")
            .reply(200, """{"data":$CONNECTION_JSON}""").reply(200, """{"data":$ACCOUNT_JSON}""")
        assertEquals(Client("c1", "u1", "u@x.test", "CZ"), bb.clients.create(ClientCreateRequest("u@x.test", "CZ", "u1")))
        assertEquals("c1", bb.clients.get("c1").id)
        assertEquals(
            Connection("x1", "Active", "p1", "2026-10-27T00:00:00Z"),
            bb.client("c1").connections.get("x1"),
        )
        val account = bb.client("c1").accounts.get("a1")
        assertEquals("Active", account.subscriptionStatus)
        assertEquals("1234.56", account.balance?.toPlainString())
    }

    @Test
    fun `fields the SDK does not know yet are ignored`() {
        server.reply(200, """{"data":{"id":"x1","state":"Active","providerId":"p1","consentExpiresAt":null,"futureField":{"a":1}}}""")
        assertEquals("x1", bb.client("c1").connections.get("x1").id)
    }

    @Test
    fun `getByExternalId returns the match or null`() {
        server.reply(200, """{"limit":100,"nextCursor":null,"data":[$CLIENT_JSON]}""").reply(200, EMPTY_PAGE)
        assertEquals(Client("c1", "u1", "u@x.test", "CZ"), bb.clients.getByExternalId("u1"))
        assertNull(bb.clients.getByExternalId("nobody"))
        assertEquals(listOf("u1"), server.requests[0].query("externalId"))
    }

    @Test
    fun `providers list walks every page lazily`() {
        server.reply(200, """{"limit":2,"nextCursor":"c2","data":[{"id":"p1","name":"One"},{"id":"p2","name":"Two"}]}""")
            .reply(200, """{"limit":2,"nextCursor":null,"data":[{"id":"p3","name":"Three"}]}""")
        val providers = bb.providers.list(ListProvidersParams(country = "CZ", search = "bank", limit = 2))
        assertEquals(0, server.requests.size, "nothing is fetched before iteration")
        assertEquals(listOf("p1", "p2", "p3"), providers.map { it.id })
        val (first, second) = server.requests
        assertEquals(listOf("CZ"), first.query("country"))
        assertEquals(listOf("bank"), first.query("search"))
        assertEquals(listOf("2"), first.query("limit"))
        assertEquals(emptyList(), first.query("nextCursor"))
        assertEquals(listOf("c2"), second.query("nextCursor"))
        assertEquals(listOf("CZ"), second.query("country"))
    }

    @Test
    fun `provider pages expose cursors`() {
        server.reply(200, """{"limit":1,"nextCursor":"c2","data":[{"id":"p1","name":"One"}]}""")
            .reply(200, """{"limit":1,"nextCursor":null,"data":[{"id":"p2","name":"Two"}]}""")
        val pages = bb.providers.pages().toList()
        assertEquals(listOf("c2", null), pages.map { it.nextCursor })
        assertEquals(listOf(1, 1), pages.map { it.limit })
    }

    @Test
    fun `listAccounts walks every page`() {
        server.reply(
            200,
            """{"limit":2,"nextCursor":"acc2","data":[{"id":"a1","subscriptionStatus":"Active"},{"id":"a2","subscriptionStatus":"Disabled"}]}""",
        ).reply(200, """{"limit":2,"nextCursor":null,"data":[{"id":"a3","subscriptionStatus":"Active"}]}""")
        assertEquals(listOf("a1", "a2", "a3"), bb.client("c1").connections.listAccounts("x1").map { it.id })
        assertEquals(listOf("acc2"), server.requests[1].query("nextCursor"))
    }

    @Test
    fun `transaction filters are encoded, variableSymbol repeated`() {
        server.reply(200, EMPTY_PAGE)
        bb.client("c1").accounts.transactionPages(
            "a1",
            ListTransactionsParams(
                limit = 5, sort = "amount", order = "asc", dateFrom = "2026-07-01", dateTo = "2026-07-31",
                recordState = "Cleared", variableSymbol = listOf("888", "456"), sinceSeq = 9002, sinceCreatedSeq = 77,
            ),
        ).first()
        val call = server.requests.single()
        assertEquals(listOf("888", "456"), call.query("variableSymbol"))
        assertEquals(listOf("5"), call.query("limit"))
        assertEquals(listOf("amount"), call.query("sort"))
        assertEquals(listOf("asc"), call.query("order"))
        assertEquals(listOf("2026-07-01"), call.query("dateFrom"))
        assertEquals(listOf("2026-07-31"), call.query("dateTo"))
        assertEquals(listOf("Cleared"), call.query("recordState"))
        assertEquals(listOf("9002"), call.query("sinceSeq"))
        assertEquals(listOf("77"), call.query("sinceCreatedSeq"))
    }

    @Test
    fun `unset filters are not sent`() {
        server.reply(200, EMPTY_PAGE)
        bb.client("c1").accounts.transactionPages("a1").first()
        assertNull(server.requests.single().rawQuery?.takeIf { it.isNotEmpty() })
    }

    @Test
    fun `transactions walk pages until nextCursor is null`() {
        val tx = { id: String, seq: Int -> """{"id":"$id","seq":$seq,"createdSeq":$seq,"recordDate":"2026-07-15","amount":"1.00"}""" }
        server.reply(200, """{"limit":1,"nextCursor":"t2","data":[${tx("t1", 1)}]}""")
            .reply(200, """{"limit":1,"nextCursor":"t3","data":[${tx("t2", 2)}]}""")
            .reply(200, """{"limit":1,"nextCursor":null,"data":[${tx("t3", 3)}]}""")
        val ids = bb.client("c1").accounts.transactions("a1", ListTransactionsParams(limit = 1)).map { it.id }
        assertEquals(listOf("t1", "t2", "t3"), ids)
        assertEquals(listOf(listOf("1"), listOf("1"), listOf("1")), server.requests.map { it.query("limit") })
        assertEquals("/v2/accounts/a1/transactions", server.requests[2].path)
    }

    @Test
    fun `revoke and delete accept empty 200 and 204 bodies`() {
        server.reply(200).reply(204).reply(204).reply(200)
        val scope = bb.client("c1")
        scope.connections.revoke("x1")
        scope.connections.delete("x1")
        scope.delete()
        scope.connections.revoke("x1")
        assertEquals(4, server.requests.size)
    }

    @Test
    fun `refresh returns the accepted status`() {
        server.reply(202, """{"status":"Accepted","nextRefreshPossibleAt":"2026-07-15T10:20:00Z"}""")
        assertEquals(RefreshAccepted("Accepted", "2026-07-15T10:20:00Z"), bb.client("c1").connections.refresh("x1"))
    }

    @Test
    fun `path segments are percent-encoded`() {
        server.reply(200, """{"data":$CONNECTION_JSON}""")
        bb.client("c 1").connections.get("x/1")
        val call = server.requests.single()
        assertEquals("/v2/connections/x%2F1", call.path)
        assertEquals("c 1", call.header("X-Client-Id"))
    }

    @Test
    fun `waitForTerminal stops on the first terminal state`() {
        server.reply(200, sessionBody("AwaitingBankSelection")).reply(200, sessionBody("Fetching"))
            .reply(200, sessionBody("Completed", "conn1")).reply(200, sessionBody("Failed"))
        val seen = mutableListOf<String>()
        val done = bb.client("c1").connectSessions.waitForTerminal("s1", 1500, 10) { seen += it.state }
        assertEquals("Completed", done.state)
        assertEquals("conn1", done.connectionId)
        assertEquals(listOf("AwaitingBankSelection", "Fetching", "Completed"), seen)
        assertEquals(3, server.requests.size)
        assertEquals(listOf(1500L, 1500L), sleeper.waits)
    }

    @Test
    fun `every terminal state ends the wait on the first poll`() {
        for (state in listOf("Completed", "Failed", "Cancelled", "Expired")) {
            server.reply(200, sessionBody(state))
            assertEquals(state, bb.client("c1").connectSessions.waitForTerminal("s1").state)
        }
        assertEquals(4, server.requests.size)
        assertEquals(emptyList(), sleeper.waits)
    }

    @Test
    fun `waitForTerminal returns the last session after maxPolls`() {
        server.fallback = Reply(200, sessionBody("Fetching"))
        val last = bb.client("c1").connectSessions.waitForTerminal("s1", pollIntervalMs = 10, maxPolls = 3)
        assertEquals("Fetching", last.state)
        assertEquals(3, server.requests.size)
        assertEquals(listOf(10L, 10L), sleeper.waits)
    }

    @Test
    fun `waitForTerminal needs at least one poll`() {
        assertFailsWith<IllegalArgumentException> { bb.client("c1").connectSessions.waitForTerminal("s1", maxPolls = 0) }
        assertEquals(0, server.requests.size)
    }

    @Test
    fun `defaults and identity`() {
        assertEquals("https://aisp-partner.bbapi.io", BudgetBakers.DEFAULT_BASE_URL)
        assertEquals(SDK_VERSION, BudgetBakers.VERSION)
        assertEquals("c1", bb.client("c1").clientId)
    }

    @Test
    fun `the builder configures the same client`() {
        server.reply(503, errorBody("internal_error")).reply(200, CONFIG_BODY)
        val built = BudgetBakers.builder(API_KEY)
            .baseUrl(server.baseUrl)
            .retryBaseMs(40)
            .maxRetries(1)
            .sleeper(sleeper)
            .random { 0.5 }
            .build()
        built.use { assertEquals("p1", it.partner.getConfig().partnerId) }
        assertEquals(listOf(40L), sleeper.waits)
        assertEquals(API_KEY, server.requests[0].header("X-Api-Key"))
    }

    @Test
    fun `an injected HttpClient carries the requests`() {
        server.reply(200, CONFIG_BODY)
        val http = java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).build()
        BudgetBakers(API_KEY, server.baseUrl, httpClient = http).partner.getConfig()
        assertEquals(1, server.requests.size)
    }

    @Test
    fun `partner config is read as received`() {
        server.reply(200, CONFIG_BODY)
        val config = bb.partner.getConfig()
        assertEquals("sandbox", config.mode)
        assertTrue(config.capabilities.refresh)
        assertFalse(config.capabilities.nonRegulatedProviders)
        assertEquals("v1", config.webhook.signatureVersion)
        assertEquals(listOf("CZ"), config.countries)
    }
}

// The SDK surface: client-scoped ergonomics that hide the X-Client-Id header,
// lazy cursor pagination, automatic Idempotency-Key on creates (explicit
// override supported), and a connect-session polling helper. Reads and creates
// live on /v2; the connection lifecycle actions live on /v1. Every call blocks.

package com.budgetbakers.partner

import java.io.Closeable
import java.util.UUID
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom
import java.util.function.Consumer
import java.util.function.DoubleSupplier

/** Waits between retries and between connect-session polls; injectable for tests. */
fun interface Sleeper {
    fun sleep(millis: Long)

    companion object {
        @JvmField
        val SYSTEM: Sleeper = Sleeper { Thread.sleep(it) }
    }
}

data class ListProvidersParams @JvmOverloads constructor(
    val country: String? = null,
    val search: String? = null,
    val limit: Int? = null,
) {
    class Builder {
        private var country: String? = null
        private var search: String? = null
        private var limit: Int? = null

        fun country(country: String?) = apply { this.country = country }
        fun search(search: String?) = apply { this.search = search }
        fun limit(limit: Int?) = apply { this.limit = limit }
        fun build() = ListProvidersParams(country, search, limit)
    }

    companion object {
        @JvmStatic
        fun builder() = Builder()
    }
}

data class ListTransactionsParams @JvmOverloads constructor(
    val limit: Int? = null,
    /** `recordDate` (default) or `amount`; the id tiebreak makes every sort total. */
    val sort: String? = null,
    /** `asc` or `desc`. */
    val order: String? = null,
    /** Inclusive calendar-date bound (UTC) on recordDate, `YYYY-MM-DD`. */
    val dateFrom: String? = null,
    val dateTo: String? = null,
    /** `Cleared` or `Uncleared`. */
    val recordState: String? = null,
    /** Filter by details.variableSymbol (up to 20 values; leading zeros ignored). */
    val variableSymbol: List<String>? = null,
    /** Delta sync: rows whose seq is greater than this, ordered by seq. Combinable only with limit. */
    val sinceSeq: Long? = null,
    /** Create-only feed: rows whose createdSeq is greater than this. Combinable only with limit. */
    val sinceCreatedSeq: Long? = null,
) {
    class Builder {
        private var limit: Int? = null
        private var sort: String? = null
        private var order: String? = null
        private var dateFrom: String? = null
        private var dateTo: String? = null
        private var recordState: String? = null
        private var variableSymbol: List<String>? = null
        private var sinceSeq: Long? = null
        private var sinceCreatedSeq: Long? = null

        fun limit(limit: Int?) = apply { this.limit = limit }
        fun sort(sort: String?) = apply { this.sort = sort }
        fun order(order: String?) = apply { this.order = order }
        fun dateFrom(dateFrom: String?) = apply { this.dateFrom = dateFrom }
        fun dateTo(dateTo: String?) = apply { this.dateTo = dateTo }
        fun recordState(recordState: String?) = apply { this.recordState = recordState }
        fun variableSymbol(variableSymbol: List<String>?) = apply { this.variableSymbol = variableSymbol }
        fun sinceSeq(sinceSeq: Long?) = apply { this.sinceSeq = sinceSeq }
        fun sinceCreatedSeq(sinceCreatedSeq: Long?) = apply { this.sinceCreatedSeq = sinceCreatedSeq }
        fun build() = ListTransactionsParams(
            limit, sort, order, dateFrom, dateTo, recordState, variableSymbol, sinceSeq, sinceCreatedSeq,
        )
    }

    companion object {
        @JvmStatic
        fun builder() = Builder()
    }
}

private val TERMINAL_STATES = setOf("Completed", "Failed", "Cancelled", "Expired")

/** Everything scoped to one end user (X-Client-Id header). */
class ClientScope internal constructor(
    private val transport: Transport,
    val clientId: String,
) {
    @get:JvmName("connections")
    val connections: Connections = Connections()

    @get:JvmName("accounts")
    val accounts: Accounts = Accounts()

    @get:JvmName("connectSessions")
    val connectSessions: ConnectSessions = ConnectSessions()

    /** Delete this client and cascade to their connections, accounts and transactions. */
    fun delete() {
        transport.requestUnit("DELETE", "/v2/clients/${encodePathSegment(clientId)}", clientId)
    }

    // Lifecycle actions (create, delete, refresh, reconnect, revoke) live on /v1.
    inner class Connections internal constructor() {
        @JvmOverloads
        fun create(providerId: String, idempotencyKey: String? = null): ConnectionCreateResponse =
            transport.request(
                "POST",
                "/v1/connections",
                transport.type(ConnectionCreateResponse::class.java),
                clientId = clientId,
                body = mapOf("providerId" to providerId),
                idempotencyKey = idempotencyKey ?: UUID.randomUUID().toString(),
            )

        /** The stored connection record: state, provider, consent expiry. */
        fun get(connectionId: String): Connection = transport.requestData(
            "GET",
            "/v2/connections/${encodePathSegment(connectionId)}",
            transport.type(Connection::class.java),
            clientId,
        )

        fun delete(connectionId: String) {
            transport.requestUnit("DELETE", "/v1/connections/${encodePathSegment(connectionId)}", clientId)
        }

        fun refresh(connectionId: String): RefreshAccepted = transport.request(
            "POST",
            "/v1/connections/${encodePathSegment(connectionId)}/refresh",
            transport.type(RefreshAccepted::class.java),
            clientId = clientId,
        )

        @JvmOverloads
        fun reconnect(connectionId: String, idempotencyKey: String? = null): ConnectionCreateResponse =
            transport.request(
                "POST",
                "/v1/connections/${encodePathSegment(connectionId)}/reconnect",
                transport.type(ConnectionCreateResponse::class.java),
                clientId = clientId,
                body = emptyMap<String, Any>(),
                idempotencyKey = idempotencyKey ?: UUID.randomUUID().toString(),
            )

        /** Idempotent: revoking an already-Inactive connection is a no-op. */
        fun revoke(connectionId: String) {
            transport.requestUnit("PATCH", "/v1/connections/${encodePathSegment(connectionId)}/revoke", clientId)
        }

        /** Every account of the connection, all pages walked; `Disabled` (unselected) accounts included. */
        fun listAccounts(connectionId: String): List<Account> = accountPages(connectionId).flatMap { it.data }

        /** Page-level iteration when you need cursors or limits; pages are fetched lazily. */
        @JvmOverloads
        fun accountPages(connectionId: String, limit: Int? = null): Iterable<Page<Account>> = pagesOf { cursor ->
            transport.request(
                "GET",
                "/v2/connections/${encodePathSegment(connectionId)}/accounts",
                transport.pageType(Account::class.java),
                clientId = clientId,
                query = listOf("limit" to limit?.toString(), "nextCursor" to cursor),
            )
        }
    }

    inner class Accounts internal constructor() {
        fun get(accountId: String): Account = transport.requestData(
            "GET",
            "/v2/accounts/${encodePathSegment(accountId)}",
            transport.type(Account::class.java),
            clientId,
        )

        /** Every transaction across pages, fetched lazily. */
        @JvmOverloads
        fun transactions(accountId: String, params: ListTransactionsParams = ListTransactionsParams()): Iterable<Transaction> =
            itemsOf(transactionPages(accountId, params))

        @JvmOverloads
        fun transactionPages(
            accountId: String,
            params: ListTransactionsParams = ListTransactionsParams(),
        ): Iterable<Page<Transaction>> = pagesOf { cursor ->
            val query = mutableListOf(
                "limit" to params.limit?.toString(),
                "sort" to params.sort,
                "order" to params.order,
                "dateFrom" to params.dateFrom,
                "dateTo" to params.dateTo,
                "recordState" to params.recordState,
            )
            // Repeated keys (?k=a&k=b), the v2 encoding for list parameters.
            params.variableSymbol?.forEach { query.add("variableSymbol" to it) }
            query.add("sinceSeq" to params.sinceSeq?.toString())
            query.add("sinceCreatedSeq" to params.sinceCreatedSeq?.toString())
            query.add("nextCursor" to cursor)
            transport.request(
                "GET",
                "/v2/accounts/${encodePathSegment(accountId)}/transactions",
                transport.pageType(Transaction::class.java),
                clientId = clientId,
                query = query,
            )
        }
    }

    inner class ConnectSessions internal constructor() {
        /**
         * Create a hosted connect session; open `hostedUrl` in the user's browser.
         * [connectionId] reconnects an existing connection instead of creating one
         * (the bank picker is then skipped, so do not also pass [providerId]).
         */
        @JvmOverloads
        fun create(
            returnUrl: String,
            providerId: String? = null,
            connectionId: String? = null,
            idempotencyKey: String? = null,
        ): ConnectSessionCreateResponse {
            val body = linkedMapOf<String, Any>("returnUrl" to returnUrl)
            if (providerId != null) body["providerId"] = providerId
            if (connectionId != null) body["connectionId"] = connectionId
            return transport.request(
                "POST",
                "/v2/connect-sessions",
                transport.type(ConnectSessionCreateResponse::class.java),
                clientId = clientId,
                body = body,
                idempotencyKey = idempotencyKey ?: UUID.randomUUID().toString(),
            )
        }

        fun get(sessionId: String): ConnectSession = transport.request(
            "GET",
            "/v2/connect-sessions/${encodePathSegment(sessionId)}",
            transport.type(ConnectSession::class.java),
            clientId = clientId,
        )

        /** Poll until the session reaches a terminal state, or return the last session after [maxPolls]. */
        @JvmOverloads
        fun waitForTerminal(
            sessionId: String,
            pollIntervalMs: Long = 2000,
            maxPolls: Int = 150,
            onPoll: Consumer<ConnectSession>? = null,
        ): ConnectSession {
            require(maxPolls >= 1) { "waitForTerminal: maxPolls must be >= 1" }
            var session: ConnectSession? = null
            for (poll in 0 until maxPolls) {
                if (poll > 0) transport.sleeper.sleep(pollIntervalMs)
                val polled = get(sessionId)
                session = polled
                onPoll?.accept(polled)
                if (polled.state in TERMINAL_STATES) return polled
            }
            return session!!
        }
    }
}

/**
 * BudgetBakers Partner API client. Thread-safe; share one instance.
 *
 * ```kotlin
 * val bb = BudgetBakers(apiKey = System.getenv("BB_API_KEY"))
 * ```
 */
class BudgetBakers @JvmOverloads constructor(
    /** Partner API key (bb_test_... or bb_live_...); selects the sandbox or live mode. */
    apiKey: String,
    baseUrl: String = DEFAULT_BASE_URL,
    /** Backoff base for 429/5xx retries. */
    retryBaseMs: Long = 500,
    /** Retries after the initial attempt on 429/5xx. */
    maxRetries: Int = 3,
    httpClient: HttpClient? = null,
    sleeper: Sleeper = Sleeper.SYSTEM,
    /** Jitter source, values in [0, 1). */
    random: DoubleSupplier = SYSTEM_RANDOM,
) : Closeable {
    private val transport: Transport = Transport(
        baseUrl = baseUrl,
        apiKey = apiKey,
        retryBaseMs = retryBaseMs,
        maxRetries = maxRetries,
        http = httpClient ?: HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            // A redirect would re-send X-Api-Key to another host; a 3xx is a typed error.
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build(),
        sleeper = sleeper,
        random = random,
    )

    @get:JvmName("clients")
    val clients: Clients = Clients()

    @get:JvmName("providers")
    val providers: Providers = Providers()

    @get:JvmName("partner")
    val partner: Partner = Partner()

    @get:JvmName("webhooks")
    val webhooks: Webhooks = Webhooks

    /** Scope every client-bound call to one end user. */
    fun client(clientId: String): ClientScope = ClientScope(transport, clientId)

    /** Nothing to release on JDK 17; kept so the client fits try-with-resources. */
    override fun close() {}

    inner class Clients internal constructor() {
        /** Upserts by externalId: an existing externalId returns the existing client. */
        fun create(request: ClientCreateRequest): Client {
            val body = linkedMapOf<String, Any>("email" to request.email, "countryCode" to request.countryCode)
            if (request.externalId != null) body["externalId"] = request.externalId
            return transport.requestData("POST", "/v2/clients", transport.type(Client::class.java), body = body)
        }

        fun get(clientId: String): Client = transport.requestData(
            "GET",
            "/v2/clients/${encodePathSegment(clientId)}",
            transport.type(Client::class.java),
            clientId,
        )

        /** Exact match on your externalId; null when no client carries it. */
        fun getByExternalId(externalId: String): Client? = transport.request<Page<Client>>(
            "GET",
            "/v2/clients",
            transport.pageType(Client::class.java),
            query = listOf("externalId" to externalId),
        ).data.firstOrNull()
    }

    inner class Providers internal constructor() {
        /** Every provider across pages, fetched lazily. */
        @JvmOverloads
        fun list(params: ListProvidersParams = ListProvidersParams()): Iterable<Provider> = itemsOf(pages(params))

        @JvmOverloads
        fun pages(params: ListProvidersParams = ListProvidersParams()): Iterable<Page<Provider>> = pagesOf { cursor ->
            transport.request(
                "GET",
                "/v2/providers",
                transport.pageType(Provider::class.java),
                query = listOf(
                    "country" to params.country,
                    "search" to params.search,
                    "limit" to params.limit?.toString(),
                    "nextCursor" to cursor,
                ),
            )
        }
    }

    inner class Partner internal constructor() {
        /** Capability discovery: self-description of the calling partner and key mode. */
        fun getConfig(): PartnerConfigResponse =
            transport.request("GET", "/v2/partner/config", transport.type(PartnerConfigResponse::class.java))
    }

    class Builder internal constructor(private val apiKey: String) {
        private var baseUrl: String = DEFAULT_BASE_URL
        private var retryBaseMs: Long = 500
        private var maxRetries: Int = 3
        private var httpClient: HttpClient? = null
        private var sleeper: Sleeper = Sleeper.SYSTEM
        private var random: DoubleSupplier = SYSTEM_RANDOM

        fun baseUrl(baseUrl: String) = apply { this.baseUrl = baseUrl }
        fun retryBaseMs(retryBaseMs: Long) = apply { this.retryBaseMs = retryBaseMs }
        fun maxRetries(maxRetries: Int) = apply { this.maxRetries = maxRetries }
        fun httpClient(httpClient: HttpClient) = apply { this.httpClient = httpClient }
        fun sleeper(sleeper: Sleeper) = apply { this.sleeper = sleeper }
        fun random(random: DoubleSupplier) = apply { this.random = random }
        fun build() = BudgetBakers(apiKey, baseUrl, retryBaseMs, maxRetries, httpClient, sleeper, random)
    }

    companion object {
        /** The production edge; the key selects sandbox or live. */
        const val DEFAULT_BASE_URL = "https://aisp-partner.bbapi.io"

        /** This SDK's version, as sent in the User-Agent. */
        @JvmField
        val VERSION: String = SdkVersion.value

        @JvmStatic
        fun builder(apiKey: String) = Builder(apiKey)

        private val SYSTEM_RANDOM = DoubleSupplier { ThreadLocalRandom.current().nextDouble() }
    }
}

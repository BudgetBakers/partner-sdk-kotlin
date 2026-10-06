// Conformance driver (contract-tests PROTOCOL.md v1). Every step goes through
// the SDK's public surface; the driver never issues HTTP itself. Modes:
// probe | scenario | webhooksig | events.

package com.budgetbakers.partner.conformance

import com.budgetbakers.partner.Account
import com.budgetbakers.partner.BudgetBakers
import com.budgetbakers.partner.ClientCreateRequest
import com.budgetbakers.partner.ClientScope
import com.budgetbakers.partner.ListProvidersParams
import com.budgetbakers.partner.ListTransactionsParams
import com.budgetbakers.partner.Money
import com.budgetbakers.partner.PartnerApiException
import com.budgetbakers.partner.UnknownEvent
import com.budgetbakers.partner.WebhookEvent
import com.budgetbakers.partner.WebhookParseError
import com.budgetbakers.partner.Webhooks
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.module.kotlin.kotlinModule
import java.io.File
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import kotlin.system.exitProcess

private val mapper: ObjectMapper = JsonMapper.builder()
    .addModule(kotlinModule())
    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
    .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
    .build()

private val identity: Map<String, Any> = linkedMapOf(
    "lang" to "kotlin",
    "sdk" to "com.budgetbakers:partner-sdk",
    "version" to BudgetBakers.VERSION,
)

private class DriverConfig(val retryBaseMs: Long, val maxRetries: Int, val pollIntervalMs: Long, val maxPolls: Int)

private class UnresolvedVar(val varName: String) : RuntimeException("unresolved var $varName")

private typealias Args = Map<String, JsonNode>

// String(args[key]) in the reference driver: an absent value reads "undefined".
private fun s(args: Args, key: String): String = args[key]?.let(::stringOf) ?: "undefined"

private fun opt(args: Args, key: String): String? = args[key]?.let(::stringOf)

private fun optInt(args: Args, key: String): Int? = args[key]?.takeIf { it.isNumber }?.intValue()

private fun optLong(args: Args, key: String): Long? = args[key]?.takeIf { it.isNumber }?.longValue()

private fun stringOf(node: JsonNode): String = if (node.isValueNode && !node.isNull) node.asText() else node.toString()

private fun money(value: BigDecimal?): String? = value?.let(Money::toDecimalString)

private fun accountView(a: Account): Map<String, Any?> = linkedMapOf(
    "id" to a.id,
    "type" to a.type,
    "balance" to money(a.balance),
    "currencyCode" to a.currencyCode,
    "iban" to a.iban,
)

private fun accountViewV2(a: Account): Map<String, Any?> = linkedMapOf(
    "id" to a.id,
    "balance" to money(a.balance),
    "subscriptionStatus" to a.subscriptionStatus,
)

private fun transactionParams(args: Args): ListTransactionsParams = ListTransactionsParams(
    limit = optInt(args, "limit"),
    sort = opt(args, "sort"),
    order = opt(args, "order"),
    dateFrom = opt(args, "dateFrom"),
    dateTo = opt(args, "dateTo"),
    recordState = opt(args, "recordState"),
    variableSymbol = opt(args, "variableSymbol")?.let { listOf(it) },
    sinceSeq = optLong(args, "sinceSeq"),
    sinceCreatedSeq = optLong(args, "sinceCreatedSeq"),
)

private fun clientCreateRequest(args: Args) =
    ClientCreateRequest(email = s(args, "email"), countryCode = s(args, "countryCode"), externalId = opt(args, "externalId"))

private fun walkTransactions(scope: ClientScope, args: Args): Map<String, Any?> {
    val amounts = mutableListOf<BigDecimal?>()
    val seqs = mutableListOf<Long?>()
    var count = 0
    var pages = 0
    var anyRecurrent = false
    for (page in scope.accounts.transactionPages(s(args, "accountId"), transactionParams(args))) {
        pages += 1
        count += page.data.size
        for (t in page.data) {
            amounts.add(t.amount)
            seqs.add(t.seq)
            if (t.enrichment?.recurrent == true) anyRecurrent = true
        }
    }
    return linkedMapOf(
        "count" to count,
        "pages" to pages,
        "amounts" to amounts.map(::money),
        "seqs" to seqs,
        "sumAmount" to Money.toDecimalString(Money.sum(amounts)),
        "anyRecurrent" to anyRecurrent,
    )
}

private class Ctx(val bb: BudgetBakers, val args: Args, val config: DriverConfig) {
    fun scope(): ClientScope = bb.client(s(args, "clientId"))
}

private val OPS: Map<String, (Ctx) -> Any?> = mapOf(
    "partner.getConfig" to { c -> c.bb.partner.getConfig() },

    "providers.listAll" to { c ->
        val ids = mutableListOf<String>()
        var count = 0
        var pages = 0
        for (page in c.bb.providers.pages(ListProvidersParams(country = opt(c.args, "country"), limit = optInt(c.args, "limit")))) {
            pages += 1
            count += page.data.size
            page.data.mapTo(ids) { it.id }
        }
        linkedMapOf("count" to count, "pages" to pages, "ids" to ids)
    },

    "clients.create" to { c -> c.bb.clients.create(clientCreateRequest(c.args)) },
    "clients.get" to { c -> c.bb.clients.get(s(c.args, "clientId")) },
    "clients.getByExternalId" to { c -> c.bb.clients.getByExternalId(s(c.args, "externalId")) },
    "clients.delete" to { c ->
        c.scope().delete()
        mapOf("deleted" to true)
    },

    "connectSessions.create" to { c ->
        c.scope().connectSessions.create(
            returnUrl = s(c.args, "returnUrl"),
            providerId = opt(c.args, "providerId"),
            connectionId = opt(c.args, "connectionId"),
            idempotencyKey = opt(c.args, "idempotencyKey"),
        )
    },
    "connectSessions.get" to { c -> c.scope().connectSessions.get(s(c.args, "sessionId")) },
    "connectSessions.waitForTerminal" to { c ->
        val states = mutableListOf<String?>()
        val session = c.scope().connectSessions.waitForTerminal(
            s(c.args, "sessionId"),
            c.config.pollIntervalMs,
            c.config.maxPolls,
        ) { states.add(it.state) }
        linkedMapOf(
            "states" to states,
            "state" to session.state,
            "connectionId" to session.connectionId,
            "resultCode" to session.resultCode,
            "error" to session.error,
        )
    },

    "connections.create" to { c ->
        c.scope().connections.create(s(c.args, "providerId"), opt(c.args, "idempotencyKey"))
    },
    "connections.get" to { c -> c.scope().connections.get(s(c.args, "connectionId")) },
    "connections.delete" to { c ->
        c.scope().connections.delete(s(c.args, "connectionId"))
        mapOf("deleted" to true)
    },
    "connections.refresh" to { c ->
        val res = c.scope().connections.refresh(s(c.args, "connectionId"))
        linkedMapOf("status" to res.status, "nextRefreshPossibleAt" to res.nextRefreshPossibleAt)
    },
    "connections.reconnect" to { c ->
        c.scope().connections.reconnect(s(c.args, "connectionId"), opt(c.args, "idempotencyKey"))
    },
    "connections.revoke" to { c ->
        c.scope().connections.revoke(s(c.args, "connectionId"))
        mapOf("revoked" to true)
    },

    "accounts.list" to { c ->
        val accounts = c.scope().connections.listAccounts(s(c.args, "connectionId"))
        linkedMapOf("count" to accounts.size, "accounts" to accounts.map(::accountView))
    },
    "transactions.listAll" to { c ->
        val walk = walkTransactions(c.scope(), c.args)
        linkedMapOf(
            "count" to walk["count"],
            "pages" to walk["pages"],
            "amounts" to walk["amounts"],
            "sumAmount" to walk["sumAmount"],
        )
    },

    // ---- v2-shaped views (same SDK calls, the normalization keeps the v2 fields)

    "clientsV2.create" to { c ->
        val client = c.bb.clients.create(clientCreateRequest(c.args))
        linkedMapOf("id" to client.id, "externalId" to client.externalId)
    },
    "clientsV2.getByExternalId" to { c ->
        val client = c.bb.clients.getByExternalId(s(c.args, "externalId"))
        linkedMapOf("count" to if (client == null) 0 else 1, "ids" to listOfNotNull(client?.id))
    },
    "connectionsV2.get" to { c ->
        val conn = c.scope().connections.get(s(c.args, "connectionId"))
        linkedMapOf("id" to conn.id, "state" to conn.state, "consentExpiresAt" to conn.consentExpiresAt)
    },
    "providersV2.listAll" to { c ->
        val codes = mutableListOf<String?>()
        val statuses = mutableListOf<String?>()
        var count = 0
        var pages = 0
        val params = ListProvidersParams(
            country = opt(c.args, "country"),
            search = opt(c.args, "search"),
            limit = optInt(c.args, "limit"),
        )
        for (page in c.bb.providers.pages(params)) {
            pages += 1
            count += page.data.size
            page.data.mapTo(codes) { it.code }
            page.data.mapTo(statuses) { it.status }
        }
        linkedMapOf("count" to count, "pages" to pages, "codes" to codes, "statuses" to statuses)
    },
    "accountsV2.list" to { c ->
        val accounts = mutableListOf<Account>()
        var pages = 0
        for (page in c.scope().connections.accountPages(s(c.args, "connectionId"), optInt(c.args, "limit"))) {
            pages += 1
            accounts.addAll(page.data)
        }
        linkedMapOf("count" to accounts.size, "pages" to pages, "accounts" to accounts.map(::accountViewV2))
    },
    "accountsV2.get" to { c -> accountViewV2(c.scope().accounts.get(s(c.args, "accountId"))) },
    "transactionsV2.listAll" to { c -> walkTransactions(c.scope(), c.args) },
)

private val VAR_RE = Regex("\\$\\{([A-Za-z0-9_]+)\\}")
private val PATH_TOKEN_RE = Regex("[A-Za-z0-9_]+|\\[\\d+\\]")

private fun interpolateArgs(args: JsonNode?, vars: Map<String, String>): Args {
    val out = linkedMapOf<String, JsonNode>()
    args?.properties()?.forEach { (k, v) ->
        out[k] = if (v.isTextual) {
            mapper.nodeFactory.textNode(
                VAR_RE.replace(v.asText()) { m ->
                    val name = m.groupValues[1]
                    vars[name] ?: throw UnresolvedVar(name)
                },
            )
        } else {
            v
        }
    }
    return out
}

private fun extractPath(value: JsonNode, path: String): JsonNode? {
    var current: JsonNode? = value
    for (token in PATH_TOKEN_RE.findAll(path).map { it.value }) {
        val node = current
        if (node == null || !node.isContainerNode) return null
        current = if (token.startsWith("[")) node.get(token.substring(1, token.length - 1).toInt()) else node.get(token)
    }
    return current
}

private fun emit(document: Any) {
    System.out.write(mapper.writeValueAsString(document).toByteArray(StandardCharsets.UTF_8))
    System.out.flush()
}

private fun scenarioMode(): Int {
    val file = System.getenv("CT_SCENARIO_FILE")
    val baseUrl = System.getenv("CT_BASE_URL")
    val apiKey = System.getenv("CT_API_KEY")
    if (file == null || baseUrl == null || apiKey == null) {
        System.err.println("CT_SCENARIO_FILE, CT_BASE_URL and CT_API_KEY are required")
        return 1
    }
    val fixture = mapper.readTree(File(file))
    val protocolVersion = fixture.path("protocolVersion")
    if (protocolVersion.asInt(-1) != 1) {
        emit(mapOf("unsupported" to "protocolVersion ${protocolVersion.asText()}"))
        return 3
    }
    val driver = fixture.path("driver")
    val rawConfig = driver.path("config")
    val config = DriverConfig(
        retryBaseMs = rawConfig.path("retryBaseMs").asLong(),
        maxRetries = rawConfig.path("maxRetries").asInt(),
        pollIntervalMs = rawConfig.path("pollIntervalMs").asLong(),
        maxPolls = rawConfig.path("maxPolls").asInt(),
    )
    val bb = BudgetBakers(apiKey, baseUrl, config.retryBaseMs, config.maxRetries)
    val vars = linkedMapOf<String, String>()
    fixture.path("vars").properties().forEach { (k, v) -> vars[k] = stringOf(v) }

    val steps = mutableListOf<Map<String, Any?>>()
    for (step in driver.path("steps")) {
        val id = step.path("id").asText()
        val op = step.path("op").asText()
        val fn = OPS[op]
        if (fn == null) {
            emit(mapOf("unsupported" to op))
            return 3
        }
        val entry = linkedMapOf<String, Any?>("id" to id, "op" to op)
        try {
            val args = interpolateArgs(step.get("args"), vars)
            val ok: JsonNode = mapper.valueToTree<JsonNode?>(fn(Ctx(bb, args, config))) ?: NullNode.instance
            entry["ok"] = ok
            step.path("save").properties().forEach { (name, path) ->
                val extracted = extractPath(ok, path.asText())
                if (extracted != null && !extracted.isNull && !extracted.isMissingNode) vars[name] = stringOf(extracted)
            }
        } catch (e: UnresolvedVar) {
            entry.remove("ok")
            entry["skipped"] = "unresolved var ${e.varName}"
        } catch (e: PartnerApiException) {
            entry.remove("ok")
            entry["error"] = linkedMapOf("code" to e.code, "httpStatus" to e.httpStatus, "requestId" to e.requestId)
        } catch (e: Exception) {
            entry.remove("ok")
            entry["crash"] = e.message ?: e.toString()
        }
        steps.add(entry)
    }

    emit(
        linkedMapOf(
            "protocolVersion" to 1,
            "driver" to identity,
            "scenario" to fixture.path("name").asText(),
            "steps" to steps,
        ),
    )
    return 0
}

private fun fixtureFile(): JsonNode? {
    val file = System.getenv("CT_FIXTURE_FILE")
    if (file == null) {
        System.err.println("CT_FIXTURE_FILE is required")
        return null
    }
    return mapper.readTree(File(file))
}

private fun webhooksigMode(): Int {
    val fixture = fixtureFile() ?: return 1
    val results = fixture.path("verifyVectors").map { v ->
        val result = Webhooks.verify(
            v.path("secrets").map { it.asText() },
            v.path("header").asText(),
            v.path("body").asText().toByteArray(StandardCharsets.UTF_8),
            v.path("now").asLong(),
        )
        linkedMapOf("name" to v.path("name").asText(), "result" to result.value)
    }
    emit(linkedMapOf("protocolVersion" to 1, "driver" to identity, "mode" to "webhooksig", "results" to results))
    return 0
}

private fun eventsMode(): Int {
    val fixture = fixtureFile() ?: return 1
    val results = fixture.path("vectors").map { v ->
        val name = v.path("name").asText()
        when (val parsed = Webhooks.parseEvent(v.path("body").asText())) {
            is WebhookEvent -> linkedMapOf(
                "name" to name,
                "kind" to "event",
                "type" to parsed.type.name,
                "eventId" to parsed.eventId,
                "clientId" to parsed.clientId,
                "connectionId" to parsed.connectionId,
                "reasonCode" to parsed.reason?.code,
                "extra" to parsed.extra,
            )
            is UnknownEvent -> linkedMapOf("name" to name, "kind" to "unknown", "type" to parsed.type)
            is WebhookParseError -> linkedMapOf("name" to name, "kind" to "parse_error")
        }
    }
    emit(linkedMapOf("protocolVersion" to 1, "driver" to identity, "mode" to "events", "results" to results))
    return 0
}

fun main(args: Array<String>) {
    val code = try {
        when (val mode = args.firstOrNull()) {
            "probe" -> {
                emit(linkedMapOf<String, Any>("protocolVersion" to 1) + identity)
                0
            }
            "scenario" -> scenarioMode()
            "webhooksig" -> webhooksigMode()
            "events" -> eventsMode()
            else -> {
                emit(mapOf("unsupported" to "mode $mode"))
                3
            }
        }
    } catch (e: Exception) {
        System.err.println(e.toString())
        1
    }
    exitProcess(code)
}

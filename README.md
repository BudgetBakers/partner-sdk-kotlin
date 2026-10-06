# partner-sdk (Kotlin / Java)

Server SDK for the BudgetBakers Partner AISP API, for Kotlin and Java on the
JVM. Java 17 or newer; blocking API, callable from Java without Kotlin
syntax; JSON through Jackson; coordinates `com.budgetbakers:partner-sdk`,
package `com.budgetbakers.partner`. Default base URL:
`https://aisp-partner.bbapi.io` (the API key selects sandbox vs live).

## Install

Until the package is on Maven Central, download the source zip
`partner-sdk-kotlin-<version>.zip` from the
[downloads page](https://aisp-docs.bbapi.io/guide/downloads), unzip it next
to your project and include it as a composite build:

```kotlin
// settings.gradle.kts
includeBuild("../partner-sdk-kotlin")

// build.gradle.kts
dependencies {
    implementation("com.budgetbakers:partner-sdk:<version>")
}
```

Once published, the same `implementation(...)` line resolves from Maven
Central without the `includeBuild`; your code does not change.

## Example

```kotlin
import com.budgetbakers.partner.BudgetBakers
import com.budgetbakers.partner.ClientCreateRequest
import com.budgetbakers.partner.ListTransactionsParams

val bb = BudgetBakers(apiKey = System.getenv("BB_API_KEY"))

// Capability discovery (mode = sandbox|live, decided by the key).
val config = bb.partner.getConfig()

// Clients upsert by externalId.
val client = bb.clients.create(ClientCreateRequest(email = "u42@example.com", countryCode = "CZ", externalId = "user-42"))

// Client-scoped calls hide the X-Client-Id header.
val scope = bb.client(client.id)

// Hosted connect flow: open hostedUrl in the user's browser, then poll.
val session = scope.connectSessions.create(returnUrl = "https://app.example.com/bb-callback")
val done = scope.connectSessions.waitForTerminal(session.sessionId)

// Accounts: every page walked (Disabled, unselected accounts included).
val accounts = scope.connections.listAccounts(done.connectionId!!)

// Lazy cursor pagination; filters and delta sync through ListTransactionsParams.
for (tx in scope.accounts.transactions(accounts[0].id, ListTransactionsParams(sinceSeq = 0))) {
    // tx.amount is a java.math.BigDecimal - money is never a float.
}
```

From Java the namespaces are methods and the options have builders:

```java
BudgetBakers bb = BudgetBakers.builder(System.getenv("BB_API_KEY")).build();
ClientScope scope = bb.client(bb.clients().create(new ClientCreateRequest("u42@example.com", "CZ", "user-42")).getId());
for (Transaction tx : scope.accounts().transactions(accountId, ListTransactionsParams.builder().sinceSeq(0L).build())) {
    BigDecimal amount = tx.getAmount();
}
```

## Webhook verification

```kotlin
// Constant-time verification against ALL active secrets (+-300 s),
// typed events; unknown types pass through, never throw (respond 2xx).
val result = Webhooks.verify(secrets, signatureHeader, rawBody)
if (result == VerifyResult.VALID) {
    when (val event = Webhooks.parseEvent(rawBody)) {
        is WebhookEvent -> { /* event.type, event.connectionId */ }
        is UnknownEvent -> { /* ignore */ }
        is WebhookParseError -> { /* respond 4xx */ }
    }
}
```

Verify over the raw request body bytes, before any JSON parsing.

## Behavior

- **Typed errors** - `PartnerApiException.code` is the stable machine code
  (`error.code`, constants in `ErrorCode`); branch on it, never on messages.
  `requestId` carries the `X-Request-Id` correlation id. A network failure is
  `PartnerApiUnreachable`. Both are unchecked.
- **Retries** - exponential backoff + jitter on 429/5xx honoring
  `Retry-After`; POST retries only under an `Idempotency-Key`
  (auto-UUID on creates, explicit `idempotencyKey` override).
- **Money** - `amount` and `balance` are `BigDecimal` read from the exact
  wire token; `Money.toDecimalString`, `Money.toCents`, `Money.fromCents` and
  `Money.sum` give exact arithmetic and refuse sub-cent amounts rather than
  round them.
- **Nullability** - only `id` is guaranteed on Client/Connection/Account
  payloads (plus `subscriptionStatus` on accounts and `seq`/`createdSeq`/
  `recordDate` on transactions). State and status values are strings, so a
  new server value never breaks parsing.
- **Paths** - reads and creates call `/v2` and unwrap the `{"data": ...}`
  envelope; the connection lifecycle actions (`connections.create/delete/
  refresh/reconnect/revoke`) call `/v1`, where they live today.
  `clients.getByExternalId` returns `null` when nothing matches.
- **Threads** - one `BudgetBakers` instance is safe to share; it is
  `Closeable` for try-with-resources.

## Building

The Gradle wrapper is committed; a JDK (any version Gradle 9.8 runs on) is
all you need. The toolchain resolver downloads a JDK 17 for compilation when
none is installed. The tests read shared fixtures from the BudgetBakers
monorepo, so a standalone copy builds with `assemble`.

```sh
./gradlew assemble
```

## Documentation

Guides and the API reference: <https://aisp-docs.bbapi.io>. Questions:
[integration@budgetbakers.com](mailto:integration@budgetbakers.com).

## Licence

Apache-2.0 (see `LICENSE` and `NOTICE`). Access to the Partner API itself is
governed by the BudgetBakers Partner Terms of Service.

// API models, hand-written from the Partner API v2 reference. The connection
// lifecycle actions (create, refresh, reconnect, revoke, delete) keep their
// v1.1 shapes. Fields the spec does not mark required are nullable; state and
// status values stay strings so a new server value never breaks parsing.
// Amounts are BigDecimal (never a binary float).

package com.budgetbakers.partner

import java.math.BigDecimal

data class Provider @JvmOverloads constructor(
    val id: String,
    val name: String,
    val code: String? = null,
    val countryCode: String? = null,
    val logoUrl: String? = null,
    val bicCodes: List<String>? = null,
    /** Effective visibility for this partner: `Active`, `Inactive`, `Hidden` or `Disabled`. */
    val status: String? = null,
    /** `Api` or `Web`. */
    val mode: String? = null,
    val timeZone: String? = null,
)

data class Page<T> @JvmOverloads constructor(
    val limit: Int,
    /** Opaque keyset cursor; null on the last page. */
    val nextCursor: String? = null,
    val data: List<T> = emptyList(),
)

data class Client @JvmOverloads constructor(
    val id: String,
    val externalId: String? = null,
    val email: String? = null,
    val countryCode: String? = null,
)

data class ClientCreateRequest @JvmOverloads constructor(
    val email: String,
    val countryCode: String,
    /** Your own user id. Re-posting an existing externalId returns the existing client. */
    val externalId: String? = null,
)

data class Connection @JvmOverloads constructor(
    val id: String,
    /** `Pending`, `Active`, `Inactive` or `Disabled`. */
    val state: String? = null,
    val providerId: String? = null,
    val consentExpiresAt: String? = null,
)

data class ConnectionCreateResponse(
    val connectionId: String,
    val redirectUrl: String,
    val expiresAt: String,
)

data class RefreshAccepted @JvmOverloads constructor(
    val status: String,
    val nextRefreshPossibleAt: String? = null,
)

data class ConnectSession @JvmOverloads constructor(
    val sessionId: String,
    /** `AwaitingBankSelection`, `RedirectedToBank`, `Fetching`, `AwaitingAccountSelection`, `Completed`, `Failed`, `Cancelled` or `Expired`. */
    val state: String,
    val connectionId: String? = null,
    /** `Ok`, `Error` or `Cancelled`. */
    val resultCode: String? = null,
    val error: String? = null,
)

data class ConnectSessionCreateResponse(
    /** Opaque, never parse. */
    val sessionId: String,
    /** Open it in the user's browser as-is. */
    val hostedUrl: String,
    val expiresAt: String,
)

data class Account @JvmOverloads constructor(
    val id: String,
    /** `Active` accounts receive transactions; `Disabled` ones were left unselected in the account picker. */
    val subscriptionStatus: String,
    val name: String? = null,
    val type: String? = null,
    val balance: BigDecimal? = null,
    val currencyCode: String? = null,
    val iban: String? = null,
)

data class EnrichmentCategory @JvmOverloads constructor(
    val categoryId: Int,
    val categoryName: String? = null,
    val subcategoryId: Int? = null,
    val subcategoryName: String? = null,
)

data class Merchant @JvmOverloads constructor(
    val id: String? = null,
    val name: String? = null,
    val logoUri: String? = null,
)

/** Null on the transaction when enrichment is disabled for the partner. */
data class Enrichment @JvmOverloads constructor(
    val recurrent: Boolean,
    val category: EnrichmentCategory? = null,
    val merchant: Merchant? = null,
)

data class TransactionDetails @JvmOverloads constructor(
    val variableSymbol: String? = null,
    val constantSymbol: String? = null,
    val specificSymbol: String? = null,
    val transactionCode: String? = null,
    val creditorReference: String? = null,
    val externalCategoryName: String? = null,
    val mccCode: String? = null,
    val others: Map<String, Any?>? = null,
)

data class Transaction @JvmOverloads constructor(
    val id: String,
    /** Change sequence for `sinceSeq` delta sync; re-issued on every server-side modification. */
    val seq: Long,
    /** Insert-order sequence for the `sinceCreatedSeq` create-only feed; never changes. */
    val createdSeq: Long,
    val recordDate: String,
    val amount: BigDecimal? = null,
    val currencyCode: String? = null,
    /** `Cleared` or `Uncleared`. */
    val recordState: String? = null,
    val note: String? = null,
    val counterParty: String? = null,
    val enrichment: Enrichment? = null,
    val details: TransactionDetails? = null,
)

data class PartnerCapabilities(
    val refresh: Boolean,
    val reconnect: Boolean,
    val enrichment: Boolean,
    val autoRevokeAfterCreate: Boolean,
    val nonRegulatedProviders: Boolean,
)

data class WebhookConfig(
    val signatureVersion: String,
)

data class PartnerConfigResponse @JvmOverloads constructor(
    val partnerId: String,
    val name: String,
    /** `sandbox` or `live`, selected by the API key. */
    val mode: String,
    val capabilities: PartnerCapabilities,
    val webhook: WebhookConfig,
    val consentDuration: String? = null,
    val countries: List<String>? = null,
)

// ---- Webhooks (webhooks v2 reference) ----------------------------------------

enum class WebhookEventType {
    AuthenticationStarted,
    AuthenticationSuccess,
    AuthenticationFailed,
    AuthenticationCanceled,
    AccountsFetchingStarted,
    AccountsFetchingSuccess,
    AccountsFetchingFailed,
    TransactionsFetchingStarted,
    TransactionsFetchingSuccess,
    TransactionsFetchingFailed,
    ConnectionCreateSuccess,
    ConnectionCreateFailed,
    ConnectionReconnectSuccess,
    ConnectionReconnectFailed,
    ConnectionRefreshSuccess,
    ConnectionRefreshFailed,
    ConnectionDeleted,
    ConnectionConsentRevoked,
    ConnectionConsentExpired,
}

data class WebhookReason @JvmOverloads constructor(
    /** `consent_expired`, `consent_revoked`, `authentication_failed`, ... kept as sent. */
    val code: String,
    val message: String? = null,
)

/** The result of [Webhooks.parseEvent]: a [WebhookEvent], an [UnknownEvent] or a [WebhookParseError]. */
sealed class ParsedWebhook {
    /** `event`, `unknown` or `parse_error`. */
    abstract val kind: String
}

/** A known lifecycle event; extra top-level fields pass through in [extra]. */
data class WebhookEvent(
    val type: WebhookEventType,
    val eventId: String,
    val clientId: String,
    val connectionId: String,
    val createdAt: String,
    val reason: WebhookReason?,
    /** Any additional top-level fields (e.g. remainingDays). */
    val extra: Map<String, Any?>,
) : ParsedWebhook() {
    override val kind: String get() = "event"
}

/** An event type this SDK version does not know: respond 2xx and ignore. */
data class UnknownEvent(
    val type: String,
    val raw: Map<String, Any?>,
) : ParsedWebhook() {
    override val kind: String get() = "unknown"
}

/** The delivery body was not a JSON object: respond 4xx or alert, never crash. */
data class WebhookParseError(
    val message: String,
) : ParsedWebhook() {
    override val kind: String get() = "parse_error"
}

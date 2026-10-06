// Error model: every error response carries a stable machine-readable
// `error.code`. Branch on the code only, never parse the message (it may
// change without notice).

package com.budgetbakers.partner

/** Stable machine-readable error codes; [PartnerApiException.code] is always one of [KNOWN]. */
object ErrorCode {
    const val VALIDATION_ERROR = "validation_error"
    const val UNAUTHORIZED = "unauthorized"
    const val CAPABILITY_DISABLED = "capability_disabled"
    const val OPERATION_TEMPORARILY_UNAVAILABLE = "operation_temporarily_unavailable"
    const val CONNECTION_NOT_RECOVERABLE = "connection_not_recoverable"
    const val CONSENT_INACTIVE = "consent_inactive"
    const val NOT_FOUND = "not_found"
    const val REFRESH_IN_PROGRESS = "refresh_in_progress"
    const val REFRESH_COOLDOWN = "refresh_cooldown"
    const val REFRESH_QUOTA_EXCEEDED = "refresh_quota_exceeded"
    const val BACKGROUND_REFRESH_NOT_ALLOWED = "background_refresh_not_allowed"
    const val RATE_LIMITED = "rate_limited"
    const val INTERNAL_ERROR = "internal_error"

    @JvmField
    val KNOWN: Set<String> = setOf(
        VALIDATION_ERROR,
        UNAUTHORIZED,
        CAPABILITY_DISABLED,
        OPERATION_TEMPORARILY_UNAVAILABLE,
        CONNECTION_NOT_RECOVERABLE,
        CONSENT_INACTIVE,
        NOT_FOUND,
        REFRESH_IN_PROGRESS,
        REFRESH_COOLDOWN,
        REFRESH_QUOTA_EXCEEDED,
        BACKGROUND_REFRESH_NOT_ALLOWED,
        RATE_LIMITED,
        INTERNAL_ERROR,
    )
}

/** A typed partner API error (a non-2xx response). */
class PartnerApiException @JvmOverloads constructor(
    /** Stable machine code, the only thing to branch on (see [ErrorCode]). */
    val code: String,
    val httpStatus: Int,
    /** Correlation id (X-Request-Id header, else body requestId), for support. */
    val requestId: String?,
    message: String,
    /** Present on refresh_cooldown and refresh_quota_exceeded. */
    val nextRefreshPossibleAt: String? = null,
) : RuntimeException(message)

/** Network-level failure: the API endpoint was not reachable at all. */
class PartnerApiUnreachable(cause: Throwable) : RuntimeException("Partner API endpoint is not reachable", cause)

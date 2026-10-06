// Money is never a float. The partner API serves amounts as decimals with two
// fraction digits, or three when the third digit carries value ("1.005",
// upstream stores amounts at three-decimal precision). The models carry them
// as BigDecimal parsed from the wire token; the helpers below give the
// canonical string and exact cent arithmetic, and refuse sub-cent amounts
// rather than round them.

package com.budgetbakers.partner

import java.math.BigDecimal

private val DECIMAL_RE = Regex("^-?\\d+\\.\\d{2,3}$")
private val TRAILING_ZERO_THIRD = Regex("\\.\\d\\d0$")
private val INT_RE = Regex("^\\d+$")
private val FRAC_RE = Regex("^\\d*$")

object Money {
    /** True for a canonical decimal string: 2 dp, or 3 dp with a non-zero third digit. */
    @JvmStatic
    fun isDecimalString(value: String?): Boolean =
        value != null && DECIMAL_RE.matches(value) && !TRAILING_ZERO_THIRD.containsMatchIn(value)

    /** Normalize a decimal literal (at most 3 fraction digits, no exponent) to 2 dp; a value-carrying third digit is kept. */
    @JvmStatic
    fun toDecimalString(literal: String): String {
        require(!literal.contains('e') && !literal.contains('E')) {
            "amount with exponent notation is not supported: $literal"
        }
        val negative = literal.startsWith("-")
        val bare = if (negative) literal.substring(1) else literal
        val parts = bare.split('.')
        val int = parts[0]
        val frac = parts.getOrElse(1) { "" }
        require(parts.size <= 2 && INT_RE.matches(int) && FRAC_RE.matches(frac) && frac.length <= 3) {
            "not a valid amount literal: $literal"
        }
        val fraction = if (frac.length == 3 && frac.endsWith("0")) frac.substring(0, 2) else frac.padEnd(2, '0')
        return "${if (negative) "-" else ""}$int.$fraction"
    }

    /** The canonical string of [value]; throws when it would need rounding. */
    @JvmStatic
    fun toDecimalString(value: BigDecimal): String {
        // Trailing zeros carry no value, so dropping them is not rounding.
        val exact = if (value.scale() > 3) value.stripTrailingZeros() else value
        return toDecimalString((if (exact.scale() < 0) exact.setScale(0) else exact).toPlainString())
    }

    /** "1234.56" to 123456. Exact and sign-preserving; a sub-cent amount throws. */
    @JvmStatic
    fun toCents(amount: String): Long {
        val canonical = toDecimalString(amount)
        val negative = canonical.startsWith("-")
        val (int, frac) = (if (negative) canonical.substring(1) else canonical).split('.')
        require(frac.length == 2) { "sub-cent amount has no exact cent value: $amount" }
        val cents = Math.addExact(Math.multiplyExact(int.toLong(), 100L), frac.toLong())
        return if (negative) -cents else cents
    }

    @JvmStatic
    fun toCents(amount: BigDecimal): Long = toCents(toDecimalString(amount))

    /** 123456 to 1234.56 (scale 2). */
    @JvmStatic
    fun fromCents(cents: Long): BigDecimal = BigDecimal.valueOf(cents, 2)

    /** Exact sum at scale 2; nulls (absent amounts) are skipped, a sub-cent amount throws. */
    @JvmStatic
    fun sum(amounts: Iterable<BigDecimal?>): BigDecimal {
        var total = 0L
        for (a in amounts) if (a != null) total = Math.addExact(total, toCents(a))
        return fromCents(total)
    }
}

// Money is never a binary float: amounts come off the wire as the exact
// JSON token, and the helpers refuse what they cannot represent instead of
// rounding.

package com.budgetbakers.partner

import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class MoneyTest {

    private val server = StubServer()

    @AfterEach
    fun stop() = server.close()

    private fun tx(id: String, seq: Int, amount: String) =
        """{"id":"$id","seq":$seq,"createdSeq":$seq,"recordDate":"2026-07-15","amount":$amount,"currencyCode":"CZK"}"""

    @Test
    fun `transaction amounts keep the exact JSON token, string or number`() {
        // Raw JSON on purpose: 90071992547409.93 is not representable as a double.
        server.reply(
            200,
            """{"limit":3,"nextCursor":"c2","data":[${tx("t1", 1, "\"0.10\"")},${tx("t2", 2, "0.20")},""" +
                """${tx("t3", 3, "\"90071992547409.93\"")}]}""",
        ).reply(
            200,
            """{"limit":3,"nextCursor":null,"data":[${tx("t4", 4, "90071992547409.93")},${tx("t5", 5, "\"1.005\"")},""" +
                """${tx("t6", 6, "null")}]}""",
        )
        val amounts = stubClient(server).client("c1").accounts.transactions("a1").map { it.amount?.toPlainString() }
        assertEquals(listOf("0.10", "0.20", "90071992547409.93", "90071992547409.93", "1.005", null), amounts)
    }

    @Test
    fun `an account balance keeps the exact token`() {
        server.reply(200, """{"data":{"id":"a1","subscriptionStatus":"Active","balance":90071992547409.93}}""")
        assertEquals("90071992547409.93", stubClient(server).client("c1").accounts.get("a1").balance?.toPlainString())
        server.reply(200, """{"data":{"id":"a1","subscriptionStatus":"Active","balance":null}}""")
        assertNull(stubClient(server).client("c1").accounts.get("a1").balance)
    }

    @Test
    fun `sum of 0_10 and 0_20 is exactly 0_30`() {
        assertEquals("0.30", Money.sum(listOf(BigDecimal("0.10"), BigDecimal("0.20"))).toPlainString())
    }

    @Test
    fun `sum skips nulls and keeps two decimals`() {
        assertEquals("1.50", Money.sum(listOf(BigDecimal("1.5"), null, BigDecimal("0"))).toPlainString())
        assertEquals("0.00", Money.sum(emptyList()).toPlainString())
        assertEquals("-0.10", Money.sum(listOf(BigDecimal("0.10"), BigDecimal("-0.20"))).toPlainString())
        assertEquals(
            "90071992547410.03",
            Money.sum(listOf(BigDecimal("90071992547409.93"), BigDecimal("0.10"))).toPlainString(),
        )
    }

    @Test
    fun `sum refuses a sub-cent amount`() {
        assertFailsWith<IllegalArgumentException> { Money.sum(listOf(BigDecimal("1.005"))) }
    }

    @Test
    fun `toDecimalString pads to two decimals and keeps a value-carrying third`() {
        assertEquals("1.00", Money.toDecimalString("1"))
        assertEquals("1.50", Money.toDecimalString("1.5"))
        assertEquals("-0.10", Money.toDecimalString("-0.1"))
        assertEquals("1234.56", Money.toDecimalString("1234.56"))
        assertEquals("1.005", Money.toDecimalString("1.005"))
        assertEquals("1.00", Money.toDecimalString("1.000"))
        assertEquals("90071992547409.93", Money.toDecimalString("90071992547409.93"))
        assertEquals("0.10", Money.toDecimalString(BigDecimal("0.1")))
        assertEquals("1.005", Money.toDecimalString(BigDecimal("1.005")))
        assertEquals("90071992547409.93", Money.toDecimalString(BigDecimal("90071992547409.93")))
    }

    @Test
    fun `toDecimalString rejects what it cannot represent exactly`() {
        for (bad in listOf("1e5", "1E-2", "1.0001", "abc", "", "1.2.3", "--1", ".5x", " 1.00")) {
            assertFailsWith<IllegalArgumentException>(bad) { Money.toDecimalString(bad) }
        }
        assertFailsWith<IllegalArgumentException> { Money.toDecimalString(BigDecimal("1.0001")) }
    }

    @Test
    fun `isDecimalString accepts the canonical forms only`() {
        for (good in listOf("1.00", "-0.10", "1.005", "90071992547409.93")) assertTrue(Money.isDecimalString(good), good)
        for (bad in listOf(null, "1", "1.0", "1.000", "1.0050", "1e2", "abc", "")) {
            assertFalse(Money.isDecimalString(bad), bad.toString())
        }
    }

    @Test
    fun `cents round-trip exactly`() {
        assertEquals(123456L, Money.toCents("1234.56"))
        assertEquals(-10L, Money.toCents("-0.10"))
        assertEquals(9007199254740993L, Money.toCents("90071992547409.93"))
        assertEquals(10L, Money.toCents(BigDecimal("0.1")))
        assertEquals("1234.56", Money.fromCents(123456L).toPlainString())
        assertEquals("-0.10", Money.fromCents(-10L).toPlainString())
        assertEquals(2, Money.fromCents(500L).scale())
    }

    @Test
    fun `toCents refuses a sub-cent amount`() {
        assertFailsWith<IllegalArgumentException> { Money.toCents("1.005") }
        assertFailsWith<IllegalArgumentException> { Money.toCents(BigDecimal("1.005")) }
    }
}

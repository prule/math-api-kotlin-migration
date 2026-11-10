package com.paulrule.mathapi.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration

/** Ported from the Java `CalculationControllerTest`; every assertion is observable over HTTP. */
class CalculationContractTest {
    // RB-1, RB-3, RB-4
    @ParameterizedTest(name = "{0}")
    @MethodSource("exactResults")
    fun `returns the exact result, normalised`(
        body: String,
        result: String,
    ) {
        val response = calculate(body)

        assertThat(response.status).describedAs(response.body).isEqualTo(200)
        assertThat(response.json()["result"].asText()).isEqualTo(result)
    }

    // RB-5
    @Test
    fun `echoes operands as received in plain notation`() {
        val response = calculate("""{"operation": "ADD", "a": 1e2, "b": "1.50"}""")

        assertThat(response.status).isEqualTo(200)
        assertThat(response.contentType).isEqualTo("application/json")
        assertThat(response.json()).isEqualTo(mapper.readTree("""{"operation": "ADD", "a": "100", "b": "1.50", "result": "101.5"}"""))
    }

    // RB-2, RB-11
    @Test
    fun `rejects an unknown operation listing the supported ones`() {
        assertUnknownOperation(calculate("""{"operation": "NOT_AN_OPERATION", "a": 1, "b": 2}"""), "NOT_AN_OPERATION")
    }

    // RB-2
    @ParameterizedTest
    @ValueSource(strings = ["add", "Add", " ADD"])
    fun `operation names are exact and case sensitive`(name: String) {
        assertProblem(calculate("""{"operation": "$name", "a": 1, "b": 2}"""), 400, "/problems/unknown-operation")
    }

    // RB-1, RB-9
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    fun `rejects an invalid request before looking at the operation`(body: String) {
        assertInvalidRequest(calculate(body))
    }

    // RB-7, RB-11
    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("zeroDivisions")
    fun `rejects division by every zero`(
        a: String,
        zero: String,
    ) {
        assertDomainProblem(
            calculate("""{"operation": "DIVIDE", "a": $a, "b": $zero}"""),
            422,
            "division-by-zero",
            "Division by zero",
            DIVISION_BY_ZERO_DETAIL,
        )
    }

    // RB-6, RB-11
    @ParameterizedTest(name = "b = {0}")
    @MethodSource("operandsOnePastEachLimit")
    fun `rejects an operand out of range without echoing it`(operand: String) {
        val response = calculate("""{"operation": "ADD", "a": 1, "b": "$operand"}""")

        assertDomainProblem(response, 400, "operand-out-of-range", "Operand out of range", outOfRangeDetail("b"))
        assertThat(response.json()["detail"].asText()).doesNotContain(operand)
    }

    // RB-6
    @Test
    fun `accepts operands at the limits`() {
        assertThat(calculate("""{"operation": "ADD", "a": "1E+1000", "b": "1E-1000"}""").status).isEqualTo(200)
        assertThat(calculate("""{"operation": "ADD", "a": "${"9".repeat(100)}", "b": 1}""").status).isEqualTo(200)
    }

    // RB-6
    @Test
    fun `reports the first out-of-range operand`() {
        val response = calculate("""{"operation": "ADD", "a": "1E+1001", "b": "1E-1001"}""")

        assertThat(response.json()["detail"].asText()).isEqualTo(outOfRangeDetail("a"))
    }

    // RB-10
    @Test
    fun `rejects huge exponents immediately`() {
        val response =
            assertTimeoutPreemptively(
                Duration.ofSeconds(1),
                ThrowingSupplier {
                    calculate("""{"operation": "MULTIPLY", "a": "1e999999999", "b": "1e999999999"}""")
                },
            )

        assertProblem(response, 400, "/problems/operand-out-of-range")
    }

    // RB-8
    @Test
    fun `rejects an oversized body stating the limits`() {
        assertInvalidRequest(calculate(validBodyPaddedTo(16_385)))
    }

    // RB-8
    @Test
    fun `accepts a body at the size limit`() {
        assertThat(calculate(validBodyPaddedTo(16_384)).status).isEqualTo(200)
    }

    // RB-8
    @ParameterizedTest(name = "{0}")
    @MethodSource("overlongValues")
    fun `rejects an overlong value while parsing`(
        name: String,
        body: String,
    ) {
        assertInvalidRequest(calculate(body))
    }

    // RB-8, RB-6
    @Test
    fun `parses a string at the length limit, then applies the operand limits`() {
        assertProblem(
            calculate("""{"operation": "ADD", "a": "${"1".repeat(1_000)}", "b": 1}"""),
            400,
            "/problems/operand-out-of-range",
        )
    }

    // RB-9
    @Test
    fun `reports an unknown operation before operand limits`() {
        assertProblem(calculate("""{"operation": "NOPE", "a": "1E+1001", "b": 1}"""), 400, "/problems/unknown-operation")
    }

    // RB-9
    @Test
    fun `reports operand limits before division by zero`() {
        assertProblem(calculate("""{"operation": "DIVIDE", "a": "1E+1001", "b": 0}"""), 400, "/problems/operand-out-of-range")
    }

    // RB-12, RB-22
    @Test
    fun `rejects an unsupported media type`() {
        val response = calculate("ADD 1 2", contentType = "text/plain")

        assertFrameworkProblem(response, 415, "Unsupported Media Type")
        assertThat(response.header("Accept")).contains("application/json")
    }

    // RB-12
    @Test
    fun `rejects an unknown path`() {
        assertFrameworkProblem(get("/api/v1/nope"), 404, "Not Found", "/api/v1/nope")
    }

    // RB-12, RB-23
    @Test
    fun `rejects an unacceptable response type`() {
        val response = calculate(VALID_ADD, accept = "text/plain")

        assertFrameworkProblem(response, 406, "Not Acceptable")
        assertThat(response.header("Accept")).contains("application/json")
    }

    // RB-12, RB-23
    @Test
    fun `rejects an unsupported method`() {
        val response = get(CALCULATIONS)

        assertFrameworkProblem(response, 405, "Method Not Allowed")
        assertThat(response.header("Allow")).isEqualTo("POST")
    }

    companion object {
        @JvmStatic
        fun exactResults() =
            listOf(
                Arguments.of("""{"operation": "ADD", "a": 0.1, "b": 0.2}""", "0.3"),
                Arguments.of("""{"operation": "SUBTRACT", "a": "2", "b": "5.5"}""", "-3.5"),
                Arguments.of("""{"operation": "MULTIPLY", "a": "1.25", "b": "-0.004"}""", "-0.005"),
                Arguments.of(
                    """{"operation": "ADD", "a": "12345678901234567890.12345678901234567890", "b": "0.00000000000000000001"}""",
                    "12345678901234567890.12345678901234567891",
                ),
                Arguments.of("""{"operation": "ADD", "a": "1.50", "b": "1.50"}""", "3"),
                Arguments.of("""{"operation": "SUBTRACT", "a": "1.5", "b": "1.50"}""", "0"),
                Arguments.of("""{"operation": "MULTIPLY", "a": "-7.25", "b": "0"}""", "0"),
                Arguments.of("""{"operation": "MULTIPLY", "a": 1e3, "b": "1"}""", "1000"),
                Arguments.of("""{"operation": "DIVIDE", "a": 1, "b": "3"}""", "0.3333333333333333333333333333333333"),
                Arguments.of("""{"operation": "DIVIDE", "a": 1, "b": 4}""", "0.25"),
                Arguments.of("""{"operation": "DIVIDE", "a": "100", "b": "0.5"}""", "200"),
                Arguments.of(
                    """{"operation": "DIVIDE", "a": "10000000000000000000000000000000015", "b": "10"}""",
                    "1000000000000000000000000000000002",
                ),
            )

        /** Bodies that are not a valid request, whatever the operation (RB-1, RB-9). The golden corpus reuses them. */
        @JvmStatic
        fun invalidBodies() =
            listOf(
                """{"operation": "ADD", "a": 1,""",
                "",
                "[]",
                """{"operation": "ADD", "a": 1}""",
                """{"a": 1, "b": 2}""",
                """{"operation": "ADD", "a": null, "b": 2}""",
                """{"operation": "ADD", "a": "abc", "b": 1}""",
                """{"operation": "ADD", "a": "", "b": 1}""",
                """{"operation": "ADD", "a": true, "b": 1}""",
                """{"operation": "ADD", "a": {}, "b": 1}""",
                """{"operation": "ADD", "a": [1], "b": 1}""",
                """{"operation": "NOPE", "a": 1}""",
            )

        @JvmStatic
        fun operandsOnePastEachLimit() = listOf("1E+1001", "1E-1001", "1" + "0".repeat(100))

        @JvmStatic
        fun zeroDivisions() =
            listOf(
                Arguments.of("1", "0"),
                Arguments.of("1", "\"0.00\""),
                Arguments.of("1", "\"-0\""),
                Arguments.of("1", "\"0E+3\""),
                Arguments.of("0", "0"),
            )

        @JvmStatic
        fun overlongValues(): List<Arguments> {
            val digits = "1".repeat(1_001)
            return listOf(
                Arguments.of("number operand", """{"operation": "ADD", "a": $digits, "b": 1}"""),
                Arguments.of("string operand", """{"operation": "ADD", "a": "$digits", "b": 1}"""),
                Arguments.of("operation", """{"operation": "${"A".repeat(1_001)}", "a": 1, "b": 1}"""),
            )
        }
    }
}

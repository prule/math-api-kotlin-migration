package com.paulrule.mathapi.adapter.inbound.web

import com.fasterxml.jackson.databind.JsonNode
import com.paulrule.mathapi.domain.UnknownOperationException
import com.paulrule.mathapi.module
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration

/** Ported from the Java `CalculationControllerTest` (MockMvc), running the real module in process. */
class CalculationRoutesTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("exactResults")
    fun `returns the exact result, normalised`(
        body: String,
        result: String,
    ) {
        val response = post(body)

        assertThat(response.status).isEqualTo(200)
        assertThat(response.json["result"].asText()).isEqualTo(result)
    }

    @Test
    fun `echoes operands as received in plain notation`() {
        val response = post("""{"operation": "ADD", "a": 1e2, "b": "1.50"}""")

        assertThat(response.status).isEqualTo(200)
        assertThat(response.contentType).isEqualTo("application/json")
        assertThat(response.json).isEqualTo(JSON_MAPPER.readTree("""{"operation": "ADD", "a": "100", "b": "1.50", "result": "101.5"}"""))
    }

    @Test
    fun `rejects an unknown operation listing the supported ones`() {
        val response = post("""{"operation": "NOT_AN_OPERATION", "a": 1, "b": 2}""")

        assertProblem(response, 400, "/problems/unknown-operation")
        assertThat(response.json["detail"].asText()).isEqualTo(UnknownOperationException("NOT_AN_OPERATION").message)
    }

    @Test
    fun `operation names are case sensitive`() {
        assertProblem(post("""{"operation": "add", "a": 1, "b": 2}"""), 400, "/problems/unknown-operation")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
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
        ],
    )
    fun `rejects an invalid request before looking at the operation`(body: String) {
        assertProblem(post(body), 400, "/problems/invalid-request")
    }

    @ParameterizedTest(name = "{0} / {1}")
    @CsvSource("1, 0", "1, '\"0.00\"'", "1, '\"-0\"'", "1, '\"0E+3\"'", "0, 0")
    fun `rejects division by zero`(
        a: String,
        zero: String,
    ) {
        assertProblem(post("""{"operation": "DIVIDE", "a": $a, "b": $zero}"""), 422, "/problems/division-by-zero")
    }

    @ParameterizedTest(name = "b = {0}")
    @MethodSource("operandsOnePastEachLimit")
    fun `rejects an operand out of range without echoing it`(operand: String) {
        val response = post("""{"operation": "ADD", "a": 1, "b": "$operand"}""")

        assertProblem(response, 400, "/problems/operand-out-of-range")
        assertThat(response.json["detail"].asText()).startsWith("Operand b ").doesNotContain(operand)
    }

    @Test
    fun `accepts operands at the limits`() {
        assertThat(post("""{"operation": "ADD", "a": "1E+1000", "b": "1E-1000"}""").status).isEqualTo(200)
        assertThat(post("""{"operation": "ADD", "a": "${"9".repeat(100)}", "b": 1}""").status).isEqualTo(200)
    }

    @Test
    fun `reports the first out-of-range operand`() {
        assertThat(post("""{"operation": "ADD", "a": "1E+1001", "b": "1E-1001"}""").json["detail"].asText()).startsWith("Operand a ")
    }

    @Test
    fun `rejects huge exponents immediately`() {
        val response =
            assertTimeoutPreemptively(
                Duration.ofSeconds(1),
                ThrowingSupplier { post("""{"operation": "MULTIPLY", "a": "1e999999999", "b": "1e999999999"}""") },
            )

        assertProblem(response, 400, "/problems/operand-out-of-range")
    }

    @Test
    fun `rejects an oversized body stating the limits`() {
        val response = post(validBodyPaddedTo(16_385))

        assertProblem(response, 400, "/problems/invalid-request")
        assertThat(response.json["detail"].asText()).contains("16,384 characters").contains("1,000")
    }

    @Test
    fun `accepts a body at the size limit`() {
        assertThat(post(validBodyPaddedTo(16_384)).status).isEqualTo(200)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("overlongValues")
    fun `rejects an overlong value while parsing`(
        name: String,
        body: String,
    ) {
        assertProblem(post(body), 400, "/problems/invalid-request")
    }

    @Test
    fun `parses a string at the length limit, then applies the operand limits`() {
        assertProblem(post("""{"operation": "ADD", "a": "${"1".repeat(1_000)}", "b": 1}"""), 400, "/problems/operand-out-of-range")
    }

    @Test
    fun `reports an unknown operation before operand limits`() {
        assertProblem(post("""{"operation": "NOPE", "a": "1E+1001", "b": 1}"""), 400, "/problems/unknown-operation")
    }

    @Test
    fun `reports operand limits before division by zero`() {
        assertProblem(post("""{"operation": "DIVIDE", "a": "1E+1001", "b": 0}"""), 400, "/problems/operand-out-of-range")
    }

    // No HTTP input is known to reach the catch-all (the operand limits stop every one tried), so it is called directly.
    @Test
    fun `reports an unexpected failure without internals`() {
        val problem = unexpected(IllegalStateException("secret internal state"))

        assertThat(problem.status.value).isEqualTo(500)
        assertThat(problem.type).isEqualTo("about:blank")
        assertThat(problem.title).isNotBlank()
        assertThat(problem.message).isEqualTo("The calculation could not be completed.")
    }

    @Test
    fun `rejects an unsupported media type`() {
        assertProblem(post("ADD 1 2", ContentType.Text.Plain), 415, "about:blank")
    }

    @Test
    fun `rejects an unknown path`() {
        assertProblem(call { get("/api/v1/nope") }, 404, "about:blank", "/api/v1/nope")
    }

    @Test
    fun `rejects an unacceptable response type`() {
        assertProblem(post(VALID, accept = ContentType.Text.Plain), 406, "about:blank")
    }

    @Test
    fun `rejects an unsupported method`() {
        assertProblem(call { get(CALCULATIONS_PATH) }, 405, "about:blank")
    }

    private class Result(
        val status: Int,
        val contentType: String?,
        val body: String,
    ) {
        val json: JsonNode get() = JSON_MAPPER.readTree(body)
    }

    private fun call(request: suspend HttpClient.() -> HttpResponse): Result {
        var result: Result? = null
        testApplication {
            application { module() }
            val response = client.request()
            result = Result(response.status.value, response.headers[HttpHeaders.ContentType], response.bodyAsText())
        }
        return result!!
    }

    private fun post(
        body: String,
        type: ContentType = ContentType.Application.Json,
        accept: ContentType? = null,
    ) = call {
        post(CALCULATIONS_PATH) {
            contentType(type)
            accept?.let { accept(it) }
            setBody(body)
        }
    }

    /** Problem Details shape (RFC 9457) with no class or package name in the detail. */
    private fun assertProblem(
        response: Result,
        status: Int,
        type: String,
        instance: String = CALCULATIONS_PATH,
    ) {
        assertThat(response.status).isEqualTo(status)
        assertThat(response.contentType).startsWith("application/problem+json")
        val body = response.json
        assertThat(body["type"].asText()).isEqualTo(type)
        assertThat(body["status"].asInt()).isEqualTo(status)
        assertThat(body["title"].asText()).isNotBlank()
        assertThat(body["instance"].asText()).isEqualTo(instance)
        assertThat(body["detail"].asText())
            .isNotBlank()
            .doesNotContainPattern("(java|tools|com|org)\\.[a-z]")
            .doesNotContain("Exception")
    }

    companion object {
        private const val VALID = """{"operation": "ADD", "a": 1, "b": 2}"""

        // Pads inside the object so the parser must read every character.
        private fun validBodyPaddedTo(length: Int) = "{" + " ".repeat(length - VALID.length) + VALID.substring(1)

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

        @JvmStatic
        fun operandsOnePastEachLimit() = listOf("1E+1001", "1E-1001", "1" + "0".repeat(100))

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

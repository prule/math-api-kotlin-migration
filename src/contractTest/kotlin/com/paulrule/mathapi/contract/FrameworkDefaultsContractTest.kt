package com.paulrule.mathapi.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Behaviour no Java test asserts. It comes from Spring/Jackson defaults and was found by probing the running Java
 * service (MIGRATION-PLAN.md RB-8a, RB-8b, RB-22 to RB-26). Some of it is a source defect (docs/MIGRATION-LOG.md),
 * pinned so the port is faithful; fixing it is a post-migration backlog item.
 */
class FrameworkDefaultsContractTest {
    // RB-8a (source defect): the limit counts UTF-8 bytes, although the detail says characters.
    @Test
    fun `the body limit counts bytes, not characters`() {
        fun bodyWith(char: Char) =
            "{" + (1..7).joinToString("") { "\"x$it\": \"${char.toString().repeat(900)}\", " } + VALID_ADD.substring(1)

        assertThat(calculate(bodyWith('e')).status).isEqualTo(200) // ~6,400 bytes
        assertInvalidRequest(calculate(bodyWith('€'))) // same ~6,400 characters, ~19,000 bytes
    }

    // RB-8b
    @Test
    fun `unknown extra fields are ignored`() {
        assertThat(calculate("""{"operation": "ADD", "a": 1, "b": 2, "extra": 1}""").json()["result"].asText()).isEqualTo("3")
    }

    // RB-8b (source defect): duplicate keys are not rejected; the last value wins.
    @Test
    fun `the last of duplicate keys wins`() {
        val response = calculate("""{"operation": "ADD", "a": 1, "a": 9, "b": 2}""")

        assertThat(response.json()["a"].asText()).isEqualTo("9")
        assertThat(response.json()["result"].asText()).isEqualTo("11")
    }

    // RB-8b
    @Test
    fun `operand strings are trimmed and accept a plus sign and an exponent`() {
        val response = calculate("""{"operation": "ADD", "a": " +1 ", "b": "1e1"}""")

        assertThat(response.json()).isEqualTo(mapper.readTree("""{"operation": "ADD", "a": "1", "b": "10", "result": "11"}"""))
    }

    // RB-8b (source defect): a number or boolean operation is coerced to a string.
    @ParameterizedTest
    @ValueSource(strings = ["5", "true"])
    fun `a non-string operation is reported as unknown`(operation: String) {
        assertUnknownOperation(calculate("""{"operation": $operation, "a": 1, "b": 2}"""), operation)
    }

    // RB-22
    @ParameterizedTest
    @ValueSource(strings = ["application/json; charset=utf-8", "application/vnd.example+json"])
    fun `accepts JSON media types`(contentType: String) {
        assertThat(calculate(VALID_ADD, contentType = contentType).status).isEqualTo(200)
    }

    // RB-22
    @Test
    fun `accepts a YAML request body`() {
        val response = calculate("operation: ADD\na: 1\nb: 2", contentType = "application/yaml")

        assertThat(response.status).isEqualTo(200)
        assertThat(response.contentType).isEqualTo("application/json")
        assertThat(response.json()["result"].asText()).isEqualTo("3")
    }

    // RB-22
    @Test
    fun `answers in YAML when asked`() {
        val response = calculate(VALID_ADD, accept = "application/yaml")

        assertThat(response.status).isEqualTo(200)
        assertThat(response.contentType).isEqualTo("application/yaml")
        assertThat(response.body).contains("result: \"3\"")
    }

    // RB-22
    @Test
    fun `errors stay Problem Details JSON even when YAML is asked for`() {
        assertProblem(
            calculate("""{"operation": "NOPE", "a": 1, "b": 2}""", accept = "application/yaml"),
            400,
            "/problems/unknown-operation",
        )
    }

    // RB-22
    @Test
    fun `a request with no content type is unsupported`() {
        val response = calculate(VALID_ADD, contentType = null)

        assertFrameworkProblem(response, 415, "Unsupported Media Type")
        assertThat(response.header("Accept")).contains("application/json", "application/yaml", "application/*+json")
    }

    // RB-23
    @Test
    fun `HEAD on the endpoint is not allowed`() {
        val response = send("HEAD", CALCULATIONS)

        assertThat(response.status).isEqualTo(405)
        assertThat(response.header("Allow")).isEqualTo("POST")
    }

    // RB-23
    @Test
    fun `OPTIONS lists the allowed methods`() {
        val response = send("OPTIONS", CALCULATIONS)

        assertThat(response.status).isEqualTo(200)
        assertThat(response.header("Allow")).isEqualTo("POST,OPTIONS")
    }

    // RB-23
    @Test
    fun `a trailing slash is a different path`() {
        val response = send("POST", "$CALCULATIONS/", VALID_ADD, mapOf("Content-Type" to "application/json"))

        assertFrameworkProblem(response, 404, "Not Found", "$CALCULATIONS/")
    }

    // RB-26
    @Test
    fun `sends no CORS headers and no compression`() {
        val response =
            send(
                "POST",
                CALCULATIONS,
                VALID_ADD,
                mapOf(
                    "Content-Type" to "application/json",
                    "Origin" to "https://example.org",
                    "Accept-Encoding" to "gzip",
                ),
            )

        assertThat(response.status).isEqualTo(200)
        assertThat(response.headers.keys.map { it.lowercase() }).noneMatch { it.startsWith("access-control-") }
        assertThat(response.header("Content-Encoding")).isNull()
    }
}

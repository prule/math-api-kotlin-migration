package com.paulrule.mathapi.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
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

    // RB-22; MIGRATION-LOG Accepted difference 1 (no YAML in Kotlin).
    @Test
    fun `a YAML request body is accepted by Java only`() {
        val response = calculate("operation: ADD\na: 1\nb: 2", contentType = "application/yaml")

        if (isJavaReference) {
            assertThat(response.status).isEqualTo(200)
            assertThat(response.contentType).isEqualTo("application/json")
            assertThat(response.json()["result"].asText()).isEqualTo("3")
        } else {
            assertFrameworkProblem(response, 415, "Unsupported Media Type")
        }
    }

    // RB-22; MIGRATION-LOG Accepted difference 1 (no YAML in Kotlin).
    @Test
    fun `a YAML answer is given by Java only`() {
        val response = calculate(VALID_ADD, accept = "application/yaml")

        if (isJavaReference) {
            assertThat(response.status).isEqualTo(200)
            assertThat(response.contentType).isEqualTo("application/yaml")
            assertThat(response.body).contains("result: \"3\"")
        } else {
            assertFrameworkProblem(response, 406, "Not Acceptable")
        }
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
        // MIGRATION-LOG Accepted difference 1: Kotlin's list has no YAML.
        val expected =
            if (isJavaReference) "application/json, application/yaml, application/*+json" else "application/json, application/*+json"
        assertThat(response.header("Accept")).isEqualTo(expected)
    }

    // RB-28: Spring's 415 detail lowercases the type and adds ;charset=UTF-8 when no charset is given (copied, RB-30).
    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        value = [
            "TEXT/PLAIN                        | Content-Type 'text/plain;charset=UTF-8' is not supported.",
            "text/plain; charset=ISO-8859-1    | Content-Type 'text/plain;charset=ISO-8859-1' is not supported.",
            "multipart/form-data; boundary=x   | Content-Type 'multipart/form-data;boundary=x;charset=UTF-8' is not supported.",
            "garbage                           | Could not parse Content-Type.",
            "application/json;charset=bogus    | Could not parse Content-Type.",
            "multipart/form-data; boundary=\"a;b\" | Content-Type 'multipart/form-data;boundary=\"a;b\";charset=UTF-8' is not supported.",
        ],
    )
    fun `the unsupported media type detail follows Spring's format`(
        contentType: String,
        detail: String,
    ) {
        val response = calculate(VALID_ADD, contentType = contentType)

        assertFrameworkProblem(response, 415, "Unsupported Media Type")
        assertThat(response.json()["detail"].asText()).isEqualTo(detail)
    }

    // RB-28
    @ParameterizedTest
    @ValueSource(
        strings = [
            "APPLICATION/JSON",
            "application/json;charset=ISO-8859-1",
            " application/json ",
            "application/json-patch+json",
            "application/problem+json",
        ],
    )
    fun `any JSON-compatible request type is accepted`(contentType: String) {
        assertThat(calculate(VALID_ADD, contentType = contentType).status).isEqualTo(200)
    }

    // RB-27
    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        value = [
            "application/vnd.a+json, application/json             | application/vnd.a+json",
            "application/problem+json                             | application/problem+json",
            "application/json;charset=utf-8                       | application/json;charset=utf-8",
            "text/plain;q=0.9, application/vnd.a+json;q=0.1       | application/vnd.a+json",
            "application/*                                        | application/json",
            "*/*;q=0.1, application/xml                           | application/json",
            "application/json;q=0                                 | application/json",
            "application/*;q=1, application/vnd.c+json            | application/vnd.c+json",
            "','                                                  | application/json",
            "application/json;foo                                 | application/json",
            "APPLICATION/JSON;Charset=UTF-8                       | application/json;charset=UTF-8",
            "application/vnd.a+json;x=\"1,2\"                     | application/vnd.a+json;x=\"1,2\"",
        ],
    )
    fun `the success type is chosen from Accept the way Spring does`(
        accept: String,
        contentType: String,
    ) {
        val response = calculate(VALID_ADD, accept = accept)

        assertThat(response.status).isEqualTo(200)
        assertThat(response.contentType).isEqualTo(contentType)
        assertThat(response.json()["result"].asText()).isEqualTo("3")
    }

    // RB-27: the empty-body 406, like the unknown health group, is not Problem Details.
    @ParameterizedTest
    @ValueSource(strings = ["garbage", "application/json, application/problem+json;q=2", "application/json;charset=bogus"])
    fun `an unparseable Accept is 406 with an empty body`(accept: String) {
        val response = calculate(VALID_ADD, accept = accept)

        assertThat(response.status).isEqualTo(406)
        assertThat(response.body).isEmpty()
    }

    // RB-27, RB-30
    @Test
    fun `no acceptable type is a 406 problem listing the producible types`() {
        val response = calculate(VALID_ADD, accept = "text/*")
        val producible =
            if (isJavaReference) "application/json, application/*+json, application/yaml" else "application/json, application/*+json"

        assertFrameworkProblem(response, 406, "Not Acceptable")
        assertThat(response.json()["detail"].asText()).isEqualTo("Acceptable representations: [$producible].")
        assertThat(response.header("Accept")).isEqualTo(producible)
    }

    // RB-27
    @Test
    fun `errors ignore Accept`() {
        assertInvalidRequest(calculate("[]", accept = "text/plain"))
    }

    // RB-29
    @ParameterizedTest(name = "{0}")
    @MethodSource("strictInvalidBodies")
    fun `JSON is parsed strictly`(body: String) {
        assertInvalidRequest(calculate(body))
    }

    // RB-29
    @Test
    fun `nesting is limited to 500 levels, root included`() {
        fun nested(depth: Int) = "{\"x\": ${"[".repeat(depth)}${"]".repeat(depth)}, " + VALID_ADD.substring(1)

        assertThat(calculate(nested(499)).status).isEqualTo(200)
        assertInvalidRequest(calculate(nested(500)))
    }

    // RB-29
    @Test
    fun `operand strings may start or end with the decimal point`() {
        assertThat(calculate("""{"operation": "ADD", "a": "1.", "b": ".5"}""").json()["result"].asText()).isEqualTo("1.5")
    }

    // RB-8: a large body is answered with the problem, not a dropped connection.
    @Test
    fun `a 1 MiB body is an invalid request`() {
        assertInvalidRequest(calculate(validBodyPaddedTo(1_048_576)))
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

    // RB-27: with an unparseable Accept, Spring can write no body for any response, errors included.
    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        value = [
            "[]                                              | application/json | 400",
            "{\"operation\": \"NOPE\", \"a\": 1, \"b\": 2}       | application/json | 400",
            "{\"operation\": \"DIVIDE\", \"a\": 1, \"b\": 0}     | application/json | 422",
            "x                                               | text/plain       | 415",
        ],
    )
    fun `an unparseable Accept strips the body from errors`(
        body: String,
        contentType: String,
        status: Int,
    ) {
        val response = calculate(body, contentType = contentType, accept = "garbage")

        assertThat(response.status).isEqualTo(status)
        assertThat(response.body).isEmpty()
        assertThat(response.contentType).isNull()
    }

    // RB-28: Spring reports a missing body before a missing content type.
    @Test
    fun `a request with no body and no content type is an invalid request`() {
        assertInvalidRequest(send("POST", CALCULATIONS))
    }

    // RB-28: a declared non-Unicode charset is decoded, as Spring does.
    @Test
    fun `a declared Latin-1 body is decoded as Latin-1`() {
        val response =
            send(
                "POST",
                CALCULATIONS,
                """{"operation": "é", "a": 1, "b": 2}""",
                mapOf("Content-Type" to "application/json;charset=ISO-8859-1"),
                Charsets.ISO_8859_1,
            )

        assertUnknownOperation(response, "é")
    }

    // RB-28; MIGRATION-LOG Accepted difference 2: Java answers a wildcard content type with 500 (source defect 4).
    @ParameterizedTest
    @ValueSource(strings = ["*/*", "application/*", "application/*+json"])
    fun `a wildcard content type is rejected`(contentType: String) {
        val response = calculate(VALID_ADD, contentType = contentType)

        if (isJavaReference) {
            assertThat(response.status).isEqualTo(500)
        } else {
            assertFrameworkProblem(response, 415, "Unsupported Media Type")
        }
    }

    companion object {
        /** Bodies Jackson must reject (RB-29). The golden corpus reuses them. */
        @JvmStatic
        fun strictInvalidBodies() =
            listOf(
                "$VALID_ADD xyz",
                "$VALID_ADD$VALID_ADD",
                "{\"operation\": \"ADD\", /* c */ \"a\": 1, \"b\": 2}",
                "{'operation': 'ADD', 'a': 1, 'b': 2}",
                "{\"operation\": \"ADD\", \"a\": NaN, \"b\": 2}",
                "{\"operation\": \"ADD\", \"a\": 01, \"b\": 2}",
                "null",
                "{\"operation\": null, \"a\": 1, \"b\": 2}",
                "{\"operation\": {}, \"a\": 1, \"b\": 2}",
                "{\"operation\": \"ADD\", \"a\": \"NaN\", \"b\": 2}",
                "{\"operation\": \"ADD\", \"a\": \"0x10\", \"b\": 2}",
                "{\"operation\": \"ADD\", \"a\": \"1_000\", \"b\": 2}",
            )
    }
}

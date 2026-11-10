package com.paulrule.mathapi.contract

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.nio.charset.Charset

/**
 * Compares the service's real responses with those recorded from the Java service, to catch behaviour no test
 * asserts. `./gradlew contractTest -Precord` (against Java only) rewrites `golden/responses.json` instead.
 */
class GoldenResponsesContractTest {
    @TestFactory
    fun `responses match the recorded Java responses`(): List<DynamicTest> {
        val file = File(System.getProperty("goldenFile"))
        if (System.getProperty("record") != null) {
            // The guard must run before the corpus is built: building it calls the service.
            return listOf(DynamicTest.dynamicTest("record responses to ${file.name}") { record(file) })
        }
        val golden = mapper.readTree(file) as ObjectNode
        // Accepted differences replace the Java recording only when judging another service; recordings stay pure Java.
        if (!isJavaReference) golden.setAll<JsonNode>(acceptedDifferences())
        // Every actual response, for diffing a failing run against the recordings (build/golden-actual.json).
        val actual = JsonNodeFactory.instance.objectNode()
        return CORPUS.map { case ->
            DynamicTest.dynamicTest(case.name) {
                val response = capture(case)
                synchronized(actual) {
                    actual.set<JsonNode>(case.name, response)
                    mapper.writerWithDefaultPrettyPrinter().writeValue(File(System.getProperty("goldenActualFile")), actual)
                }
                assertThat(response).describedAs(case.name).isEqualTo(golden[case.name])
            }
        }
    }

    private fun record(file: File) {
        // Recording the Kotlin service would make every later run compare Kotlin with itself.
        check(isJavaReference) { "Refusing to record: $BASE_URL is not the Java reference service (GET /error)" }
        val all = JsonNodeFactory.instance.objectNode()
        CORPUS.forEach { all.set<JsonNode>(it.name, capture(it)) }
        file.parentFile.mkdirs()
        mapper.writerWithDefaultPrettyPrinter().writeValue(file, all)
    }

    private fun capture(case: Case): ObjectNode {
        val response = send(case.method, case.path, case.body, case.headers, case.charset)
        val node = JsonNodeFactory.instance.objectNode()
        node.put("status", response.status)
        for (header in RECORDED_HEADERS) response.header(header)?.let { node.put(header, it.replace(BASE_URL, "{base}")) }
        val text = response.body.replace(BASE_URL, "{base}")
        if (response.contentType.orEmpty().contains("json") && text.isNotEmpty()) {
            val json = mapper.readTree(text)
            // Spring Boot's own error bodies (TRACE) carry a timestamp; it would never replay equal.
            if (json is ObjectNode && json.has("timestamp")) json.put("timestamp", "{timestamp}")
            node.set<JsonNode>("body", json)
        } else {
            node.put("body", text)
        }
        return node
    }

    private fun acceptedDifferences(): ObjectNode {
        val overlay = mapper.readTree(File(System.getProperty("goldenFile")).resolveSibling("accepted-differences.json")) as ObjectNode
        val unknown = overlay.propertyNames() - CORPUS.map { it.name }.toSet()
        check(unknown.isEmpty()) { "accepted-differences.json names cases that are not in the corpus: $unknown" }
        return overlay
    }

    class Case(
        val name: String,
        val method: String,
        val path: String,
        val body: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val charset: Charset = Charsets.UTF_8,
    )

    companion object {
        private val RECORDED_HEADERS = listOf("Content-Type", "Allow", "Accept", "Location")
        private val JSON = mapOf("Content-Type" to "application/json")

        private fun calc(
            name: String,
            body: String,
            headers: Map<String, String> = JSON,
        ) = Case(name, "POST", CALCULATIONS, body, headers)

        // Operand pairs as JSON values: zeros in every form, signs, exponents, each limit and one past it,
        // the DECIMAL128 rounding boundary, and lenient string forms (RB-3 to RB-8b).
        private val OPERANDS =
            listOf(
                "0" to "0",
                "1" to "0",
                "\"0.00\"" to "\"-0\"",
                "\"0E+3\"" to "1",
                "1" to "3",
                "2" to "3",
                "-1.5" to "2.25",
                "\"-7.25\"" to "\"0.004\"",
                "0.1" to "\"0.2\"",
                "1e3" to "\"1E-3\"",
                "\"123.4500\"" to "\"0.0100\"",
                "\"1E+1000\"" to "\"1E-1000\"",
                "\"1E+1001\"" to "1",
                "1" to "\"1E-1001\"",
                "\"${"9".repeat(100)}\"" to "\"${"9".repeat(100)}\"",
                "\"1${"0".repeat(100)}\"" to "1",
                "\"1e999999999\"" to "1",
                "\"10000000000000000000000000000000015\"" to "10",
                "\"10000000000000000000000000000000005\"" to "10",
                "\" +1 \"" to "\"1e1\"",
            )

        val CORPUS: List<Case> by lazy {
            buildList {
                for (operation in operationsFromService()) {
                    for ((a, b) in OPERANDS) {
                        add(calc("$operation a=$a b=$b", """{"operation": "$operation", "a": $a, "b": $b}"""))
                    }
                }
                (
                    CalculationContractTest.invalidBodies() +
                        listOf(
                            """{"operation": "NOPE", "a": "1E+1001", "b": 1}""",
                            """{"operation": "add", "a": 1, "b": 2}""",
                            """{"operation": 5, "a": 1, "b": 2}""",
                            """{"operation": true, "a": 1, "b": 2}""",
                            """{"operation": "ADD", "a": 1, "a": 9, "b": 2}""",
                            """{"operation": "ADD", "a": 1, "b": 2, "extra": 1}""",
                            """{"operation": "ADD", "a": ${"1".repeat(1_001)}, "b": 1}""",
                            """{"operation": "ADD", "a": "${"1".repeat(1_000)}", "b": 1}""",
                            """{"operation": "${"A".repeat(1_001)}", "a": 1, "b": 1}""",
                        )
                ).forEach { add(calc("body $it".take(120), it)) }
                add(calc("body at the size limit", validBodyPaddedTo(16_384)))
                add(calc("body one past the size limit", validBodyPaddedTo(16_385)))

                val ok = VALID_ADD
                add(calc("no content type", ok, emptyMap()))
                add(calc("text/plain", "ADD 1 2", mapOf("Content-Type" to "text/plain")))
                add(calc("json with charset", ok, mapOf("Content-Type" to "application/json; charset=utf-8")))
                add(calc("vendor json", ok, mapOf("Content-Type" to "application/vnd.example+json")))
                add(calc("yaml request", "operation: ADD\na: 1\nb: 2", mapOf("Content-Type" to "application/yaml")))
                add(calc("accept yaml", ok, JSON + ("Accept" to "application/yaml")))
                add(calc("accept yaml, error", """{"operation": "NOPE", "a": 1, "b": 2}""", JSON + ("Accept" to "application/yaml")))
                add(calc("accept text/plain", ok, JSON + ("Accept" to "text/plain")))
                add(
                    calc(
                        "accept problem+json, error",
                        """{"operation": "NOPE", "a": 1, "b": 2}""",
                        JSON + ("Accept" to "application/problem+json"),
                    ),
                )
                // RB-27 to RB-29, probed for item 3.
                for (type in listOf(
                    "TEXT/PLAIN",
                    "text/plain; charset=ISO-8859-1",
                    "application/octet-stream",
                    "image/png",
                    "application/x-www-form-urlencoded",
                    "multipart/form-data; boundary=x",
                    "garbage",
                    "application/xml",
                    "APPLICATION/JSON",
                    "application/json;charset=ISO-8859-1",
                    " application/json ",
                    "application/json;foo=bar",
                    "application/json-patch+json",
                    "application/problem+json",
                )) {
                    add(calc("content type '$type'", ok, mapOf("Content-Type" to type)))
                }
                for (accept in listOf(
                    "application/vnd.foo+json",
                    "application/*",
                    "*/*",
                    "text/plain, application/json;q=0.5",
                    "application/json;q=0",
                    "garbage",
                    "application/json, text/plain",
                    "text/plain;q=0.9, application/vnd.a+json;q=0.1",
                    "application/vnd.a+json, application/json",
                    "application/json;charset=utf-8",
                    "text/*",
                    "application/xml",
                    "*/*;q=0.1, application/xml",
                    "application/json, application/problem+json;q=2",
                )) {
                    add(calc("accept '$accept'", ok, JSON + ("Accept" to accept)))
                }
                add(
                    calc(
                        "accept text/plain, unknown operation",
                        """{"operation": "NOPE", "a": 1, "b": 2}""",
                        JSON + ("Accept" to "text/plain"),
                    ),
                )
                add(calc("accept text/plain, invalid body", "[]", JSON + ("Accept" to "text/plain")))
                (
                    FrameworkDefaultsContractTest.strictInvalidBodies() +
                        listOf(
                            """{"operation": 1.5, "a": 1, "b": 2}""",
                            """{"operation": "ADD", "a": "1.", "b": ".5"}""",
                            """{"operation": "ADD", "a": "١٢", "b": 2}""",
                        )
                ).forEach { add(calc("strict $it".take(120), it)) }
                for (depth in listOf(499, 500)) {
                    add(calc("nesting $depth", "{\"x\": ${"[".repeat(depth)}${"]".repeat(depth)}, " + ok.substring(1)))
                }
                add(calc("body of 1 MiB", validBodyPaddedTo(1_048_576)))

                // Found in item 3's code review (fixed in Kotlin).
                val bad =
                    mapOf(
                        "invalid body" to "[]",
                        "unknown operation" to """{"operation": "NOPE", "a": 1, "b": 2}""",
                        "division by zero" to """{"operation": "DIVIDE", "a": 1, "b": 0}""",
                        "operand out of range" to """{"operation": "ADD", "a": "1E+1001", "b": 2}""",
                    )
                bad.forEach { (name, body) -> add(calc("accept garbage, $name", body, JSON + ("Accept" to "garbage"))) }
                add(calc("accept garbage, text/plain", "x", mapOf("Content-Type" to "text/plain", "Accept" to "garbage")))
                add(Case("accept garbage, GET calculations", "GET", CALCULATIONS, headers = mapOf("Accept" to "garbage")))
                add(Case("accept garbage, GET /api/v1/nope", "GET", "/api/v1/nope", headers = mapOf("Accept" to "garbage")))
                add(calc("content type with quoted ';'", "x", mapOf("Content-Type" to "multipart/form-data; boundary=\"a;b\"")))
                add(calc("accept with quoted ','", ok, JSON + ("Accept" to "application/vnd.a+json;x=\"1,2\"")))
                add(Case("no body, no content type", "POST", CALCULATIONS))
                add(Case("no body, text/plain", "POST", CALCULATIONS, headers = mapOf("Content-Type" to "text/plain")))
                for (accept in listOf(
                    "application/*;q=1, application/vnd.c+json",
                    ",",
                    "application/json;foo",
                    "application/json;charset=bogus",
                    "APPLICATION/JSON;Charset=UTF-8",
                )) {
                    add(calc("accept '$accept'", ok, JSON + ("Accept" to accept)))
                }
                for (type in listOf("application/json;foo", "application/json;charset=bogus", "application/json;Charset=UTF-8")) {
                    add(calc("content type '$type'", ok, mapOf("Content-Type" to type)))
                }
                add(
                    Case(
                        "latin-1 body",
                        "POST",
                        CALCULATIONS,
                        """{"operation": "é", "a": 1, "b": 2}""",
                        mapOf("Content-Type" to "application/json;charset=ISO-8859-1"),
                        Charsets.ISO_8859_1,
                    ),
                )
                // Accepted differences 2-4 (docs/MIGRATION-LOG.md): recorded so the overlay names what Kotlin does instead.
                for (type in listOf("*/*", "application/*", "application/*+json")) {
                    add(
                        calc(
                            "content type '$type'",
                            ok,
                            mapOf("Content-Type" to type),
                        ),
                    )
                }
                add(Case("double slash", "POST", "/$CALCULATIONS", ok, JSON))
                add(Case("path parameter", "POST", "$CALCULATIONS;jsessionid=1", ok, JSON))
                add(
                    Case(
                        "CORS preflight",
                        "OPTIONS",
                        CALCULATIONS,
                        headers =
                            mapOf(
                                "Origin" to "https://example.org",
                                "Access-Control-Request-Method" to "POST",
                            ),
                    ),
                )
                add(Case("TRACE calculations", "TRACE", CALCULATIONS))
                for (method in listOf("GET", "HEAD", "PUT", "DELETE", "OPTIONS")) add(Case("$method calculations", method, CALCULATIONS))
                add(Case("trailing slash", "POST", "$CALCULATIONS/", ok, JSON))
                listOf(
                    "/api/v1/nope",
                    "/",
                    "/actuator",
                    "/actuator/health",
                    "/actuator/health/",
                    "/actuator/health/liveness",
                    "/actuator/health/readiness",
                    "/actuator/health/nope",
                    "/actuator/env",
                    "/actuator/beans",
                    "/actuator/info",
                    "/actuator/metrics",
                    "/swagger-ui.html",
                ).forEach { add(Case("GET $it", "GET", it)) }
            }.also { corpus -> check(corpus.distinctBy { it.name }.size == corpus.size) { "Corpus case names must be unique" } }
        }
    }
}

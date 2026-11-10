package com.paulrule.mathapi.contract

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

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
        val golden = mapper.readTree(file)
        return CORPUS.map { case ->
            DynamicTest.dynamicTest(case.name) {
                assertThat(capture(case)).describedAs(case.name).isEqualTo(golden[case.name])
            }
        }
    }

    private fun record(file: File) {
        // Recording the Kotlin service would make every later run compare Kotlin with itself. Spring Boot's own
        // /error controller answers status 999; no RB row asks Kotlin to copy it, so keep /error out of the corpus.
        val error = get("/error").body
        check(error.contains("\"status\":999")) {
            "Refusing to record: $BASE_URL is not the Java reference service (GET /error was '$error')"
        }
        val all = JsonNodeFactory.instance.objectNode()
        CORPUS.forEach { all.set<JsonNode>(it.name, capture(it)) }
        file.parentFile.mkdirs()
        mapper.writerWithDefaultPrettyPrinter().writeValue(file, all)
    }

    private fun capture(case: Case): ObjectNode {
        val response = send(case.method, case.path, case.body, case.headers)
        val node = JsonNodeFactory.instance.objectNode()
        node.put("status", response.status)
        for (header in RECORDED_HEADERS) response.header(header)?.let { node.put(header, it.replace(BASE_URL, "{base}")) }
        val text = response.body.replace(BASE_URL, "{base}")
        if (response.contentType.orEmpty().contains("json") && text.isNotEmpty()) {
            node.set<JsonNode>("body", mapper.readTree(text))
        } else {
            node.put("body", text)
        }
        return node
    }

    class Case(
        val name: String,
        val method: String,
        val path: String,
        val body: String? = null,
        val headers: Map<String, String> = emptyMap(),
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

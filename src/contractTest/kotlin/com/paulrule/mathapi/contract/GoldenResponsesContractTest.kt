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
        // A multipart/byteranges boundary is random on each response; it would never replay equal.
        val boundary = response.contentType?.substringAfter("boundary=", "")?.ifEmpty { null }
        val node = JsonNodeFactory.instance.objectNode()
        node.put("status", response.status)
        for (header in RECORDED_HEADERS) {
            response.header(header)?.let { node.put(header, normalise(it, boundary)) }
        }
        val text = normalise(response.body, boundary)
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

    /** The base URL and any multipart boundary differ between runs and services, so they are recorded as placeholders. */
    private fun normalise(
        value: String,
        boundary: String?,
    ) = value.replace(BASE_URL, "{base}").let { v -> boundary?.let { v.replace(it, "{boundary}") } ?: v }

    class Case(
        val name: String,
        val method: String,
        val path: String,
        val body: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val charset: Charset = Charsets.UTF_8,
    )

    companion object {
        private val RECORDED_HEADERS =
            listOf(
                "Content-Type",
                "Allow",
                "Accept",
                "Location",
                "Cache-Control",
                "Last-Modified",
                "Accept-Ranges",
                "Content-Range",
                "Content-Disposition",
            )
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
                // Item 4: the actuator, probed on the Java service.
                for (path in listOf(
                    "/actuator/",
                    "/actuator/health/liveness/",
                    "/actuator/health/liveness/x",
                    "/actuator/health/LIVENESS",
                    "/ACTUATOR/HEALTH",
                    "/actuator/health?x=1",
                    "/actuator/health.json",
                )) {
                    add(Case("GET $path", "GET", path))
                }
                for (accept in listOf(
                    "application/json",
                    "application/vnd.spring-boot.actuator.v3+json",
                    "application/vnd.spring-boot.actuator.v2+json",
                    "text/plain",
                    "garbage",
                    "*/*",
                    "application/*",
                    "application/vnd.foo+json",
                    "application/yaml",
                    "application/json;q=0.5, application/vnd.spring-boot.actuator.v2+json",
                    "application/json, application/vnd.spring-boot.actuator.v2+json",
                    "application/json;charset=utf-8",
                )) {
                    add(Case("actuator accept '$accept'", "GET", "/actuator/health", headers = mapOf("Accept" to accept)))
                }
                for (path in listOf("/actuator", "/actuator/health", "/actuator/health/liveness", "/actuator/health/nope")) {
                    for (method in listOf("HEAD", "POST", "PUT", "DELETE", "OPTIONS")) add(Case("$method $path", method, path))
                }
                add(Case("actuator index accept json", "GET", "/actuator", headers = mapOf("Accept" to "application/json")))
                add(Case("actuator index accept text/plain", "GET", "/actuator", headers = mapOf("Accept" to "text/plain")))
                add(
                    Case(
                        "actuator liveness accept json",
                        "GET",
                        "/actuator/health/liveness",
                        headers = mapOf("Accept" to "application/json"),
                    ),
                )
                add(
                    Case(
                        "actuator unknown group accept text/plain",
                        "GET",
                        "/actuator/health/nope",
                        headers =
                            mapOf("Accept" to "text/plain"),
                    ),
                )
                add(Case("actuator unknown group accept garbage", "GET", "/actuator/health/nope", headers = mapOf("Accept" to "garbage")))
                add(Case("actuator POST accept garbage", "POST", "/actuator/health", headers = mapOf("Accept" to "garbage")))
                add(Case("actuator index Host header", "GET", "/actuator", headers = mapOf("Host" to "example.org:9999")))
                add(
                    Case(
                        "actuator index X-Forwarded headers",
                        "GET",
                        "/actuator",
                        headers = mapOf("X-Forwarded-Host" to "proxy.example", "X-Forwarded-Proto" to "https"),
                    ),
                )
                add(Case("GET /actuator//health", "GET", "/actuator//health"))
                add(Case("TRACE /actuator/health", "TRACE", "/actuator/health"))
                // Item 4's code review: quality before specificity (fixed), Host port normalisation (fixed).
                for (accept in listOf(
                    "application/json;q=0.5, */*",
                    "application/json;q=0.8, application/*+json;q=0.9",
                    "application/vnd.spring-boot.actuator.v2+json;q=0.3, application/*;q=0.8",
                )) {
                    add(Case("actuator accept '$accept'", "GET", "/actuator/health", headers = mapOf("Accept" to accept)))
                }
                for (accept in listOf("*/*;q=0.9, application/vnd.a+json;q=0.1", "application/*;q=0.9, application/vnd.a+json;q=0.1")) {
                    add(calc("accept '$accept'", ok, JSON + ("Accept" to accept)))
                }
                for (host in listOf("example.org:80", "example.org:0080", "example.org:", "[::1]:9090")) {
                    add(Case("actuator index Host '$host'", "GET", "/actuator", headers = mapOf("Host" to host)))
                }
                // Item 4's code review: accepted differences 2-4 extended (user decision).
                for (accept in listOf(",,,", "application/json,", ", application/json")) {
                    add(Case("actuator accept '$accept'", "GET", "/actuator/health", headers = mapOf("Accept" to accept)))
                }
                for (path in listOf(
                    "/actuator/health;x=1",
                    "/actuator/health/;x=1",
                    "/actuator/health/liveness%2F",
                )) {
                    add(Case("GET $path", "GET", path))
                }
                add(Case("actuator index Host 'a/b'", "GET", "/actuator", headers = mapOf("Host" to "a/b")))
                for (path in listOf("/actuator/env", "/actuator/")) add(Case("TRACE $path", "TRACE", path))
                // Item 5: the API docs and Swagger UI, probed on the Java service. Large assets: HEAD only.
                for (path in listOf("/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/swagger-config")) {
                    add(Case("GET $path", "GET", path))
                    add(Case("GET $path, Host header", "GET", path, headers = mapOf("Host" to "example.org:9999")))
                    add(Case("GET $path, Host port 80", "GET", path, headers = mapOf("Host" to "example.org:80")))
                    for (method in listOf("HEAD", "POST", "PUT", "OPTIONS")) add(Case("$method $path", method, path))
                    for (accept in listOf(
                        "application/json",
                        "text/plain",
                        "application/yaml",
                        "garbage",
                        "application/vnd.oai.openapi",
                        "*/*",
                    )) {
                        add(Case("GET $path, accept '$accept'", "GET", path, headers = mapOf("Accept" to accept)))
                    }
                }
                add(
                    Case(
                        "GET /v3/api-docs, X-Forwarded headers",
                        "GET",
                        "/v3/api-docs",
                        headers =
                            mapOf("X-Forwarded-Host" to "proxy.example", "X-Forwarded-Proto" to "https"),
                    ),
                )
                for (path in listOf(
                    "/v3/api-docs/",
                    "/v3/api-docs/springdocDefault",
                    "/swagger-ui.html?x=1",
                    "/swagger-ui/",
                    "/swagger-ui",
                )) {
                    add(Case("GET $path", "GET", path))
                }
                for (method in listOf("HEAD", "POST", "OPTIONS")) add(Case("$method /swagger-ui.html", method, "/swagger-ui.html"))
                for (file in listOf("index.html", "swagger-initializer.js", "oauth2-redirect.html", "nope.js", "index.css")) {
                    add(Case("GET /swagger-ui/$file", "GET", "/swagger-ui/$file"))
                }
                for (file in listOf(
                    "swagger-ui-bundle.js",
                    "swagger-ui-standalone-preset.js",
                    "swagger-ui.css",
                    "favicon-32x32.png",
                    "favicon-16x16.png",
                    "oauth2-redirect.js",
                )) {
                    add(Case("HEAD /swagger-ui/$file", "HEAD", "/swagger-ui/$file"))
                }
                for (method in listOf(
                    "HEAD",
                    "POST",
                    "OPTIONS",
                )) {
                    add(Case("$method /swagger-ui/index.html", method, "/swagger-ui/index.html"))
                }
                add(
                    Case(
                        "GET /swagger-ui/index.html, accept text/plain",
                        "GET",
                        "/swagger-ui/index.html",
                        headers =
                            mapOf("Accept" to "text/plain"),
                    ),
                )
                add(
                    Case(
                        "GET /swagger-ui/index.html, accept garbage",
                        "GET",
                        "/swagger-ui/index.html",
                        headers =
                            mapOf("Accept" to "garbage"),
                    ),
                )
                add(Case("GET /swagger-ui/../v3/api-docs", "GET", "/swagger-ui/%2e%2e/v3/api-docs"))
                val cssModified = "Sat, 07 Nov 2026 02:54:12 GMT" // the webjar entry's time, the same jar on both sides
                add(
                    Case(
                        "GET /swagger-ui/swagger-ui.css, If-Modified-Since",
                        "GET",
                        "/swagger-ui/swagger-ui.css",
                        headers =
                            mapOf("If-Modified-Since" to cssModified),
                    ),
                )

                fun ranged(
                    name: String,
                    method: String,
                    file: String,
                    range: String,
                ) = Case(name, method, "/swagger-ui/$file", headers = mapOf("Range" to range))
                add(ranged("GET /swagger-ui/index.css, Range", "GET", "index.css", "bytes=0-9"))
                for (range in listOf(
                    "bytes=500-",
                    "bytes=99999999999-",
                    "bytes=5-2",
                    "bytes=-5",
                    "bytes=0-9,20-29",
                    "items=0-9",
                    "bytes=abc",
                    "bytes=190-",
                )) {
                    add(ranged("GET /swagger-ui/index.css, Range '$range'", "GET", "index.css", range))
                }
                add(ranged("GET /swagger-ui/index.html, Range", "GET", "index.html", "bytes=0-9"))
                add(ranged("HEAD /swagger-ui/index.css, Range", "HEAD", "index.css", "bytes=0-9"))
                // Item 5's code review.
                for (method in listOf("OPTIONS", "POST", "DELETE")) {
                    for (path in listOf("/swagger-ui/nope.js", "/swagger-ui/")) add(Case("$method $path", method, path))
                }
                val css = "/swagger-ui/swagger-ui.css"
                for ((name, value) in listOf(
                    "If-Modified-Since RFC 850" to ("If-Modified-Since" to "Saturday, 07-Nov-26 02:54:12 GMT"),
                    "If-Modified-Since asctime" to ("If-Modified-Since" to "Sat Nov  7 02:54:12 2026"),
                    "If-Modified-Since garbage" to ("If-Modified-Since" to "garbage"),
                    "If-Unmodified-Since older" to ("If-Unmodified-Since" to "Fri, 01 Jan 2021 00:00:00 GMT"),
                    "If-Unmodified-Since equal" to ("If-Unmodified-Since" to cssModified),
                    "If-None-Match" to ("If-None-Match" to "\"x\""),
                )) {
                    add(Case("GET $css, $name", "GET", css, headers = mapOf(value)))
                }
                add(
                    Case(
                        "GET /swagger-ui/index.css, If-Range older",
                        "GET",
                        "/swagger-ui/index.css",
                        headers =
                            mapOf("If-Range" to "Fri, 01 Jan 2021 00:00:00 GMT", "Range" to "bytes=0-4"),
                    ),
                )
                for (range in listOf(
                    "bytes=",
                    "bytes=,",
                    "bytes=0-1,",
                    "bytes=0-150,0-150",
                    "bytes=0-100,101-201",
                    "bytes=" + List(101) { "0-1" }.joinToString(","),
                )) {
                    add(ranged("GET /swagger-ui/index.css, Range '${range.take(40)}'", "GET", "index.css", range))
                }
                for (path in listOf(
                    "/swagger-ui/index.html/",
                    "/swagger-ui/swagger-ui.css/",
                    "/swagger-ui/./index.html",
                    "/swagger-ui//index.html",
                    "/swagger-ui//",
                )) {
                    add(Case("GET $path", "GET", path))
                }
                add(Case("HEAD /swagger-ui/swagger-ui-bundle.js.LICENSE.txt", "HEAD", "/swagger-ui/swagger-ui-bundle.js.LICENSE.txt"))
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

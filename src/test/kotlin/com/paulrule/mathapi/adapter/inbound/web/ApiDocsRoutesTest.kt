package com.paulrule.mathapi.adapter.inbound.web

import com.fasterxml.jackson.databind.JsonNode
import com.paulrule.mathapi.domain.Operation
import com.paulrule.mathapi.module
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** Ported from the Java `ApiDocumentationTest`, in process; the contract suite compares the text with the Java service's. */
class ApiDocsRoutesTest {
    private val docs: JsonNode = openApiDocument("http://localhost")
    private val calculate = docs.at("/paths/~1api~1v1~1calculations/post")

    @Test
    fun `describes OpenAPI 3_1 with the server it is asked from`() {
        assertThat(docs["openapi"].asText()).startsWith("3.1")
        assertThat(openApiDocument("http://example.org:9999")["servers"][0]["url"].asText()).isEqualTo("http://example.org:9999")
    }

    @Test
    fun `enumerates exactly the supported operations in order`() {
        assertThat(docs.at("/components/schemas/CalculationRequest/properties/operation/enum").map { it.asText() })
            .containsExactlyElementsOf(Operation.entries.map { it.name })
    }

    @Test
    fun `documents exactly the statuses the endpoint returns`() {
        assertThat(calculate["responses"].fieldNames().asSequence().toList()).containsExactlyInAnyOrder("200", "400", "415", "422")
    }

    // The examples are built by the code that builds the responses; this checks the wiring end to end.
    @ParameterizedTest(name = "{1}")
    @CsvSource(
        delimiter = '|',
        value = [
            """400 | invalid-request      | {"operation": "ADD", "a": "abc", "b": 1}""",
            """400 | unknown-operation    | {"operation": "NOT_AN_OPERATION", "a": 1, "b": 2}""",
            """400 | operand-out-of-range | {"operation": "ADD", "a": 1, "b": "1E+1001"}""",
            """422 | division-by-zero     | {"operation": "DIVIDE", "a": 1, "b": 0}""",
        ],
    )
    fun `documented example is the real response`(
        status: String,
        slug: String,
        request: String,
    ) {
        var actual: JsonNode? = null
        testApplication {
            application { module() }
            actual =
                JSON_MAPPER.readTree(
                    client
                        .post(CALCULATIONS_PATH) {
                            contentType(ContentType.Application.Json)
                            setBody(request)
                        }.bodyAsText(),
                )
        }

        assertThat(actual).isEqualTo(calculate.at("/responses/$status/content/application~1problem+json/examples/$slug/value"))
    }

    @Test
    fun `serves the YAML and the Swagger UI entry point`() {
        testApplication {
            application { module() }
            val yaml = client.get("/v3/api-docs.yaml")
            val redirect = createClient { followRedirects = false }.get("/swagger-ui.html")

            assertThat(yaml.headers[HttpHeaders.ContentType]).isEqualTo("application/vnd.oai.openapi")
            assertThat(yaml.bodyAsText()).startsWith("openapi: 3.1.0\n")
            assertThat(redirect.status.value).isEqualTo(302)
            assertThat(redirect.headers[HttpHeaders.Location]).isEqualTo("/swagger-ui/index.html")
        }
    }
}

/** The Swagger UI range rules in process (Spring's guards included); the contract suite compares them with Java. */
class SwaggerUiRangesTest {
    private fun range(value: String): Pair<Int, String?> {
        var result: Pair<Int, String?>? = null
        testApplication {
            application { module() }
            val response = client.get("/swagger-ui/index.css") { header(HttpHeaders.Range, value) }
            result = response.status.value to response.headers[HttpHeaders.ContentRange]
        }
        return result!!
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        value = [
            "bytes=0-9        | 206 | bytes 0-9/202",
            "bytes=-5         | 206 | bytes 197-201/202",
            "bytes=0-9,20-29  | 206 |",
            "bytes=500-       | 416 | bytes */202",
            "bytes=5-2        | 416 | bytes */202",
            "bytes=0-150,0-150 | 416 | bytes */202",
            "items=0-9        | 416 | bytes */202",
        ],
    )
    fun `answers ranges as Spring does`(
        value: String,
        status: Int,
        contentRange: String?,
    ) {
        assertThat(range(value)).isEqualTo(status to contentRange)
    }

    @Test
    fun `refuses more than 100 ranges`() {
        assertThat(range("bytes=" + List(101) { "0-1" }.joinToString(",")).first).isEqualTo(416)
    }
}

package com.paulrule.mathapi.contract

import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** Ported from the Java `ApiDocumentationTest`. Black-box, so the operation list comes from the service, not domain code. */
class ApiDocumentationContractTest {
    private val docs: JsonNode by lazy { get("/v3/api-docs").json() }
    private val calculate: JsonNode get() = docs.at("/paths/~1api~1v1~1calculations/post")

    // RB-18
    @Test
    fun `serves an OpenAPI 3_1 description`() {
        assertThat(docs["openapi"].asText()).startsWith("3.1")
    }

    // RB-18
    @Test
    fun `serves the interactive UI`() {
        val first = get("/swagger-ui.html")
        val target = first.header("Location")

        assertThat(if (target == null) first.status else get(target).status).isEqualTo(200)
    }

    // RB-25
    @Test
    fun `serves the same description as YAML`() {
        val response = get("/v3/api-docs.yaml")

        assertThat(response.status).isEqualTo(200)
        assertThat(response.contentType).startsWith("application/vnd.oai.openapi")
        assertThat(response.body).startsWith("openapi: 3.1")
    }

    // RB-19, RB-2: the docs and the unknown-operation detail list the same operations in the same order.
    @Test
    fun `enumerates exactly the supported operations in order`() {
        val detail = calculate("""{"operation": "x", "a": 1, "b": 2}""").json()["detail"].asText()
        val listed = detail.substringAfter("Supported operations: ").removeSuffix(".").split(", ")

        assertThat(supportedOperations()).isNotEmpty().containsExactlyElementsOf(listed)
    }

    // RB-19
    @Test
    fun `accepts operands as number or string`() {
        for (operand in listOf("a", "b")) {
            assertThat(docs.at("/components/schemas/CalculationRequest/properties/$operand/type").map { it.asText() })
                .containsExactlyInAnyOrder("number", "string")
        }
        assertThat(docs.at("/components/schemas/CalculationRequest/required").map { it.asText() })
            .containsExactlyInAnyOrder("operation", "a", "b")
    }

    // RB-20
    @Test
    fun `documents the success response as JSON strings`() {
        assertThat(calculate.at("/responses/200/content/application~1json/schema/\$ref").asText())
            .isEqualTo("#/components/schemas/CalculationResponse")
        assertThat(calculate.at("/responses/200/content").propertyNames()).containsExactly("application/json")
        val properties = docs.at("/components/schemas/CalculationResponse/properties")
        assertThat(properties.size()).isEqualTo(4)
        assertThat(properties.map { it["type"].asText() }).containsOnly("string")
    }

    // RB-20
    @Test
    fun `documents exactly the statuses the endpoint returns`() {
        assertThat(calculate["responses"].propertyNames()).containsExactlyInAnyOrder("200", "400", "415", "422")
    }

    // RB-20
    @Test
    fun `problem schema has only wire fields`() {
        assertThat(docs.at("/components/schemas/ProblemDetail/properties").propertyNames())
            .containsExactlyInAnyOrder("type", "title", "status", "detail", "instance")
    }

    // RB-21
    @Test
    fun `documents every Problem Details response`() {
        for (status in listOf("400", "415", "422")) {
            assertThat(calculate.at("/responses/$status/content").propertyNames()).containsExactly("application/problem+json")
        }
        assertThat(exampleTypes("400"))
            .containsExactlyInAnyOrder("/problems/invalid-request", "/problems/unknown-operation", "/problems/operand-out-of-range")
        assertThat(exampleTypes("422")).containsExactly("/problems/division-by-zero")
    }

    // RB-21
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
        type: String,
        request: String,
    ) {
        val example = calculate.at("/responses/$status/content/application~1problem+json/examples/$type/value")
        val response = calculate(request)

        assertThat(response.status).isEqualTo(status.toInt())
        for (field in listOf("type", "title", "status", "detail", "instance")) {
            assertThat(response.json()[field]).describedAs(field).isEqualTo(example[field])
        }
    }

    private fun exampleTypes(status: String) =
        calculate.at("/responses/$status/content/application~1problem+json/examples").map { it["value"]["type"].asText() }
}

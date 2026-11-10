package com.paulrule.mathapi.adapter.inbound.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.paulrule.mathapi.domain.DivisionByZeroException
import com.paulrule.mathapi.domain.OperandOutOfRangeException
import com.paulrule.mathapi.domain.Operation
import com.paulrule.mathapi.domain.UnknownOperationException
import io.ktor.server.routing.Route

/**
 * The Java service's springdoc output without the three parts springdoc and its customisers compute at runtime:
 * `servers`, the operation enum and the error examples. [openApiDocument] puts them back.
 */
private val TEMPLATE = JSON_MAPPER.readTree(object {}.javaClass.getResource("/openapi/openapi.json")) as ObjectNode

/** swagger-core's YAML settings, so the text matches the Java service's byte for byte. */
private val YAML =
    YAMLMapper(
        YAMLFactory
            .builder()
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .enable(YAMLGenerator.Feature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS)
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .build(),
    )

private val OPENAPI_YAML = MediaType("application", "vnd.oai.openapi")

/** `/v3/api-docs` (JSON), `/v3/api-docs.yaml` and the Swagger UI's config. */
fun Route.apiDocsRoutes() {
    readOnlyResource("/v3/api-docs", listOf(MediaType.JSON)) { call -> openApiDocument(baseUrl(call)) }
    readOnlyResource("/v3/api-docs.yaml", listOf(OPENAPI_YAML)) { call -> YAML.writeValueAsString(openApiDocument(baseUrl(call))) }
    readOnlyResource("/v3/api-docs/swagger-config", listOf(MediaType.JSON)) { call ->
        linkedMapOf(
            "configUrl" to "/v3/api-docs/swagger-config",
            "oauth2RedirectUrl" to "${baseUrl(call)}/swagger-ui/oauth2-redirect.html",
            "url" to "/v3/api-docs",
            "validatorUrl" to "",
        )
    }
}

/** The whole OpenAPI document served at [baseUrl], with its runtime parts filled in as the Java customisers do. */
internal fun openApiDocument(baseUrl: String): ObjectNode {
    val doc = JSON_MAPPER.createObjectNode()
    TEMPLATE.properties().forEach { (name, value) ->
        doc.set<JsonNode>(name, value.deepCopy())
        if (name == "info") {
            doc
                .putArray("servers")
                .addObject()
                .put("url", baseUrl)
                .put("description", "Generated server url")
        }
    }
    // Domain values come from the domain, so the docs never drift (Java OpenApiConfig).
    val operation = doc.at("/components/schemas/CalculationRequest/properties/operation") as ObjectNode
    operation.putArray("enum").apply { Operation.entries.forEach { add(it.name) } }
    // One example per problem, built by the code that builds the real response (Java ProblemExamples).
    doc.problemContent("400").set<JsonNode>(
        "examples",
        examples(invalidRequest(), problemFor(UnknownOperationException("NOT_AN_OPERATION")), problemFor(OperandOutOfRangeException("b"))),
    )
    doc.problemContent("422").set<JsonNode>("examples", examples(problemFor(DivisionByZeroException())))
    return doc
}

private fun ObjectNode.problemContent(status: String) =
    at("/paths/~1api~1v1~1calculations/post/responses/$status/content/application~1problem+json") as ObjectNode

/** Each problem as an example named by its slug, with `instance` set as Spring sets it for the real response. */
private fun examples(vararg problems: ProblemException): ObjectNode {
    val examples = JSON_MAPPER.createObjectNode()
    problems.forEach {
        examples.putObject(it.type.substringAfterLast('/')).set<JsonNode>("value", JSON_MAPPER.valueToTree(it.toBody(CALCULATIONS_PATH)))
    }
    return examples
}

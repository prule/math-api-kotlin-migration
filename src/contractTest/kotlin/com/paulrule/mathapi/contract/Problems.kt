package com.paulrule.mathapi.contract

import org.assertj.core.api.Assertions.assertThat

// RB-11: the fixed domain error texts. Operation names are never typed here (docs/STANDARDS.md §5: adding an
// operation must not edit a test).
const val INVALID_REQUEST_DETAIL =
    "The request body must be JSON with operation, a and b; a and b must be decimal numbers." +
        " The body may be at most 16,384 characters, and no value more than 1,000."
const val DIVISION_BY_ZERO_DETAIL = "Division by zero is undefined."

fun outOfRangeDetail(operand: String) = "Operand $operand must have at most 100 significant digits and a scale between -1000 and 1000."

/**
 * The exact shape of the unknown-operation detail. The list itself is checked against the API docs only in
 * ApiDocumentationContractTest, so the calculation tests don't need the docs (item 3 lands before item 5).
 */
fun unknownOperationDetailPattern(name: String) = "\\QUnknown operation '$name'. Supported operations: \\E[A-Z_]+(, [A-Z_]+)*\\."

/** The operations in declaration order, as the unknown-operation detail lists them (RB-2). */
fun operationsFromService(): List<String> =
    calculate("""{"operation": "x", "a": 1, "b": 2}""")
        .json()["detail"]
        .asText()
        .substringAfter("Supported operations: ")
        .removeSuffix(".")
        .split(", ")

/** The operations in declaration order, as the API docs enumerate them (RB-19). */
fun supportedOperations(): List<String> =
    get("/v3/api-docs").json().at("/components/schemas/CalculationRequest/properties/operation/enum").map { it.asText() }

/**
 * Problem Details shape (RFC 9457; RB-11, RB-12, RB-14), with no class or package name in the detail.
 * [instance] is the request path the problem must name.
 */
fun assertProblem(
    response: Response,
    status: Int,
    type: String,
    instance: String = CALCULATIONS,
) {
    assertThat(response.status).describedAs(response.body).isEqualTo(status)
    assertThat(response.contentType).startsWith("application/problem+json")
    val body = response.json()
    assertThat(body["type"].asText()).isEqualTo(type)
    assertThat(body["status"].asInt()).isEqualTo(status)
    assertThat(body["title"].asText()).isNotBlank()
    assertThat(body["instance"].asText()).isEqualTo(instance)
    assertThat(body["detail"].asText())
        .isNotBlank()
        .doesNotContainPattern("(java|tools|com|org)\\.[a-z]")
        .doesNotContain("Exception")
}

/** [assertProblem] plus the exact title and detail of a domain error (RB-11). */
fun assertDomainProblem(
    response: Response,
    status: Int,
    slug: String,
    title: String,
    detail: String,
) {
    assertProblem(response, status, "/problems/$slug")
    assertThat(response.json()["title"].asText()).isEqualTo(title)
    assertThat(response.json()["detail"].asText()).isEqualTo(detail)
}

fun assertInvalidRequest(response: Response) =
    assertDomainProblem(response, 400, "invalid-request", "Invalid request", INVALID_REQUEST_DETAIL)

/** An unknown-operation problem for [name], with the detail's exact shape (RB-2, RB-11). */
fun assertUnknownOperation(
    response: Response,
    name: String,
) {
    assertProblem(response, 400, "/problems/unknown-operation")
    assertThat(response.json()["title"].asText()).isEqualTo("Unknown operation")
    assertThat(response.json()["detail"].asText()).matches(unknownOperationDetailPattern(name))
}

/** Framework errors (RB-12): `about:blank` and the HTTP reason phrase as title. Spring's own detail wording is not pinned. */
fun assertFrameworkProblem(
    response: Response,
    status: Int,
    title: String,
    instance: String = CALCULATIONS,
) {
    assertProblem(response, status, "about:blank", instance)
    assertThat(response.json()["title"].asText()).isEqualTo(title)
}

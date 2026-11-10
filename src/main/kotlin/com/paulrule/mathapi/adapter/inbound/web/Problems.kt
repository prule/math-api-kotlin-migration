package com.paulrule.mathapi.adapter.inbound.web

import com.paulrule.mathapi.domain.DivisionByZeroException
import com.paulrule.mathapi.domain.OperandOutOfRangeException
import com.paulrule.mathapi.domain.UnknownOperationException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import org.slf4j.LoggerFactory

/** RFC 9457 Problem Details as the Java service writes them. */
data class Problem(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String,
    val instance: String,
)

/** An error the adapter answers with a fixed problem (and optional response headers), never with internals. */
internal class ProblemException(
    val status: HttpStatusCode,
    val type: String,
    val title: String,
    override val message: String,
    val headers: Map<String, String> = emptyMap(),
) : RuntimeException(message)

private val log = LoggerFactory.getLogger("com.paulrule.mathapi.adapter.inbound.web.Problems")
private val PROBLEM_JSON = ContentType("application", "problem+json")
private const val ABOUT_BLANK = "about:blank"

internal const val INVALID_REQUEST_DETAIL =
    "The request body must be JSON with operation, a and b; a and b must be decimal numbers." +
        " The body may be at most 16,384 characters, and no value more than 1,000."

/** A domain error: `type` is `/problems/<slug>`, and the detail is the domain exception's message (RB-11). */
internal fun domainProblem(
    status: HttpStatusCode,
    slug: String,
    title: String,
    detail: String,
) = ProblemException(status, "/problems/$slug", title, detail)

internal fun invalidRequest() = domainProblem(HttpStatusCode.BadRequest, "invalid-request", "Invalid request", INVALID_REQUEST_DETAIL)

internal fun frameworkProblem(
    status: HttpStatusCode,
    detail: String,
    headers: Map<String, String> = emptyMap(),
) = ProblemException(status, ABOUT_BLANK, status.description, detail, headers)

/** The catch-all: logs the failure and answers with a fixed detail, so no internals reach the client (RB-13). */
internal fun unexpected(cause: Throwable): ProblemException {
    log.error("Unexpected failure", cause)
    return frameworkProblem(HttpStatusCode.InternalServerError, "The calculation could not be completed.")
}

internal suspend fun ApplicationCall.respondProblem(problem: ProblemException) {
    problem.headers.forEach { (name, value) -> response.header(name, value) }
    // With an unparseable Accept, Spring can write no body at all: status and headers only (RB-27).
    val accept = request.header(HttpHeaders.Accept)
    if (accept != null && MediaType.parseAccept(accept) == null) {
        respondBytes(ByteArray(0), status = problem.status)
        return
    }
    val body = Problem(problem.type, problem.title, problem.status.value, problem.message, request.path())
    // HEAD gets the headers only, as from the Java service.
    val bytes = if (request.httpMethod == HttpMethod.Head) ByteArray(0) else JSON_MAPPER.writeValueAsBytes(body)
    respondBytes(bytes, PROBLEM_JSON, problem.status)
}

/** Every error as Problem Details (RB-11 to RB-14, RB-30). */
fun StatusPagesConfig.problemDetails() {
    exception<UnknownOperationException> { call, e ->
        call.respondProblem(domainProblem(HttpStatusCode.BadRequest, "unknown-operation", "Unknown operation", e.message!!))
    }
    exception<OperandOutOfRangeException> { call, e ->
        call.respondProblem(domainProblem(HttpStatusCode.BadRequest, "operand-out-of-range", "Operand out of range", e.message!!))
    }
    exception<DivisionByZeroException> { call, e ->
        call.respondProblem(domainProblem(HttpStatusCode.UnprocessableEntity, "division-by-zero", "Division by zero", e.message!!))
    }
    exception<ProblemException> { call, e -> call.respondProblem(e) }
    exception<Throwable> { call, e -> call.respondProblem(unexpected(e)) }
    status(HttpStatusCode.NotFound) { call, _ ->
        call.respondProblem(frameworkProblem(HttpStatusCode.NotFound, "No static resource ${call.request.path().trim('/')}."))
    }
}

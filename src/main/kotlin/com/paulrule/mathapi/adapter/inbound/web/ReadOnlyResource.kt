package com.paulrule.mathapi.adapter.inbound.web

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.options
import io.ktor.server.routing.route

/**
 * A read-only resource as Spring serves its handler endpoints (actuator, springdoc): GET and HEAD answer [body] in
 * a type negotiated from [producible] (a ByteArray or String is sent as is, anything else as JSON; null is the bare
 * 404), OPTIONS lists the methods, and any other method is 405 with `Allow: GET`.
 */
internal fun Route.readOnlyResource(
    path: String,
    producible: List<MediaType>,
    body: (ApplicationCall) -> Any?,
) {
    route(path) {
        get { respondNegotiated(call, producible, body) }
        head { respondNegotiated(call, producible, body) }
        optionsAllowingGetHead()
        handle { throw methodNotAllowed(call, allow = "GET") }
    }
}

/** Spring's OPTIONS answer for a GET resource: 200, no body, `Allow: GET,HEAD,OPTIONS`; [check] may refuse first. */
internal fun Route.optionsAllowingGetHead(check: (ApplicationCall) -> Unit = {}) {
    options {
        check(call)
        call.response.header(HttpHeaders.Allow, "GET,HEAD,OPTIONS")
        call.respond(HttpStatusCode.OK)
    }
}

private suspend fun respondNegotiated(
    call: ApplicationCall,
    producible: List<MediaType>,
    body: (ApplicationCall) -> Any?,
) {
    // Negotiation comes first, so an unknown resource with nothing acceptable is 406, as in Java.
    val type = negotiate(call.request.header(HttpHeaders.Accept), producible)
    val value =
        body(call) ?: return call.run {
            attributes.put(BARE_RESPONSE, Unit) // the actuator's unknown group: the only 404 that is not Problem Details
            respondBytes(ByteArray(0), status = HttpStatusCode.NotFound)
        }
    val bytes =
        when (value) {
            is ByteArray -> value
            is String -> value.toByteArray()
            else -> JSON_MAPPER.writeValueAsBytes(value)
        }
    call.respond(SpringFormattedBytes(call.bodyUnlessHead { bytes }, type.toString()))
}

/** Unlike the calculations endpoint, the bare 406 for an unparseable Accept here keeps its Accept header. */
private fun negotiate(
    accept: String?,
    producible: List<MediaType>,
): MediaType {
    val list = producible.joinToString(", ")
    val notAcceptable =
        frameworkProblem(HttpStatusCode.NotAcceptable, "Acceptable representations: [$list].", mapOf(HttpHeaders.Accept to list))
    val accepted = MediaType.parseAccept(accept) ?: throw notAcceptable
    return MediaType.select(accepted, producible) ?: throw notAcceptable
}

/**
 * `http://` and the server's name and port as Tomcat reads them from the Host header: the port is a number (no
 * digits read as 0), dropped when it is 80; without a Host header (HTTP/1.0), the local name and port.
 * X-Forwarded-* is ignored (Spring Boot's default forward-headers strategy is none).
 */
internal fun baseUrl(call: ApplicationCall): String {
    val host = call.request.header(HttpHeaders.Host) ?: "${call.request.local.localHost}:${call.request.local.localPort}"
    val portAt = if (host.startsWith("[")) host.indexOf("]:").let { if (it < 0) -1 else it + 1 } else host.lastIndexOf(':')
    val name = if (portAt < 0) host else host.substring(0, portAt)
    val port = if (portAt < 0) 80 else host.substring(portAt + 1).toIntOrNull() ?: 0
    return "http://$name${if (port == 80) "" else ":$port"}"
}

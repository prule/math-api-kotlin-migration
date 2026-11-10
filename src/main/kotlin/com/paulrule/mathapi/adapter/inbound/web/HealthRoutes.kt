package com.paulrule.mathapi.adapter.inbound.web

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.options
import io.ktor.server.routing.route

/** The actuator's types, v3 first (its default); no `*+json` echo, unlike the calculations endpoint. */
private val ACTUATOR_TYPES =
    listOf(
        MediaType("application", "vnd.spring-boot.actuator.v3+json"),
        MediaType("application", "vnd.spring-boot.actuator.v2+json"),
        MediaType.JSON,
    )
private val ACTUATOR_ACCEPT = ACTUATOR_TYPES.joinToString(", ")

private val GROUPS = listOf("liveness", "readiness")
private val HEALTH = linkedMapOf("groups" to GROUPS, "status" to "UP")
private val UP = mapOf("status" to "UP")

/**
 * The actuator surface the Java service exposes with Spring Boot's defaults, and nothing else: the index, health
 * (always UP: the Java service has no health contributors) and its liveness and readiness groups.
 */
fun Route.healthRoutes() {
    // Without a Host header (HTTP/1.0), Tomcat names the server by its local host name and port.
    actuator("/actuator") { call ->
        links(call.request.header(HttpHeaders.Host) ?: "${call.request.local.localHost}:${call.request.local.localPort}")
    }
    actuator("/actuator/health") { HEALTH }
    actuator("/actuator/health/{path...}") { call ->
        // A trailing slash adds an empty segment; Java serves health and its groups with or without it.
        val segments =
            call.parameters
                .getAll("path")
                .orEmpty()
                .filter { it.isNotEmpty() }
        when {
            segments.isEmpty() -> HEALTH
            segments.size == 1 && segments[0] in GROUPS -> UP
            else -> null
        }
    }
}

/** GET and HEAD answer [body] (null means the bare 404), OPTIONS lists them, and any other method is 405. */
private fun Route.actuator(
    path: String,
    body: (ApplicationCall) -> Any?,
) {
    route(path) {
        get { respondActuator(call, body) }
        head { respondActuator(call, body) }
        options {
            call.response.header(HttpHeaders.Allow, "GET,HEAD,OPTIONS")
            call.respond(HttpStatusCode.OK)
        }
        handle { throw methodNotAllowed(call, allow = "GET") }
    }
}

private suspend fun respondActuator(
    call: ApplicationCall,
    body: (ApplicationCall) -> Any?,
) {
    // Negotiation comes first, so an unknown group with nothing acceptable is 406, as in Java.
    val type = actuatorType(call.request.header(HttpHeaders.Accept))
    val value =
        body(call) ?: return call.run {
            attributes.put(BARE_RESPONSE, Unit) // the only 404 that is not Problem Details
            respondBytes(ByteArray(0), status = HttpStatusCode.NotFound)
        }
    call.respond(SpringFormattedBytes(call.bodyUnlessHead { JSON_MAPPER.writeValueAsBytes(value) }, type.toString()))
}

/** Unlike the calculations endpoint, the actuator's bare 406 for an unparseable Accept keeps its Accept header. */
private fun actuatorType(accept: String?): MediaType {
    val notAcceptable =
        frameworkProblem(
            HttpStatusCode.NotAcceptable,
            "Acceptable representations: [$ACTUATOR_ACCEPT].",
            mapOf(HttpHeaders.Accept to ACTUATOR_ACCEPT),
        )
    val accepted = MediaType.parseAccept(accept) ?: throw notAcceptable
    return MediaType.select(accepted, ACTUATOR_TYPES) ?: throw notAcceptable
}

/**
 * Spring builds the links from the Host header as Tomcat parses it: the port is read as a number (no digits read
 * as 0) and dropped when it is 80. X-Forwarded-* is ignored (no forward-headers strategy).
 */
private fun links(host: String): Map<String, Any> {
    val portAt = if (host.startsWith("[")) host.indexOf("]:").let { if (it < 0) -1 else it + 1 } else host.lastIndexOf(':')
    val name = if (portAt < 0) host else host.substring(0, portAt)
    val port = if (portAt < 0) 80 else host.substring(portAt + 1).toIntOrNull() ?: 0
    val base = "http://$name${if (port == 80) "" else ":$port"}/actuator"
    return mapOf(
        "_links" to
            linkedMapOf(
                "self" to mapOf("href" to base, "templated" to false),
                "health" to mapOf("href" to "$base/health", "templated" to false),
                "health-path" to mapOf("href" to "$base/health/{*path}", "templated" to true),
            ),
    )
}

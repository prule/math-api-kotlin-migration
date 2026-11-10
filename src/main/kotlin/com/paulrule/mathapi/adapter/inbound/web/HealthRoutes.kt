package com.paulrule.mathapi.adapter.inbound.web

import io.ktor.server.routing.Route

/** The actuator's types, v3 first (its default); no `*+json` echo, unlike the calculations endpoint. */
private val ACTUATOR_TYPES =
    listOf(
        MediaType("application", "vnd.spring-boot.actuator.v3+json"),
        MediaType("application", "vnd.spring-boot.actuator.v2+json"),
        MediaType.JSON,
    )

private val GROUPS = listOf("liveness", "readiness")
private val HEALTH = linkedMapOf("groups" to GROUPS, "status" to "UP")
private val UP = mapOf("status" to "UP")

/**
 * The actuator surface the Java service exposes with Spring Boot's defaults, and nothing else: the index, health
 * (always UP: the Java service has no health contributors) and its liveness and readiness groups.
 */
fun Route.healthRoutes() {
    readOnlyResource("/actuator", ACTUATOR_TYPES) { call -> links(baseUrl(call)) }
    readOnlyResource("/actuator/health", ACTUATOR_TYPES) { HEALTH }
    readOnlyResource("/actuator/health/{path...}", ACTUATOR_TYPES) { call ->
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

private fun links(baseUrl: String): Map<String, Any> {
    val base = "$baseUrl/actuator"
    return mapOf(
        "_links" to
            linkedMapOf(
                "self" to mapOf("href" to base, "templated" to false),
                "health" to mapOf("href" to "$base/health", "templated" to false),
                "health-path" to mapOf("href" to "$base/health/{*path}", "templated" to true),
            ),
    )
}

package com.paulrule.mathapi

import com.paulrule.mathapi.adapter.inbound.web.calculationRoutes
import com.paulrule.mathapi.adapter.inbound.web.problemDetails
import com.paulrule.mathapi.config.UseCaseConfig
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing

fun main() {
    // 8080 matches the Java service, so the wire is the same after cutover.
    embeddedServer(Netty, port = System.getenv("PORT")?.toInt() ?: 8080) { module() }.start(wait = true)
}

/**
 * The composition root, outside every layer: it plays the part of the Spring container, which found the Java
 * controller by component scanning, so no layer needs to depend on the web adapter (docs/STANDARDS.md §2).
 */
fun Application.module() {
    val useCases = UseCaseConfig()
    install(StatusPages) { problemDetails() }
    routing { calculationRoutes(useCases.performCalculation) }
}

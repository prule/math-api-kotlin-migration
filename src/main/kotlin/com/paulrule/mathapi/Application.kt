package com.paulrule.mathapi

import com.paulrule.mathapi.adapter.inbound.web.apiDocsRoutes
import com.paulrule.mathapi.adapter.inbound.web.calculationRoutes
import com.paulrule.mathapi.adapter.inbound.web.healthRoutes
import com.paulrule.mathapi.adapter.inbound.web.problemDetails
import com.paulrule.mathapi.adapter.inbound.web.swaggerUiRoutes
import com.paulrule.mathapi.config.UseCaseConfig
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler

/** Closes a connection the [IdleStateHandler] reports idle. */
@ChannelHandler.Sharable
private object CloseWhenIdle : ChannelInboundHandlerAdapter() {
    override fun userEventTriggered(
        ctx: ChannelHandlerContext,
        evt: Any,
    ) {
        if (evt is IdleStateEvent) ctx.close() else super.userEventTriggered(ctx, evt)
    }
}

fun main() {
    embeddedServer(
        Netty,
        configure = {
            // 8080 matches the Java service, so the wire is the same after cutover.
            connector { port = System.getenv("PORT")?.toInt() ?: 8080 }
            // Tomcat's (Spring Boot's) 60 s connection timeout: a connection with no traffic either way is closed.
            channelPipelineConfig =
                { addFirst("idleTimeout", IdleStateHandler(0, 0, 60)).addAfter("idleTimeout", "idleClose", CloseWhenIdle) }
            responseWriteTimeoutSeconds = 60
            // Spring Boot's graceful shutdown waits up to 30 s for requests in flight.
            shutdownTimeout = 30_000
        },
        module = Application::module,
    ).start(wait = true)
}

/**
 * The composition root, outside every layer: it plays the part of the Spring container, which found the Java
 * controller by component scanning, so no layer needs to depend on the web adapter (docs/STANDARDS.md §2).
 */
fun Application.module() {
    val useCases = UseCaseConfig()
    install(StatusPages) { problemDetails() }
    routing {
        calculationRoutes(useCases.performCalculation)
        healthRoutes()
        apiDocsRoutes()
        swaggerUiRoutes()
    }
}

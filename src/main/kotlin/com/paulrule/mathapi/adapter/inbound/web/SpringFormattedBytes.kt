package com.paulrule.mathapi.adapter.inbound.web

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod

/**
 * Bytes with the Content-Type written exactly as Spring does (`application/json;charset=utf-8`, no space); Ktor's
 * ContentType would render `application/json; charset=utf-8` (RB-27).
 */
internal class SpringFormattedBytes(
    private val bytes: ByteArray,
    contentType: String,
) : OutgoingContent.ByteArrayContent() {
    override val contentLength get() = bytes.size.toLong()
    override val headers = headersOf(HttpHeaders.ContentType, contentType)

    override fun bytes() = bytes
}

/** The body to send: none for HEAD (headers only, as the Java service answers), else [body]. */
internal fun ApplicationCall.bodyUnlessHead(body: () -> ByteArray) = if (request.httpMethod == HttpMethod.Head) ByteArray(0) else body()

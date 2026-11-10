package com.paulrule.mathapi.adapter.inbound.web

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.core.JsonEncoding
import com.paulrule.mathapi.application.PerformCalculation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveStream
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.math.BigDecimal
import java.nio.charset.Charset

const val CALCULATIONS_PATH = "/api/v1/calculations"

private val ACCEPT_LIST = MediaType.PRODUCIBLE.joinToString(", ")

/** `POST /api/v1/calculations`, `OPTIONS` on it, and 405 for every other method (RB-23, RB-30). */
fun Route.calculationRoutes(performCalculation: PerformCalculation) {
    route(CALCULATIONS_PATH) {
        post {
            val input = call.receiveStream()
            val (body, type) =
                try {
                    val contentType = readableContentType(call.request.header(HttpHeaders.ContentType), input)
                    val (operation, a, b) = readRequest(input, contentType)
                    val calculation = performCalculation.perform(operation, a, b)
                    // Computed before negotiating, so division by zero is reported even when nothing is acceptable.
                    JSON_MAPPER.writeValueAsBytes(CalculationResponse.of(calculation)) to
                        responseType(call.request.header(HttpHeaders.Accept))
                } catch (e: Throwable) {
                    drain(input)
                    throw e
                }
            call.respond(SpringFormattedBytes(body, type.toString()))
        }
        options {
            call.response.header(HttpHeaders.Allow, "POST,OPTIONS")
            call.respond(HttpStatusCode.OK)
        }
        handle { throw methodNotAllowed(call, allow = "POST") }
    }
}

/**
 * RB-28: JSON or `*+json` only; anything else is 415 with Spring's detail. With no Content-Type, an empty body is
 * invalid-request (Spring reports the missing body first) and any other body is 415 as `application/octet-stream`.
 */
private suspend fun readableContentType(
    header: String?,
    input: InputStream,
): MediaType {
    if (header == null) {
        val empty = withContext(Dispatchers.IO) { input.read() == -1 }
        throw if (empty) invalidRequest() else unsupported("Content-Type 'application/octet-stream' is not supported.")
    }
    val type = MediaType.parse(header) ?: throw unsupported("Could not parse Content-Type.")
    if (!MediaType.isReadable(type)) throw unsupported("Content-Type '${type.toUnsupportedDetailForm()}' is not supported.")
    return type
}

private fun unsupported(detail: String) =
    frameworkProblem(HttpStatusCode.UnsupportedMediaType, detail, mapOf(HttpHeaders.Accept to ACCEPT_LIST))

// Like Tomcat's default maxSwallowSize: beyond this the connection is dropped instead.
private const val MAX_DRAIN_BYTES = 2L * 1024 * 1024

/**
 * Reads what is left of an unread body (up to 2 MiB) before an error is sent. A client that writes its whole body
 * before reading the response would otherwise wait forever on a request rejected after 16 KiB.
 */
private suspend fun drain(input: InputStream) =
    withContext(Dispatchers.IO) {
        try {
            var left = MAX_DRAIN_BYTES
            val buffer = ByteArray(8_192)
            while (left > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (read < 0) break
                left -= read
            }
        } catch (_: IOException) {
            // The client went away; nothing left to protect.
        }
    }

// The encodings Jackson detects itself; Spring hands Jackson the raw bytes for these and a decoding Reader otherwise.
private val JACKSON_ENCODINGS = JsonEncoding.entries.map { it.javaName }.toSet()

/**
 * Parses the body and turns every parse failure or missing field into invalid-request. The bytes go straight to
 * Jackson (so the size limit counts bytes, RB-8a) unless a non-Unicode charset is declared, which Spring decodes.
 */
private suspend fun readRequest(
    body: InputStream,
    contentType: MediaType,
): Triple<String, BigDecimal, BigDecimal> {
    val charset = contentType.charset?.trim('"')?.let { Charset.forName(it) }
    val request =
        try {
            withContext(Dispatchers.IO) {
                if (charset == null || charset.name() in JACKSON_ENCODINGS) {
                    JSON_MAPPER.readValue(body, CalculationRequest::class.java)
                } else {
                    JSON_MAPPER.readValue(InputStreamReader(body, charset), CalculationRequest::class.java)
                }
            }
        } catch (_: JacksonException) {
            throw invalidRequest()
        } catch (_: IOException) {
            throw invalidRequest()
        }
    return Triple(request?.operation ?: throw invalidRequest(), request.a ?: throw invalidRequest(), request.b ?: throw invalidRequest())
}

/** RB-27: the type Spring would choose from Accept, or 406. */
private fun responseType(accept: String?): MediaType {
    // An unparseable Accept: the problem's body is dropped on the way out, leaving Spring's bare 406.
    val accepted = MediaType.parseAccept(accept) ?: throw frameworkProblem(HttpStatusCode.NotAcceptable, "Unparseable Accept header.")
    return MediaType.select(accepted)
        ?: throw frameworkProblem(
            HttpStatusCode.NotAcceptable,
            "Acceptable representations: [$ACCEPT_LIST].",
            mapOf(HttpHeaders.Accept to ACCEPT_LIST),
        )
}

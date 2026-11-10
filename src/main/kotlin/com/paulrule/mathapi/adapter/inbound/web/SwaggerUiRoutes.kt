package com.paulrule.mathapi.adapter.inbound.web

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.route
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Properties

/** The bundled webjar's folder; the version comes from the jar itself, so a dependency upgrade edits one place. */
private val WEBJAR =
    Properties()
        .apply { load(object {}.javaClass.getResourceAsStream("/META-INF/maven/org.webjars/swagger-ui/pom.properties")) }
        .getProperty("version")
        .let { "META-INF/resources/webjars/swagger-ui/$it/" }

// Every file type in the webjar, with the content type Spring sends for it.
private val CONTENT_TYPES =
    mapOf("html" to "text/html", "js" to "text/javascript", "css" to "text/css", "png" to "image/png", "txt" to "text/plain")

/** Files springdoc rewrites, at their exact path: served `no-store` and without Last-Modified. */
private val TRANSFORMED = setOf("index.html", "swagger-initializer.js")

/** `/swagger-ui.html` (a redirect) and the Swagger UI files under `/swagger-ui/`, as springdoc serves them. */
fun Route.swaggerUiRoutes() {
    route("/swagger-ui.html") {
        get { redirectToIndex(call) }
        head { redirectToIndex(call) }
        optionsAllowingGetHead()
        handle { throw methodNotAllowed(call, allow = "GET") }
    }
    route("/swagger-ui/{file...}") {
        get { respondFile(call) }
        head { respondFile(call) }
        // Spring finds the file before it looks at the method, so an unknown file is 404 for every method.
        optionsAllowingGetHead { file(it) }
        handle {
            val file = file(call)
            // springdoc serves its transformed files through its own handler, whose 405 skips the download protection.
            if (file.transformed) call.attributes.put(NO_DOWNLOAD_PROTECTION, Unit)
            throw methodNotAllowed(call, allow = "GET, HEAD")
        }
    }
}

private suspend fun redirectToIndex(call: ApplicationCall) {
    val query = call.request.queryString()
    call.response.header(HttpHeaders.Location, "/swagger-ui/index.html" + if (query.isEmpty()) "" else "?$query")
    call.respond(HttpStatusCode.Found)
}

private class WebjarFile(
    val name: String,
    val url: URL,
    val transformed: Boolean,
)

/**
 * The webjar file a request names, as Spring's resource handler resolves it: empty and `.` segments and a trailing
 * slash are dropped, `..` is refused. Only the exact paths `/swagger-ui/index.html` and the initializer get
 * springdoc's transformed copy. Throws the 404 problem, whose detail names the raw path relative to `/swagger-ui/`.
 */
private fun file(call: ApplicationCall): WebjarFile {
    val raw = call.request.path()
    val notFound = frameworkProblem(HttpStatusCode.NotFound, "No static resource ${raw.removePrefix("/swagger-ui").trimStart('/')}.")
    val segments =
        call.parameters
            .getAll("file")
            .orEmpty()
            .filter { it.isNotEmpty() && it != "." }
    if (segments.isEmpty() || ".." in segments) throw notFound
    val name = segments.joinToString("/")
    val url = object {}.javaClass.classLoader.getResource(WEBJAR + name) ?: throw notFound
    return WebjarFile(name, url, transformed = name in TRANSFORMED && raw == "/swagger-ui/$name")
}

private suspend fun respondFile(call: ApplicationCall) {
    val file = file(call)
    val type = CONTENT_TYPES[file.name.substringAfterLast('.')] ?: "application/octet-stream"
    var bytes = file.url.readBytes()
    if (file.transformed) {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        if (file.name == "swagger-initializer.js") bytes = withSpringdocConfig(bytes.decodeToString()).toByteArray()
    } else {
        val modified = Instant.ofEpochMilli(file.url.openConnection().lastModified / 1000 * 1000)
        httpDate(call.request.header(HttpHeaders.IfUnmodifiedSince))?.let {
            if (modified.isAfter(it)) return call.respond(HttpStatusCode.PreconditionFailed)
        }
        call.response.header(HttpHeaders.LastModified, HTTP_DATES.first().format(modified.atZone(ZoneOffset.UTC)))
        httpDate(call.request.header(HttpHeaders.IfModifiedSince))?.let {
            if (!modified.isAfter(it)) return call.respond(HttpStatusCode.NotModified)
        }
    }
    call.response.header(HttpHeaders.AcceptRanges, "bytes")
    val rangeHeader =
        call.request.header(HttpHeaders.Range) ?: return call.respond(SpringFormattedBytes(call.bodyUnlessHead { bytes }, type))
    val ranges = byteRanges(rangeHeader, bytes.size) ?: return rangeNotSatisfiable(call, bytes.size)
    call.response.status(HttpStatusCode.PartialContent)
    if (ranges.size == 1) {
        val range = ranges.single()
        call.response.header(HttpHeaders.ContentRange, "bytes ${range.first}-${range.last}/${bytes.size}")
        return call.respond(SpringFormattedBytes(call.bodyUnlessHead { bytes.copyOfRange(range.first, range.last + 1) }, type))
    }
    // No range or several: one multipart/byteranges body, laid out as Spring writes it.
    val boundary = (1..(30 + RANDOM.nextInt(11))).map { BOUNDARY_CHARS[RANDOM.nextInt(BOUNDARY_CHARS.length)] }.joinToString("")
    val body = ByteArrayOutputStream()
    for (range in ranges) {
        body.write(
            "\r\n--$boundary\r\nContent-Type: $type\r\nContent-Range: bytes ${range.first}-${range.last}/${bytes.size}\r\n\r\n"
                .toByteArray(),
        )
        body.write(bytes, range.first, range.last - range.first + 1)
    }
    body.write("\r\n--$boundary--".toByteArray())
    call.respond(SpringFormattedBytes(call.bodyUnlessHead { body.toByteArray() }, "multipart/byteranges; boundary=$boundary"))
}

// The three date formats HTTP allows (IMF-fixdate, RFC 850, asctime); Spring accepts all of them.
private val HTTP_DATES =
    listOf("EEE, dd MMM yyyy HH:mm:ss 'GMT'", "EEEE, dd-MMM-yy HH:mm:ss zzz", "EEE MMM d HH:mm:ss yyyy").map {
        DateTimeFormatter.ofPattern(it, Locale.US)
    }

private fun httpDate(value: String?): Instant? {
    val text = value?.trim()?.replace(Regex(" +"), " ") ?: return null
    for (format in HTTP_DATES) {
        runCatching { return ZonedDateTime.parse(text, format).toInstant() }
        runCatching { return LocalDateTime.parse(text, format).toInstant(ZoneOffset.UTC) }
    }
    return null
}

// Spring's multipart boundary: 30 to 40 random characters from this set.
private const val BOUNDARY_CHARS = "-_1234567890abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
private val RANDOM = SecureRandom()

// Spring refuses more ranges than this, so a request can't make the server build a huge multipart body.
private const val MAX_RANGES = 100

/**
 * Spring's `HttpRange` parsing: `bytes=` then `a-b`, `a-` or `-n`, comma separated, empty entries skipped. Null
 * when unsatisfiable, including more than 100 ranges or several adding up to the whole file or more (Spring's guards).
 */
private fun byteRanges(
    header: String,
    size: Int,
): List<IntRange>? {
    if (!header.startsWith("bytes=")) return null
    val specs =
        header
            .removePrefix("bytes=")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    if (specs.size > MAX_RANGES) return null
    val ranges = parseRanges(specs, size) ?: return null
    if (ranges.size > 1 && ranges.sumOf { it.last - it.first + 1L } >= size) return null
    return ranges
}

private fun parseRanges(
    specs: List<String>,
    size: Int,
): List<IntRange>? =
    specs.map { spec ->
        val (start, end) = spec.split('-', limit = 2).takeIf { it.size == 2 } ?: return null
        if (start.isEmpty()) {
            val suffix = end.toLongOrNull()?.takeIf { it > 0 } ?: return null
            (size - minOf(suffix, size.toLong()).toInt()) until size
        } else {
            val first = start.toLongOrNull()?.takeIf { it < size } ?: return null
            val last = if (end.isEmpty()) size - 1L else end.toLongOrNull()?.takeIf { it >= first } ?: return null
            first.toInt()..minOf(last, size - 1L).toInt()
        }
    }

/** Spring Boot's own error JSON (not Problem Details, as in Java), with the size of what was asked for. */
private suspend fun rangeNotSatisfiable(
    call: ApplicationCall,
    size: Int,
) {
    call.response.header(HttpHeaders.ContentRange, "bytes */$size")
    call.protectDownload()
    val error =
        linkedMapOf(
            "timestamp" to Instant.now().toString(),
            "status" to 416,
            "error" to "Requested range not satisfiable",
            "path" to call.request.path(),
        )
    call.response.status(HttpStatusCode.RequestedRangeNotSatisfiable)
    call.respond(SpringFormattedBytes(call.bodyUnlessHead { JSON_MAPPER.writeValueAsBytes(error) }, "application/json"))
}

/** springdoc's rewrite of the webjar initializer: it points the UI at `/v3/api-docs/swagger-config`. */
private fun withSpringdocConfig(initializer: String) =
    initializer.replace(
        "    layout: \"StandaloneLayout\"\n",
        "    layout: \"StandaloneLayout\" ,\n\n  \"configUrl\" : \"/v3/api-docs/swagger-config\",\n  \"validatorUrl\" : \"\"\n\n",
    )

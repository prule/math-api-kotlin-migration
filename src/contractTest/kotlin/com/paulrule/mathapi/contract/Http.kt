package com.paulrule.mathapi.contract

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** The service under test. Required: a default would make "green" meaningless if it pointed at the wrong service. */
val BASE_URL: String =
    requireNotNull(System.getenv("BASE_URL")?.trimEnd('/')) {
        "Set BASE_URL to the service under test, e.g. BASE_URL=http://localhost:8080 ./gradlew contractTest"
    }

const val CALCULATIONS = "/api/v1/calculations"

/** A valid calculation request, for tests about everything except the arithmetic. */
const val VALID_ADD = """{"operation": "ADD", "a": 1, "b": 2}"""

/** [VALID_ADD] padded inside the object to [length] characters, so the parser must read every one (RB-8). */
fun validBodyPaddedTo(length: Int): String {
    val padded = "{" + " ".repeat(length - VALID_ADD.length) + VALID_ADD.substring(1)
    check(padded.length == length)
    return padded
}

val mapper = ObjectMapper()

// HTTP/1.1: the JDK client's default h2c upgrade adds headers neither service sees in production.
private val client: HttpClient =
    HttpClient
        .newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

class Response(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: String,
) {
    fun header(name: String): String? =
        headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.joinToString(",")

    val contentType: String? get() = header("Content-Type")

    fun json(): JsonNode = mapper.readTree(body)
}

fun JsonNode.propertyNames(): List<String> = properties().map { it.key }

fun send(
    method: String,
    path: String,
    body: String? = null,
    headers: Map<String, String> = emptyMap(),
): Response {
    val request =
        HttpRequest
            .newBuilder(URI.create(BASE_URL + path))
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
    headers.forEach { (name, value) -> request.header(name, value) }
    val response = client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    return Response(response.statusCode(), response.headers().map(), response.body())
}

fun get(
    path: String,
    headers: Map<String, String> = emptyMap(),
): Response = send("GET", path, headers = headers)

/** POSTs [body] to the calculations endpoint as JSON unless another content type is given. */
fun calculate(
    body: String,
    contentType: String? = "application/json",
    accept: String? = null,
): Response =
    send(
        "POST",
        CALCULATIONS,
        body,
        buildMap {
            contentType?.let { put("Content-Type", it) }
            accept?.let { put("Accept", it) }
        },
    )

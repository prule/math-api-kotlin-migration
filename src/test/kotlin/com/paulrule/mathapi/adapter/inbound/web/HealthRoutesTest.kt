package com.paulrule.mathapi.adapter.inbound.web

import com.paulrule.mathapi.module
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/** The health endpoints in process; the contract suite compares every probed variant with the Java service. */
class HealthRoutesTest {
    private class Result(
        val status: Int,
        val contentType: String?,
        val body: String,
    )

    private fun get(
        path: String,
        headers: Map<String, String> = emptyMap(),
    ): Result {
        var result: Result? = null
        testApplication {
            application { module() }
            val response = client.get(path) { headers.forEach { (name, value) -> header(name, value) } }
            result = Result(response.status.value, response.headers[HttpHeaders.ContentType], response.bodyAsText())
        }
        return result!!
    }

    @Test
    fun `health is UP with its groups and no details`() {
        val response = get("/actuator/health")

        assertThat(response.status).isEqualTo(200)
        assertThat(response.contentType).isEqualTo("application/vnd.spring-boot.actuator.v3+json")
        assertThat(JSON_MAPPER.readTree(response.body))
            .isEqualTo(JSON_MAPPER.readTree("""{"groups": ["liveness", "readiness"], "status": "UP"}"""))
    }

    @ParameterizedTest
    @ValueSource(strings = ["/actuator/health/liveness", "/actuator/health/liveness/", "/actuator/health/readiness"])
    fun `each group is UP, with or without a trailing slash`(path: String) {
        assertThat(get(path).body).isEqualTo("""{"status":"UP"}""")
    }

    @Test
    fun `an unknown group is an empty 404`() {
        val response = get("/actuator/health/nope")

        assertThat(response.status).isEqualTo(404)
        assertThat(response.contentType).isNull()
        assertThat(response.body).isEmpty()
    }

    @Test
    fun `the index links only to health`() {
        val links = JSON_MAPPER.readTree(get("/actuator").body)["_links"]

        assertThat(links.fieldNames().asSequence().toList()).containsExactly("self", "health", "health-path")
        assertThat(links["health-path"]["templated"].asBoolean()).isTrue()
    }

    // Tomcat's Host parsing: port 80 is dropped, a port with no digits reads as 0.
    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "example.org:9999, http://example.org:9999/actuator",
        "example.org:80, http://example.org/actuator",
        "example.org:, http://example.org:0/actuator",
    )
    fun `index links follow the Host header`(
        host: String,
        self: String,
    ) {
        assertThat(JSON_MAPPER.readTree(get("/actuator", mapOf("Host" to host)).body)["_links"]["self"]["href"].asText()).isEqualTo(self)
    }

    @Test
    fun `plain JSON is offered on request`() {
        assertThat(get("/actuator/health", mapOf("Accept" to "application/json")).contentType).isEqualTo("application/json")
    }
}

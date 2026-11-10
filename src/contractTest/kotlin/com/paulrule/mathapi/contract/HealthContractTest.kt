package com.paulrule.mathapi.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/** Ported from the Java `ServiceHealthSmokeTest`, plus the health behaviour only seen by probing (RB-24). */
class HealthContractTest {
    // RB-15, RB-24
    @Test
    fun `started instance reports UP without details`() {
        val response = get("/actuator/health")

        assertThat(response.status).isEqualTo(200)
        assertThat(response.contentType).matches("application/(json|vnd\\.spring-boot\\.actuator\\.v3\\+json).*")
        val body = response.json()
        assertThat(body["status"].asText()).isEqualTo("UP")
        assertThat(body.has("components")).isFalse()
        assertThat(body.has("details")).isFalse()
    }

    // RB-24: stricter than the Java test's regex, which also allowed application/json.
    @Test
    fun `health uses the actuator media type`() {
        assertThat(get("/actuator/health").contentType).startsWith("application/vnd.spring-boot.actuator.v3+json")
    }

    // RB-24: unlike the calculations endpoint (RB-23), the health path matches with a trailing slash.
    @Test
    fun `health also answers with a trailing slash`() {
        assertThat(get("/actuator/health/").json()["status"].asText()).isEqualTo("UP")
    }

    // RB-24
    @Test
    fun `health lists the liveness and readiness groups`() {
        assertThat(get("/actuator/health").json()["groups"].map { it.asText() }).containsExactly("liveness", "readiness")
    }

    // RB-24
    @ParameterizedTest
    @ValueSource(strings = ["liveness", "readiness"])
    fun `each health group reports UP`(group: String) {
        val response = get("/actuator/health/$group")

        assertThat(response.status).isEqualTo(200)
        assertThat(response.json()).isEqualTo(mapper.readTree("""{"status": "UP"}"""))
    }

    // RB-24: the one error that is not Problem Details.
    @Test
    fun `an unknown health group is 404 with an empty body`() {
        val response = get("/actuator/health/nope")

        assertThat(response.status).isEqualTo(404)
        assertThat(response.body).isEmpty()
    }

    // RB-16
    @Test
    fun `actuator index links only to health`() {
        val response = get("/actuator")

        assertThat(response.status).isEqualTo(200)
        assertThat(response.json()["_links"].propertyNames()).containsExactlyInAnyOrder("self", "health", "health-path")
    }

    // RB-1, RB-4
    @Test
    fun `performs a calculation end to end`() {
        val response = calculate("""{"operation": "ADD", "a": 0.1, "b": "0.2"}""")

        assertThat(response.status).isEqualTo(200)
        assertThat(response.json()["result"].asText()).isEqualTo("0.3")
    }

    // RB-17
    @ParameterizedTest
    @ValueSource(strings = ["/actuator/env", "/actuator/beans", "/actuator/info"])
    fun `other operational endpoints are not served`(path: String) {
        assertThat(get(path).status).isEqualTo(404)
    }

    // Actuator media types: v3 by default, v2 and plain JSON on request; quality decides between equals.
    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        value = [
            "*/*                                                                  | application/vnd.spring-boot.actuator.v3+json",
            "application/json                                                     | application/json",
            "application/vnd.spring-boot.actuator.v2+json                         | application/vnd.spring-boot.actuator.v2+json",
            "application/json;q=0.5, application/vnd.spring-boot.actuator.v2+json | application/vnd.spring-boot.actuator.v2+json",
            "application/json;charset=utf-8                                       | application/json;charset=utf-8",
        ],
    )
    fun `health negotiates the actuator media types`(
        accept: String,
        contentType: String,
    ) {
        val response = get("/actuator/health", mapOf("Accept" to accept))

        assertThat(response.status).isEqualTo(200)
        assertThat(response.contentType).isEqualTo(contentType)
    }

    @Test
    fun `no acceptable type is a 406 problem listing the actuator types`() {
        val response = get("/actuator/health", mapOf("Accept" to "application/vnd.foo+json"))

        assertFrameworkProblem(response, 406, "Not Acceptable", "/actuator/health")
        assertThat(response.header("Accept")).isEqualTo(ACTUATOR_TYPES)
        assertThat(response.json()["detail"].asText()).isEqualTo("Acceptable representations: [$ACTUATOR_TYPES].")
    }

    // Negotiation happens before the unknown-group 404, and an unparseable Accept keeps its Accept header.
    @Test
    fun `an unparseable Accept on an unknown group is a bodiless 406`() {
        val response = get("/actuator/health/nope", mapOf("Accept" to "garbage"))

        assertThat(response.status).isEqualTo(406)
        assertThat(response.body).isEmpty()
        assertThat(response.header("Accept")).isEqualTo(ACTUATOR_TYPES)
    }

    @ParameterizedTest
    @ValueSource(strings = ["/actuator/health/LIVENESS", "/actuator/health/liveness/x"])
    fun `other health paths are empty 404s`(path: String) {
        val response = get(path)

        assertThat(response.status).isEqualTo(404)
        assertThat(response.body).isEmpty()
    }

    @Test
    fun `the index has no trailing-slash form`() {
        assertFrameworkProblem(get("/actuator/"), 404, "Not Found", "/actuator/")
    }

    @Test
    fun `index links follow the Host header and ignore forwarding headers`() {
        val viaHost = get("/actuator", mapOf("Host" to "example.org:9999")).json()["_links"]
        val forwarded = get("/actuator", mapOf("X-Forwarded-Host" to "proxy.example", "X-Forwarded-Proto" to "https")).json()["_links"]

        assertThat(viaHost["self"]["href"].asText()).isEqualTo("http://example.org:9999/actuator")
        assertThat(viaHost["health-path"]["href"].asText()).isEqualTo("http://example.org:9999/actuator/health/{*path}")
        assertThat(viaHost["health-path"]["templated"].asBoolean()).isTrue()
        assertThat(forwarded["self"]["href"].asText()).isEqualTo("$BASE_URL/actuator")
    }

    @ParameterizedTest
    @ValueSource(strings = ["/actuator", "/actuator/health", "/actuator/health/liveness"])
    fun `actuator paths answer HEAD without a body and OPTIONS with the allowed methods`(path: String) {
        val head = send("HEAD", path)
        val options = send("OPTIONS", path)

        assertThat(head.status).isEqualTo(200)
        assertThat(head.contentType).isEqualTo("application/vnd.spring-boot.actuator.v3+json")
        assertThat(head.body).isEmpty()
        assertThat(options.status).isEqualTo(200)
        assertThat(options.header("Allow")).isEqualTo("GET,HEAD,OPTIONS")
    }

    @ParameterizedTest
    @ValueSource(strings = ["POST", "PUT", "DELETE"])
    fun `other methods are not allowed`(method: String) {
        val response = send(method, "/actuator/health")

        assertFrameworkProblem(response, 405, "Method Not Allowed", "/actuator/health")
        assertThat(response.header("Allow")).isEqualTo("GET")
    }

    companion object {
        private const val ACTUATOR_TYPES =
            "application/vnd.spring-boot.actuator.v3+json, application/vnd.spring-boot.actuator.v2+json, application/json"
    }
}

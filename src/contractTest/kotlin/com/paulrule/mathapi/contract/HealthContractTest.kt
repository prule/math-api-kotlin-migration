package com.paulrule.mathapi.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
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
}

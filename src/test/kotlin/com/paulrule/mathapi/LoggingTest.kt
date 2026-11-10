package com.paulrule.mathapi

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class LoggingTest {
    // Matches the Java service's (Spring Boot's) default level; logback alone would log Netty internals at DEBUG.
    @Test
    fun `logs at INFO, not DEBUG`() {
        val log = LoggerFactory.getLogger("io.netty")

        assertThat(log.isInfoEnabled).isTrue()
        assertThat(log.isDebugEnabled).isFalse()
    }
}

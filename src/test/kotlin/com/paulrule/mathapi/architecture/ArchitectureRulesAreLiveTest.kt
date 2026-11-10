package com.paulrule.mathapi.architecture

import com.paulrule.mathapi.adapter.inbound.web.fixture.WebType
import com.paulrule.mathapi.application.fixture.UsesKtorCall
import com.paulrule.mathapi.application.fixture.UsesWeb
import com.paulrule.mathapi.domain.fixture.UsesJackson
import com.paulrule.mathapi.domain.fixture.UsesKtor
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.ArchRule
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

// Each rule (and each half of a rule) is checked against a class that breaks it, so a rule that can never fail is caught.
class ArchitectureRulesAreLiveTest {
    @Test
    fun `domain rule rejects a domain class that uses Ktor`() {
        assertRejects(ArchitectureTest.DOMAIN_IS_FRAMEWORK_FREE, UsesKtor::class.java, UsesKtor::class.java)
    }

    @Test
    fun `domain rule rejects a domain class that uses Jackson`() {
        assertRejects(ArchitectureTest.DOMAIN_IS_FRAMEWORK_FREE, UsesJackson::class.java, UsesJackson::class.java)
    }

    @Test
    fun `application rule rejects an application class that uses Ktor`() {
        assertRejects(ArchitectureTest.APPLICATION_HAS_NO_KTOR_OR_ADAPTERS, UsesKtorCall::class.java, UsesKtorCall::class.java)
    }

    @Test
    fun `application rule rejects an application class that uses an adapter`() {
        assertRejects(ArchitectureTest.APPLICATION_HAS_NO_KTOR_OR_ADAPTERS, UsesWeb::class.java, UsesWeb::class.java, WebType::class.java)
    }

    @Test
    fun `layer rule rejects application depending on web`() {
        assertRejects(ArchitectureTest.LAYERS_DEPEND_INWARDS, UsesWeb::class.java, UsesWeb::class.java, WebType::class.java)
    }

    private fun assertRejects(
        rule: ArchRule,
        violator: Class<*>,
        vararg toImport: Class<*>,
    ) {
        val classes = ClassFileImporter().importClasses(*toImport)

        assertThatThrownBy { rule.check(classes) }
            .isInstanceOf(AssertionError::class.java)
            .hasMessageContaining(violator.name)
    }
}

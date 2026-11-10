package com.paulrule.mathapi.architecture

import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.Architectures.layeredArchitecture

/** docs/STANDARDS.md §2 dependency rules. */
@AnalyzeClasses(packages = ["com.paulrule.mathapi"], importOptions = [ImportOption.DoNotIncludeTests::class])
class ArchitectureTest {
    companion object {
        private const val DOMAIN = "com.paulrule.mathapi.domain.."
        private const val APPLICATION = "com.paulrule.mathapi.application.."
        private const val WEB = "com.paulrule.mathapi.adapter.inbound.web.."
        private const val CONFIG = "com.paulrule.mathapi.config.."

        @ArchTest
        @JvmField
        val LAYERS_DEPEND_INWARDS: ArchRule =
            layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                // shortcut: allows empty layers until item 3 fills them; remove in add-calculation
                .withOptionalLayers(true)
                .layer("Domain")
                .definedBy(DOMAIN)
                .layer("Application")
                .definedBy(APPLICATION)
                .layer("Web")
                .definedBy(WEB)
                .layer("Config")
                .definedBy(CONFIG)
                .whereLayer("Web")
                .mayNotBeAccessedByAnyLayer()
                .whereLayer("Config")
                .mayNotBeAccessedByAnyLayer()
                .whereLayer("Application")
                .mayOnlyBeAccessedByLayers("Web", "Config")
                .whereLayer("Domain")
                .mayOnlyBeAccessedByLayers("Application", "Web", "Config")

        @ArchTest
        @JvmField
        val DOMAIN_IS_FRAMEWORK_FREE: ArchRule =
            noClasses()
                .that()
                .resideInAPackage(DOMAIN)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("io.ktor..", "com.fasterxml..", "tools.jackson..")
                // shortcut: allows an empty domain until item 3 fills it; remove in add-calculation
                .allowEmptyShould(true)

        // All of Ktor, not just its web part: with no DI framework, application code never needs it.
        @ArchTest
        @JvmField
        val APPLICATION_HAS_NO_KTOR_OR_ADAPTERS: ArchRule =
            noClasses()
                .that()
                .resideInAPackage(APPLICATION)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("io.ktor..", "com.paulrule.mathapi.adapter..")
                // shortcut: allows an empty application layer until item 3 fills it; remove in add-calculation
                .allowEmptyShould(true)
    }
}

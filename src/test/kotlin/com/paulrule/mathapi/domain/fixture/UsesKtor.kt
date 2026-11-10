package com.paulrule.mathapi.domain.fixture

import io.ktor.server.application.Application

/** Breaks the domain rule on purpose so `ArchitectureRulesAreLiveTest` can prove the rule fires. */
class UsesKtor {
    var application: Application? = null
}

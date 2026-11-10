package com.paulrule.mathapi.config

import com.paulrule.mathapi.application.PerformCalculation

/** The only place use cases are created (docs/STANDARDS.md §2), as the Java `UseCaseConfig` beans. */
class UseCaseConfig {
    val performCalculation = PerformCalculation()
}

package com.paulrule.mathapi.domain.fixture

import com.fasterxml.jackson.databind.ObjectMapper

/** Breaks only the domain rule's Jackson ban, so its test cannot pass on the Ktor ban alone. */
class UsesJackson {
    var mapper: ObjectMapper? = null
}

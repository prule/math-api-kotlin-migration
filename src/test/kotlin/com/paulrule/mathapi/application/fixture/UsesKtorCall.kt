package com.paulrule.mathapi.application.fixture

import io.ktor.server.application.ApplicationCall

/** Breaks only the application rule's Ktor ban, so its test cannot pass on the adapter ban alone. */
class UsesKtorCall {
    var call: ApplicationCall? = null
}

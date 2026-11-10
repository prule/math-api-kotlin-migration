package com.paulrule.mathapi.application.fixture

import com.paulrule.mathapi.adapter.inbound.web.fixture.WebType

/** Breaks the layer and application rules on purpose (an adapter dependency) so `ArchitectureRulesAreLiveTest` can prove they fire. */
class UsesWeb {
    var web: WebType? = null
}

package com.paulrule.mathapi.adapter.inbound.web

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule

/**
 * Jackson 2 set up to read like the Java service's Jackson 3 + Spring Boot: the request limits (RB-8; the document
 * limit counts bytes when reading a stream, RB-8a), 500 levels of nesting and no trailing tokens (RB-29), unknown
 * fields ignored (RB-8b).
 */
internal val JSON_MAPPER: ObjectMapper =
    JsonMapper
        .builder(
            JsonFactory
                .builder()
                .streamReadConstraints(
                    StreamReadConstraints
                        .builder()
                        .maxDocumentLength(16_384)
                        .maxNumberLength(1_000)
                        .maxStringLength(1_000)
                        .maxNestingDepth(500)
                        .build(),
                )
                // The route drains what is left of a rejected body, so Jackson must not close the request stream.
                .disable(StreamReadFeature.AUTO_CLOSE_SOURCE)
                .build(),
        ).addModule(kotlinModule())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

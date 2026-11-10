package com.paulrule.mathapi.adapter.inbound.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/** Spring's media-type rules as observed on the Java service (RB-27, RB-28); the contract suite checks them over HTTP. */
class MediaTypeTest {
    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        value = [
            "application/vnd.a+json, application/json         | application/vnd.a+json",
            "application/*;q=1, application/vnd.c+json        | application/vnd.c+json",
            "application/json;q=0.5, application/vnd.a+json   | application/vnd.a+json",
            "application/json, application/vnd.a+json         | application/json",
            "*/*;q=0.9, application/vnd.a+json;q=0.1          | application/json",
            "application/json;charset=utf-8                   | application/json;charset=utf-8",
            "APPLICATION/JSON;Charset=UTF-8                   | application/json;charset=UTF-8",
            "application/json;foo                             | application/json",
            "*/*;q=0.1, application/xml                       | application/json",
            "','                                              | application/json",
            "application/vnd.a+json;x=\"1,2\"                 | application/vnd.a+json;x=\"1,2\"",
        ],
    )
    fun `chooses the response type as Spring does`(
        accept: String,
        chosen: String,
    ) {
        assertThat(MediaType.select(MediaType.parseAccept(accept)!!).toString()).isEqualTo(chosen)
    }

    @ParameterizedTest
    @ValueSource(strings = ["garbage", "application/json;q=2", "application/json;charset=bogus"])
    fun `rejects an unparseable Accept`(accept: String) {
        assertThat(MediaType.parseAccept(accept)).isNull()
    }

    @Test
    fun `finds nothing acceptable for text only`() {
        assertThat(MediaType.select(MediaType.parseAccept("text/*")!!)).isNull()
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        value = [
            "TEXT/PLAIN                       | text/plain;charset=UTF-8",
            "multipart/form-data; boundary=\"a;b\" | multipart/form-data;boundary=\"a;b\";charset=UTF-8",
            "text/plain; charset=ISO-8859-1   | text/plain;charset=ISO-8859-1",
        ],
    )
    fun `renders the unsupported type as Spring does`(
        type: String,
        detail: String,
    ) {
        assertThat(MediaType.parse(type)!!.toUnsupportedDetailForm()).isEqualTo(detail)
    }

    @ParameterizedTest
    @CsvSource(
        "application/json, true",
        "application/json-patch+json, true",
        "application/*+json, false",
        "*/*, false",
        "text/plain, false",
    )
    fun `reads concrete JSON types only`(
        type: String,
        readable: Boolean,
    ) {
        assertThat(MediaType.isReadable(MediaType.parse(type)!!)).isEqualTo(readable)
    }
}

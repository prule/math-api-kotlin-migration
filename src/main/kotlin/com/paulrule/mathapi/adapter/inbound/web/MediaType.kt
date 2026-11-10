package com.paulrule.mathapi.adapter.inbound.web

import java.nio.charset.Charset
import java.nio.charset.IllegalCharsetNameException

/**
 * The slice of Spring's MimeType/MediaType rules the calculations endpoint shows on the wire (RB-27, RB-28):
 * parsing, compatibility with `*+json` suffixes, choosing the response type, and the 415 detail format.
 */
internal data class MediaType(
    val type: String,
    val subtype: String,
    val parameters: List<Pair<String, String>> = emptyList(),
) {
    val isWildcardType get() = type == "*"
    val isWildcardSubtype get() = subtype == "*" || subtype.startsWith("*+")
    val isConcrete get() = !isWildcardType && !isWildcardSubtype
    private val suffix get() = subtype.substringAfter('+', "").ifEmpty { null }

    private fun parameter(name: String) = parameters.firstOrNull { it.first == name }?.second

    /** The declared charset, if any (validated when parsed). */
    val charset: String? get() = parameter("charset")

    // Concrete, then `x/*` or `x/*+json`, then any type.
    private val specificityRank get() =
        if (isWildcardType) {
            2
        } else if (isWildcardSubtype) {
            1
        } else {
            0
        }
    private val quality get() = parameter("q")?.toDouble() ?: 1.0

    fun withoutQuality() = copy(parameters = parameters.filterNot { it.first == "q" })

    fun isCompatibleWith(other: MediaType): Boolean {
        if (isWildcardType || other.isWildcardType) return true
        if (type != other.type) return false
        if (subtype == other.subtype) return true
        if (!isWildcardSubtype && !other.isWildcardSubtype) return false
        if (subtype == "*" || other.subtype == "*") return true
        if (isWildcardSubtype && suffix != null) return suffix == other.subtype || suffix == other.suffix
        if (other.isWildcardSubtype && other.suffix != null) return subtype == other.suffix || other.suffix == suffix
        return false
    }

    private fun isLessSpecific(other: MediaType): Boolean {
        if (isWildcardType != other.isWildcardType) return isWildcardType
        if (isWildcardSubtype != other.isWildcardSubtype) return isWildcardSubtype
        if (type == other.type && subtype == other.subtype) return parameters.size < other.parameters.size
        return false
    }

    /** Spring's 415 rendering: parameters joined with ';' and the forced request charset appended when none is given. */
    fun toUnsupportedDetailForm(): String = toString() + if (parameter("charset") == null) ";charset=UTF-8" else ""

    override fun toString() = "$type/$subtype" + parameters.joinToString("") { (name, value) -> ";$name=$value" }

    companion object {
        val JSON = MediaType("application", "json")
        private val ANY_JSON = MediaType("application", "*+json")
        private val ALL = MediaType("*", "*")

        /** What the endpoint reads and writes. YAML is absent on purpose: MIGRATION-LOG Accepted difference 1. */
        val PRODUCIBLE = listOf(JSON, ANY_JSON)

        private val TOKEN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

        /**
         * Parses one media type, as Spring does: type and subtype and parameter names lowercased, a parameter without
         * a value ignored, `q` within 0..1, `charset` a known charset. Null when it is not valid.
         */
        fun parse(value: String): MediaType? {
            val parts = splitOutsideQuotes(value, ';').map { it.trim() }
            val (type, subtype) = parts[0].split('/').takeIf { it.size == 2 && it.all(TOKEN::matches) } ?: return null
            if (type == "*" && subtype != "*") return null
            val parameters =
                parts.drop(1).filter { '=' in it }.map { parameter ->
                    val (name, raw) = parameter.split('=', limit = 2)
                    if (!TOKEN.matches(name.trim())) return null
                    name.trim().lowercase() to raw.trim()
                }
            val quality = parameters.firstOrNull { it.first == "q" }?.second
            if (quality != null && quality.toDoubleOrNull()?.let { it in 0.0..1.0 } != true) return null
            val charset = parameters.firstOrNull { it.first == "charset" }?.second?.trim('"')
            if (charset != null && !isKnownCharset(charset)) return null
            return MediaType(type.lowercase(), subtype.lowercase(), parameters)
        }

        private fun isKnownCharset(name: String) =
            try {
                Charset.isSupported(name)
            } catch (_: IllegalCharsetNameException) {
                false
            }

        /** Every type in an Accept header (empty entries skipped), or null when any is invalid (answered without a body). */
        fun parseAccept(header: String?): List<MediaType>? {
            val entries = header?.let { splitOutsideQuotes(it, ',') }?.filter { it.isNotBlank() }.orEmpty()
            if (entries.isEmpty()) return listOf(ALL)
            return entries.map { parse(it) ?: return null }
        }

        /** Splits on [separator] except inside double quotes, so `boundary="a;b"` stays one parameter. */
        private fun splitOutsideQuotes(
            value: String,
            separator: Char,
        ): List<String> {
            val parts = mutableListOf<String>()
            var quoted = false
            var start = 0
            value.forEachIndexed { i, c ->
                if (c == '"') quoted = !quoted
                if (c == separator && !quoted) {
                    parts += value.substring(start, i)
                    start = i + 1
                }
            }
            return parts + value.substring(start)
        }

        /**
         * Spring's choice of the response type: Accept types ordered by quality, then specificity, then as written;
         * the most specific compatible pair of each; the first concrete one wins, else the first producible type.
         * Null when nothing is compatible.
         */
        fun select(
            accepted: List<MediaType>,
            producible: List<MediaType> = PRODUCIBLE,
        ): MediaType? {
            val ordered = accepted.sortedWith(compareByDescending<MediaType> { it.quality }.thenBy { it.specificityRank })
            val compatible =
                ordered.flatMap { accept ->
                    producible.filter { accept.isCompatibleWith(it) }.map { produce ->
                        if (accept.isLessSpecific(produce)) produce else accept
                    }
                }
            if (compatible.isEmpty()) return null
            return (compatible.firstOrNull { it.isConcrete } ?: producible.first()).withoutQuality()
        }

        /** A concrete JSON or `*+json` type. A wildcard request type is 415: MIGRATION-LOG Accepted difference 2. */
        fun isReadable(type: MediaType) = type.isConcrete && PRODUCIBLE.any { it.isCompatibleWith(type) }
    }
}

package com.cncverse.stremiobridge.network.geo

/**
 * Guesses which country's IP an extension needs.
 *
 * Extension languages are often wrong (many Indian sites are tagged "en"), so
 * country words in the extension's own names win: first its plugin/API names,
 * then its home-page section names. The declared language is only used when no
 * name mentions a country, and "en"/multi never maps to a country.
 */
object CountryResolver {

    data class Result(val country: String?, val source: String)

    /** ISO code → words that point at the country (lowercase, matched as whole words). */
    private val COUNTRY_WORDS: Map<String, List<String>> = mapOf(
        "IN" to listOf(
            "india", "indian", "hindi", "tamil", "telugu", "malayalam", "kannada", "marathi", "punjabi",
            "gujarati", "odia", "bhojpuri", "bollywood", "tollywood", "kollywood", "mollywood", "sandalwood",
            "desi", "jio", "jiotv", "jiocinema", "hotstar", "zee5", "sonyliv", "ipl",
        ),
        "BD" to listOf("bangladesh", "bangladeshi", "dhaka", "bdix", "bangla"),
        "PK" to listOf("pakistan", "pakistani", "urdu"),
        "LK" to listOf("sri lanka", "sinhala"),
        "NP" to listOf("nepal", "nepali"),
        "TR" to listOf("turkey", "turkiye", "türkiye", "turkish", "türk", "dizi"),
        "KR" to listOf("korea", "korean", "kdrama", "k-drama"),
        "JP" to listOf("japan", "japanese"),
        "CN" to listOf("china", "chinese", "mandarin"),
        "TW" to listOf("taiwan", "taiwanese"),
        "TH" to listOf("thailand", "thai"),
        "VN" to listOf("vietnam", "vietnamese"),
        "ID" to listOf("indonesia", "indonesian"),
        "MY" to listOf("malaysia", "malaysian"),
        "PH" to listOf("philippines", "filipino", "pinoy", "tagalog"),
        "IR" to listOf("iran", "iranian", "persian", "farsi"),
        "AE" to listOf("uae", "emirates", "dubai"),
        "SA" to listOf("saudi"),
        "EG" to listOf("egypt", "egyptian"),
        "RU" to listOf("russia", "russian"),
        "UA" to listOf("ukraine", "ukrainian"),
        "PL" to listOf("poland", "polish", "polska"),
        "DE" to listOf("germany", "german", "deutsch"),
        "FR" to listOf("france", "french", "français"),
        "IT" to listOf("italy", "italian", "italiano", "italia"),
        "ES" to listOf("spain", "españa", "castellano"),
        "MX" to listOf("mexico", "méxico", "mexican"),
        "AR" to listOf("argentina", "argentine"),
        "BR" to listOf("brazil", "brasil", "brazilian"),
        "PT" to listOf("portugal", "portuguese"),
        "NL" to listOf("netherlands", "dutch"),
        "GR" to listOf("greece", "greek"),
        "RO" to listOf("romania", "romanian"),
        "HU" to listOf("hungary", "hungarian"),
        "CZ" to listOf("czech", "czechia"),
        "IL" to listOf("israel", "israeli", "hebrew"),
        "GB" to listOf("uk", "britain", "british", "bbc", "iplayer"),
        "US" to listOf("usa", "american"),
        "CA" to listOf("canada", "canadian"),
        "AU" to listOf("australia", "australian"),
        "NG" to listOf("nigeria", "nigerian", "nollywood"),
    )

    /** Language → country, used only when no name mentions a country. */
    private val LANGUAGE_COUNTRY: Map<String, String> = mapOf(
        "hi" to "IN", "ta" to "IN", "te" to "IN", "ml" to "IN", "kn" to "IN", "mr" to "IN",
        "gu" to "IN", "pa" to "IN", "or" to "IN", "as" to "IN", "bn" to "IN", "bho" to "IN",
        "ur" to "PK", "si" to "LK", "ne" to "NP",
        "tr" to "TR", "ko" to "KR", "ja" to "JP", "zh" to "CN", "th" to "TH", "vi" to "VN",
        "id" to "ID", "ms" to "MY", "tl" to "PH", "fil" to "PH", "fa" to "IR", "ar" to "SA",
        "ru" to "RU", "uk" to "UA", "pl" to "PL", "de" to "DE", "fr" to "FR", "it" to "IT",
        "es" to "ES", "pt" to "BR", "nl" to "NL", "el" to "GR", "ro" to "RO", "hu" to "HU",
        "cs" to "CZ", "he" to "IL",
    )

    val KNOWN_COUNTRIES: Set<String> get() = COUNTRY_WORDS.keys + LANGUAGE_COUNTRY.values

    private val patterns: Map<String, Regex> = COUNTRY_WORDS.mapValues { (_, words) ->
        Regex("(?<![\\p{L}\\p{N}])(?:" + words.joinToString("|") { Regex.escape(it) } + ")(?![\\p{L}\\p{N}])",
            RegexOption.IGNORE_CASE)
    }

    /** "BdixDhakaFlix" → "Bdix Dhaka Flix" so CamelCase names match whole words. */
    private fun splitWords(s: String): String =
        s.replace(Regex("(?<=\\p{Ll})(?=\\p{Lu})|(?<=\\p{L})(?=\\p{N})|(?<=\\p{N})(?=\\p{L})|[_.]"), " ")

    private fun bestCountry(texts: List<String>): String? {
        if (texts.isEmpty()) return null
        val joined = texts.joinToString(" \u0000 ") { splitWords(it) }
        val counts = patterns.mapValues { (_, rx) -> rx.findAll(joined).count() }.filterValues { it > 0 }
        if (counts.isEmpty()) return null
        val top = counts.maxOf { it.value }
        val leaders = counts.filterValues { it == top }.keys
        // A tie between countries is ambiguous (e.g. a multi-region site) — no guess
        return leaders.singleOrNull()
    }

    fun resolve(
        names: List<String>,
        sectionNames: List<String>,
        languages: List<String?>,
    ): Result {
        bestCountry(names.filter { it.isNotBlank() })?.let { return Result(it, "name") }
        bestCountry(sectionNames.filter { it.isNotBlank() })?.let { return Result(it, "homepage") }
        for (lang in languages) {
            val code = lang?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_') ?: continue
            LANGUAGE_COUNTRY[code]?.let { return Result(it, "language:$code") }
        }
        return Result(null, "none")
    }
}

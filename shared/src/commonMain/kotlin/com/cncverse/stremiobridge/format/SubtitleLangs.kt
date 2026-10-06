package com.cncverse.stremiobridge.format

/**
 * Stremio names a subtitle track from its `lang`, which must be an ISO 639-2 code ("eng");
 * anything else shows as "Unknown". Extensions send free text instead: "English (Original
 * Audio)", "en (Hindi Audio)", "हिन्दी", "in_id"... This maps those to 639-2/B codes.
 */
object SubtitleLangs {
    /** ISO 639-1 -> 639-2/B */
    private val TWO = mapOf(
        "en" to "eng", "hi" to "hin", "ta" to "tam", "te" to "tel", "ml" to "mal", "kn" to "kan", "bn" to "ben",
        "mr" to "mar", "gu" to "guj", "pa" to "pan", "ur" to "urd", "or" to "ori", "as" to "asm", "ne" to "nep",
        "si" to "sin", "ar" to "ara", "fa" to "per", "he" to "heb", "iw" to "heb", "tr" to "tur", "ru" to "rus",
        "uk" to "ukr", "pl" to "pol", "cs" to "cze", "sk" to "slo", "hu" to "hun", "ro" to "rum", "bg" to "bul",
        "sr" to "srp", "hr" to "hrv", "bs" to "bos", "sl" to "slv", "el" to "gre", "de" to "ger", "fr" to "fre",
        "es" to "spa", "pt" to "por", "it" to "ita", "nl" to "dut", "sv" to "swe", "no" to "nor", "nb" to "nor",
        "da" to "dan", "fi" to "fin", "is" to "ice", "et" to "est", "lv" to "lav", "lt" to "lit", "zh" to "chi",
        "ja" to "jpn", "ko" to "kor", "th" to "tha", "vi" to "vie", "id" to "ind", "in" to "ind", "ms" to "may",
        "tl" to "tgl", "fil" to "fil", "my" to "bur", "km" to "khm", "lo" to "lao", "mn" to "mon", "ka" to "geo",
        "hy" to "arm", "az" to "aze", "kk" to "kaz", "uz" to "uzb", "sw" to "swa", "af" to "afr", "am" to "amh",
        "sq" to "alb", "mk" to "mac", "ca" to "cat", "eu" to "baq", "gl" to "glg", "cy" to "wel", "ga" to "gle",
    )

    /** English and native names (lowercase) -> 639-2/B */
    private val NAMES = mapOf(
        "english" to "eng", "hindi" to "hin", "हिन्दी" to "hin", "हिंदी" to "hin", "tamil" to "tam", "தமிழ்" to "tam",
        "telugu" to "tel", "తెలుగు" to "tel", "malayalam" to "mal", "മലയാളം" to "mal", "kannada" to "kan",
        "ಕನ್ನಡ" to "kan", "bengali" to "ben", "bangla" to "ben", "বাংলা" to "ben", "marathi" to "mar", "मराठी" to "mar",
        "gujarati" to "guj", "punjabi" to "pan", "urdu" to "urd", "اُردُو" to "urd", "اردو" to "urd", "odia" to "ori",
        "nepali" to "nep", "sinhala" to "sin", "arabic" to "ara", "العربية" to "ara", "اَلْعَرَبِيَّةُ" to "ara",
        "persian" to "per", "farsi" to "per", "hebrew" to "heb", "turkish" to "tur", "türkçe" to "tur",
        "russian" to "rus", "русский" to "rus", "ukrainian" to "ukr", "polish" to "pol", "polski" to "pol",
        "czech" to "cze", "hungarian" to "hun", "romanian" to "rum", "bulgarian" to "bul", "serbian" to "srp",
        "croatian" to "hrv", "greek" to "gre", "german" to "ger", "deutsch" to "ger", "french" to "fre",
        "français" to "fre", "francais" to "fre", "spanish" to "spa", "español" to "spa", "espanol" to "spa",
        "latin american spanish" to "spa", "castilian" to "spa", "portuguese" to "por", "português" to "por",
        "portugues" to "por", "brazilian portuguese" to "por", "italian" to "ita", "italiano" to "ita",
        "dutch" to "dut", "swedish" to "swe", "norwegian" to "nor", "danish" to "dan", "finnish" to "fin",
        "chinese" to "chi", "中文" to "chi", "简体中文" to "chi", "繁體中文" to "chi", "mandarin" to "chi",
        "cantonese" to "chi", "japanese" to "jpn", "日本語" to "jpn", "korean" to "kor", "한국어" to "kor",
        "thai" to "tha", "ไทย" to "tha", "vietnamese" to "vie", "tiếng việt" to "vie", "indonesian" to "ind",
        "bahasa indonesia" to "ind", "malay" to "may", "bahasa melayu" to "may", "filipino" to "fil",
        "tagalog" to "tgl", "burmese" to "bur", "khmer" to "khm",
    )

    private val CODES = (TWO.values + NAMES.values).toSet() + setOf("fra", "deu", "zho", "msa", "fas", "nld", "ces", "ell", "ron", "slk")

    /** The 639-2 code for [raw], or [raw] unchanged when the language is not recognised. */
    fun normalize(raw: String): String {
        val base = raw.substringBefore('(').substringBefore('[').trim().trimEnd('-', ':', ',').trim()
        if (base.isEmpty()) return raw
        val lower = base.lowercase()
        NAMES[lower]?.let { return it }
        if (lower in CODES) return lower
        // "en", "in_id", "pt-BR", "zh-Hans", "en 1"
        val code = lower.split('_', '-', ' ').first()
        TWO[code]?.let { return it }
        if (code in CODES) return code
        // "English SDH", "Spanish (Latin America)", "English 2"
        NAMES.entries.firstOrNull { lower.startsWith(it.key + " ") }?.let { return it.value }
        return raw
    }
}

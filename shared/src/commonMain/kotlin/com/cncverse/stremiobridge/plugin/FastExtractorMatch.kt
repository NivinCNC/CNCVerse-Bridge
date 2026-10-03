package com.cncverse.stremiobridge.plugin

import com.lagradost.cloudstream3.utils.Levenshtein

/**
 * Fast path for CloudStream's fuzzy extractor fallback.
 *
 * When no extractor's mainUrl is a prefix of a link, `loadExtractor` asks
 * `Levenshtein.partialRatio(mainUrl, url) > 80` for every registered extractor
 * (~600). Each call builds a full edit-distance matrix, which made this ~20% of
 * the server's CPU (HubCloud / 4K HDHUB resolve many links per title).
 *
 * `libs/cloudstream-api.jar` is patched (see libs/README.md) so that single call
 * site invokes [partialRatioForExtractor] instead. The answer to "> 80" is always
 * the same as the original:
 *
 * partialRatio compares the shorter string with windows of the longer one (same
 * length, clipped at the end); a window's score is 2·LCS / (n + w) because
 * basicRatio uses the indel distance. LCS ≤ the characters the two have in common,
 * so if even the best window can't reach 80 with that bound, the real score can't
 * either and 0 is returned (the caller only checks "> 80"). Otherwise the real
 * partialRatio runs.
 */
object FastExtractorMatch {
    private const val THRESHOLD = 80

    /** Same descriptor as `Levenshtein.partialRatio$default`, so the patched call site needs no other change. */
    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun partialRatioForExtractor(
        lev: Levenshtein,
        s1: String,
        s2: String,
        processor: Function1<String, String>?,
        mask: Int,
        marker: Any?,
    ): Int {
        // loadExtractor uses the default (identity) processor; anything else goes to the original
        if (mask and 4 == 0 && processor != null) return Levenshtein.partialRatio(s1, s2, processor)
        if (cannotExceedThreshold(s1, s2)) return 0
        return Levenshtein.partialRatio(s1, s2)
    }

    /** True when no window of the longer string can score above [THRESHOLD] against the shorter one. */
    internal fun cannotExceedThreshold(s1: String, s2: String): Boolean {
        val shorter: String
        val longer: String
        if (s1.length < s2.length) { shorter = s1; longer = s2 } else { shorter = s2; longer = s1 }
        val n = shorter.length
        val m = longer.length
        if (n == 0) return false
        // ASCII only (URLs); anything else takes the exact path
        for (c in shorter) if (c.code >= 128) return false
        for (c in longer) if (c.code >= 128) return false

        val need = IntArray(128)
        for (c in shorter) need[c.code]++
        val have = IntArray(128)
        var common = 0 // characters the current window shares with the shorter string

        // Window [start, end): end = min(start + n, m). Slide start from 0 to m - 1.
        var end = 0
        for (start in 0 until m) {
            val target = minOf(start + n, m)
            while (end < target) {
                val c = longer[end].code
                if (have[c] < need[c]) common++
                have[c]++
                end++
            }
            val w = end - start
            // score ≤ 2·common / (n + w); "> 80%" ⇔ 200·common > 80·(n + w) ⇔ 5·common > 2·(n + w)
            if (5 * common > 2 * (n + w)) return false
            val c = longer[start].code
            have[c]--
            if (have[c] < need[c]) common--
        }
        return true
    }
}

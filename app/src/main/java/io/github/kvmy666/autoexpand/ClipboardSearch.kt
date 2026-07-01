package io.github.kvmy666.autoexpand

import java.text.Normalizer

/**
 * Pure, UI-free ranking logic for clipboard search.
 *
 * Runs in-memory over the full (≤ maxEntries, default 500) entry list returned by
 * [ClipboardDatabase.getAll]. With at most a few hundred short strings this is
 * sub-millisecond and fully deterministic — no SQL LIKE escaping, no FTS table.
 *
 * Matching is multi-word AND, case- and accent-insensitive, ranked
 * exact > prefix > word-start > substring. Each whitespace-separated token must
 * appear as a contiguous substring (no fuzzy/subsequence — it over-matches long
 * entries). Match index ranges are reported against the
 * ORIGINAL (un-normalized) text for highlighting, which is valid because
 * [normalize] is a per-character 1:1 transform after combining marks are removed.
 */
object ClipboardSearch {

    data class Scored(
        val entry: ClipboardDatabase.Entry,
        val score: Int,
        val matchRanges: List<IntRange>
    )

    // Score tiers per token (highest wins for that token).
    private const val EXACT = 1000
    private const val PREFIX = 800
    private const val WORD_START = 600
    private const val SUBSTRING = 400

    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    /**
     * Lowercase + strip diacritics. NFD decomposes accented chars into base +
     * combining mark; removing the marks keeps a 1:1 mapping to the original
     * string's character positions (base letters are preserved in order), so the
     * resulting indices line up with the original text for highlighting.
     */
    fun normalize(s: String): String =
        COMBINING_MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase()

    /**
     * Filter + rank [entries] for [query]. A blank query returns every entry in
     * the input order, unscored (caller keeps the active sort mode). Otherwise an
     * entry is kept only if EVERY whitespace-separated query token matches its
     * text; results are ordered by total score, then pinned, favorite, recency.
     */
    fun search(entries: List<ClipboardDatabase.Entry>, query: String): List<Scored> {
        val tokens = normalize(query).split(' ', '\t', '\n').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) {
            return entries.map { Scored(it, 0, emptyList()) }
        }

        val out = ArrayList<Scored>(entries.size)
        for (entry in entries) {
            val hay = normalize(entry.text)
            var total = 0
            val ranges = ArrayList<IntRange>(tokens.size)
            var allMatched = true
            for (token in tokens) {
                val m = matchToken(hay, token)
                if (m == null) { allMatched = false; break }
                total += m.score
                if (m.range != null) ranges.add(m.range)
            }
            if (allMatched) out.add(Scored(entry, total, mergeRanges(ranges)))
        }

        out.sortWith(
            compareByDescending<Scored> { it.score }
                .thenByDescending { it.entry.isPinned }
                .thenByDescending { it.entry.isFavorite }
                .thenByDescending { it.entry.timestamp }
        )
        return out
    }

    private class TokenMatch(val score: Int, val range: IntRange?)

    private fun matchToken(hay: String, token: String): TokenMatch? {
        if (hay == token) return TokenMatch(EXACT, hay.indices)
        if (hay.startsWith(token)) return TokenMatch(PREFIX, 0 until token.length)

        val idx = hay.indexOf(token)
        if (idx >= 0) {
            val atWordStart = idx == 0 || !hay[idx - 1].isLetterOrDigit()
            val base = if (atWordStart) WORD_START else SUBSTRING
            // Earlier matches rank slightly higher.
            val score = (base - idx).coerceAtLeast(base - 99)
            return TokenMatch(score, idx until idx + token.length)
        }

        // No contiguous match → token fails. (We intentionally do NOT fall back to
        // subsequence/fuzzy matching: on long clipboard entries it matches almost
        // any letters-in-order, producing confusing false positives.)
        return null
    }

    /** Merge overlapping/adjacent ranges so highlight spans don't collide. */
    private fun mergeRanges(ranges: List<IntRange>): List<IntRange> {
        if (ranges.size <= 1) return ranges
        val sorted = ranges.sortedBy { it.first }
        val merged = ArrayList<IntRange>(sorted.size)
        var cur = sorted[0]
        for (i in 1 until sorted.size) {
            val r = sorted[i]
            cur = if (r.first <= cur.last + 1) cur.first..maxOf(cur.last, r.last)
                  else { merged.add(cur); r }
        }
        merged.add(cur)
        return merged
    }
}

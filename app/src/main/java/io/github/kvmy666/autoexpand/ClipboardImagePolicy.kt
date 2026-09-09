package io.github.kvmy666.autoexpand

/**
 * Pure, UI-free, Android-free policy for the clipboard image vault: size caps, sampling
 * math, eviction order and budget arithmetic.
 *
 * Everything here is deliberately free of `android.*` so it runs on the JVM under plain
 * JUnit with no Robolectric — see `ClipboardImagePolicyTest`. The Android-touching half
 * (decode, compress, unlink) lives in `ClipboardImageStore`.
 */
object ClipboardImagePolicy {

    /** Source images larger than this are skipped outright, before any decode. */
    const val MAX_SOURCE_BYTES = 25L * 1024 * 1024

    const val THUMB_MAX_EDGE = 256
    const val FULL_MAX_EDGE = 2048
    const val THUMB_QUALITY = 75
    const val FULL_QUALITY = 85

    const val DEFAULT_MAX_ENTRIES = 50
    const val DEFAULT_MAX_BYTES = 100L * 1024 * 1024

    val ENTRY_OPTIONS = listOf(20, 50, 100)
    val BYTE_OPTIONS = listOf(25L, 50L, 100L, 200L).map { it * 1024 * 1024 }

    /** One stored image entry, as far as the budget is concerned. */
    data class Candidate(
        val id: Long,
        val hash: String?,
        /** Bytes of the `full` artifact. Entries sharing a hash share these bytes. */
        val bytes: Long,
        val pinned: Boolean,
        val timestamp: Long
    )

    /**
     * @param evict entry ids to remove, oldest-unpinned first.
     * @param blocked true when the budget still cannot be met because only pinned entries
     *   remain. The caller must then refuse the new image and surface a hint rather than
     *   evict a pin.
     */
    data class Plan(val evict: List<Long>, val blocked: Boolean)

    /** Bytes actually on disk: one charge per distinct hash. */
    fun distinctBytes(entries: Collection<Candidate>): Long =
        entries.filter { it.hash != null }
            .groupBy { it.hash }
            .values
            .sumOf { group -> group.maxOf { it.bytes } }

    /**
     * Decide what must go for [incomingBytes] to fit. [incomingHash] matters: re-copying an
     * image already stored adds an entry but no bytes.
     *
     * Pinned entries are never selected. If the budget cannot be met without one, the plan
     * comes back `blocked` with whatever eviction it managed — the caller discards the plan
     * and keeps the vault as-is.
     */
    fun planEviction(
        existing: List<Candidate>,
        incomingHash: String?,
        incomingBytes: Long,
        maxEntries: Int,
        maxBytes: Long
    ): Plan {
        val survivors = existing.toMutableList()
        val evicted = mutableListOf<Long>()

        // An incoming duplicate of an already-stored hash costs no new bytes.
        val incomingIsNewHash = incomingHash == null || existing.none { it.hash == incomingHash }

        fun entryCount() = survivors.size + 1
        fun byteTotal(): Long {
            val base = distinctBytes(survivors)
            return if (incomingIsNewHash) base + incomingBytes else base
        }

        while (entryCount() > maxEntries || byteTotal() > maxBytes) {
            val victim = survivors
                .filter { !it.pinned }
                .minByOrNull { it.timestamp }
                ?: return Plan(evicted, blocked = true)
            survivors.remove(victim)
            evicted.add(victim.id)
        }
        return Plan(evicted, blocked = false)
    }

    /**
     * `BitmapFactory.Options.inSampleSize` for decoding an image of [srcW]x[srcH] down to
     * at most [maxEdge] on its long side — always a power of two, never below 1, and
     * always chosen so the decoded result is still >= the target (we downscale exactly
     * afterwards). This is what keeps a 108 MP source from ever being fully decoded.
     */
    fun sampleSizeFor(srcW: Int, srcH: Int, maxEdge: Int): Int {
        if (srcW <= 0 || srcH <= 0 || maxEdge <= 0) return 1
        var sample = 1
        var longEdge = maxOf(srcW, srcH)
        while (longEdge / 2 >= maxEdge) {
            longEdge /= 2
            sample *= 2
        }
        return sample
    }

    /** Target pixel size preserving aspect ratio, capped at [maxEdge]. Never upscales. */
    fun scaledSize(srcW: Int, srcH: Int, maxEdge: Int): Pair<Int, Int> {
        if (srcW <= 0 || srcH <= 0) return 0 to 0
        val longEdge = maxOf(srcW, srcH)
        if (longEdge <= maxEdge) return srcW to srcH
        val ratio = maxEdge.toDouble() / longEdge
        return maxOf(1, Math.round(srcW * ratio).toInt()) to
            maxOf(1, Math.round(srcH * ratio).toInt())
    }

    /** "1.4 MB", "812 KB" — the row label and the budget readout. */
    fun formatBytes(b: Long): String = when {
        b >= 1024L * 1024 * 1024 -> String.format("%.1f GB", b / (1024.0 * 1024 * 1024))
        b >= 1024L * 1024 -> String.format("%.1f MB", b / (1024.0 * 1024))
        b >= 1024L -> "${b / 1024} KB"
        else -> "$b B"
    }

    /** `Image · 1080×1920 · 240 KB`, with a GIF marker when only a first frame was kept. */
    fun rowLabel(width: Int, height: Int, bytes: Long, animated: Boolean): String {
        val dims = if (width > 0 && height > 0) " · ${width}×${height}" else ""
        val kind = if (animated) "GIF" else "Image"
        return "$kind$dims · ${formatBytes(bytes)}"
    }
}

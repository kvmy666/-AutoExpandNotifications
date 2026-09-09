package io.github.kvmy666.autoexpand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A-series coverage for the budget/eviction half of the spec (tests 4, 5, 6) plus the
 * sampling math that keeps a huge source from ever being fully decoded.
 *
 * Pure JVM — no Robolectric, no Android.
 */
class ClipboardImagePolicyTest {

    private val MB = 1024L * 1024

    private fun c(id: Long, bytes: Long, pinned: Boolean = false, ts: Long = id, hash: String? = "h$id") =
        ClipboardImagePolicy.Candidate(id, hash, bytes, pinned, ts)

    // ── A4: entry-count budget ──────────────────────────────────────────

    @Test
    fun `entry budget evicts oldest unpinned first`() {
        val existing = listOf(c(1, MB, ts = 100), c(2, MB, ts = 200), c(3, MB, ts = 300))
        val plan = ClipboardImagePolicy.planEviction(
            existing, incomingHash = "new", incomingBytes = MB, maxEntries = 3, maxBytes = 100 * MB
        )
        assertFalse(plan.blocked)
        assertEquals(listOf(1L), plan.evict)
    }

    @Test
    fun `entry budget never evicts a pin`() {
        // Oldest is pinned, so the next-oldest unpinned must go instead.
        val existing = listOf(c(1, MB, pinned = true, ts = 100), c(2, MB, ts = 200), c(3, MB, ts = 300))
        val plan = ClipboardImagePolicy.planEviction(
            existing, "new", MB, maxEntries = 3, maxBytes = 100 * MB
        )
        assertFalse(plan.blocked)
        assertEquals(listOf(2L), plan.evict)
    }

    @Test
    fun `all-pinned vault blocks the save rather than evicting a pin`() {
        val existing = listOf(
            c(1, MB, pinned = true, ts = 100),
            c(2, MB, pinned = true, ts = 200)
        )
        val plan = ClipboardImagePolicy.planEviction(
            existing, "new", MB, maxEntries = 2, maxBytes = 100 * MB
        )
        assertTrue("must refuse rather than evict a pin", plan.blocked)
    }

    // ── A5: byte budget ─────────────────────────────────────────────────

    @Test
    fun `byte budget evicts until under budget`() {
        val existing = listOf(c(1, 40 * MB, ts = 1), c(2, 40 * MB, ts = 2), c(3, 10 * MB, ts = 3))
        // 90 MB stored + 30 MB incoming = 120 MB against a 100 MB cap.
        val plan = ClipboardImagePolicy.planEviction(
            existing, "new", 30 * MB, maxEntries = 100, maxBytes = 100 * MB
        )
        assertFalse(plan.blocked)
        assertEquals(listOf(1L), plan.evict)   // dropping 40 MB is enough
    }

    @Test
    fun `byte budget never evicts a pin`() {
        val existing = listOf(c(1, 90 * MB, pinned = true, ts = 1))
        val plan = ClipboardImagePolicy.planEviction(
            existing, "new", 30 * MB, maxEntries = 100, maxBytes = 100 * MB
        )
        assertTrue(plan.blocked)
    }

    // ── dedup interaction: a re-copy costs an entry but no bytes ────────

    @Test
    fun `re-copying a stored image adds no bytes to the budget`() {
        val existing = listOf(c(1, 90 * MB, ts = 1, hash = "dup"))
        // Same hash incoming: byte total stays 90 MB, so nothing needs evicting.
        val plan = ClipboardImagePolicy.planEviction(
            existing, incomingHash = "dup", incomingBytes = 90 * MB,
            maxEntries = 100, maxBytes = 100 * MB
        )
        assertFalse(plan.blocked)
        assertTrue(plan.evict.isEmpty())
    }

    @Test
    fun `distinctBytes charges a shared hash once`() {
        val entries = listOf(
            c(1, 10 * MB, hash = "same"),
            c(2, 10 * MB, hash = "same"),
            c(3, 5 * MB, hash = "other")
        )
        assertEquals(15 * MB, ClipboardImagePolicy.distinctBytes(entries))
    }

    // ── A6: source size cap ─────────────────────────────────────────────

    @Test
    fun `source cap is 25 MB`() {
        assertEquals(25L * 1024 * 1024, ClipboardImagePolicy.MAX_SOURCE_BYTES)
    }

    // ── sampling math ───────────────────────────────────────────────────

    @Test
    fun `sampleSize never decodes a huge source at full resolution`() {
        // 12000x9000 down to a 256 px thumb.
        val s = ClipboardImagePolicy.sampleSizeFor(12000, 9000, 256)
        assertTrue("sample must be a power of two", s > 0 && (s and (s - 1)) == 0)
        assertTrue("decoded long edge must be under 512", 12000 / s < 512)
    }

    @Test
    fun `sampleSize is 1 when the source already fits`() {
        assertEquals(1, ClipboardImagePolicy.sampleSizeFor(200, 100, 256))
    }

    @Test
    fun `sampleSize tolerates garbage bounds`() {
        assertEquals(1, ClipboardImagePolicy.sampleSizeFor(0, 0, 256))
        assertEquals(1, ClipboardImagePolicy.sampleSizeFor(-5, 10, 256))
    }

    @Test
    fun `scaledSize preserves aspect ratio and caps the long edge`() {
        val (w, h) = ClipboardImagePolicy.scaledSize(4000, 2000, 2048)
        assertEquals(2048, w)
        assertEquals(1024, h)
    }

    @Test
    fun `scaledSize never upscales`() {
        val (w, h) = ClipboardImagePolicy.scaledSize(100, 50, 2048)
        assertEquals(100, w)
        assertEquals(50, h)
    }

    // ── labels ──────────────────────────────────────────────────────────

    @Test
    fun `row label matches the spec shape`() {
        assertEquals("Image · 1080×1920 · 240 KB",
            ClipboardImagePolicy.rowLabel(1080, 1920, 240 * 1024, animated = false))
    }

    @Test
    fun `animated sources are labelled GIF`() {
        assertTrue(ClipboardImagePolicy.rowLabel(100, 100, 1024, animated = true).startsWith("GIF"))
    }
}

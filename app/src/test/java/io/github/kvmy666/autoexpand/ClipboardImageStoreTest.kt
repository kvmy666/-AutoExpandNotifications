package io.github.kvmy666.autoexpand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A17-series coverage for the file layer: the GC keep-set contract and the animated-source
 * sniffer.
 *
 * The decode/compress path itself needs a real `BitmapFactory`, so it is exercised on device
 * rather than here (see the C-series checklist); what is covered here is the logic that can
 * silently corrupt the store — deleting a file something still points at.
 */
class ClipboardImageStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // newFolder() with no name so repeated calls inside one test get unique directories;
    // newFolder("clipimg") throws the second time.
    private fun store() = ClipboardImageStore(tmp.newFolder())

    private fun writeArtifacts(s: ClipboardImageStore, hash: String) {
        s.thumbFile(hash).parentFile?.mkdirs()
        s.fullFile(hash).parentFile?.mkdirs()
        s.thumbFile(hash).writeBytes(ByteArray(64) { 1 })
        s.fullFile(hash).writeBytes(ByteArray(512) { 2 })
    }

    // ── A17: GC must never touch a referenced file ──────────────────────

    @Test
    fun `gc removes orphans and keeps everything referenced`() {
        val s = store()
        writeArtifacts(s, "keepme")
        writeArtifacts(s, "orphan")

        val res = s.gc(keep = setOf("keepme"))

        assertTrue("referenced thumb must survive", s.thumbFile("keepme").isFile)
        assertTrue("referenced full must survive", s.fullFile("keepme").isFile)
        assertFalse(s.fullFile("orphan").isFile)
        assertFalse(s.thumbFile("orphan").isFile)
        assertEquals(2, res.orphanFilesDeleted)   // thumb + full
    }

    @Test
    fun `gc with an empty keep set still never throws`() {
        val s = store()
        writeArtifacts(s, "a")
        val res = s.gc(emptySet())
        assertEquals(2, res.orphanFilesDeleted)
    }

    @Test
    fun `gc sweeps interrupted temp files`() {
        val s = store()
        writeArtifacts(s, "live")
        s.fullFile("live").parentFile!!.resolve("half.webp.tmp").writeBytes(ByteArray(8))

        val res = s.gc(setOf("live"))

        assertTrue(s.fullFile("live").isFile)
        assertTrue("temp leftovers must be swept", res.tempFilesDeleted >= 1)
    }

    @Test
    fun `deleteHash removes both artifacts`() {
        val s = store()
        writeArtifacts(s, "gone")
        assertTrue(s.hasBytes("gone"))
        assertTrue(s.deleteHash("gone"))
        assertFalse(s.hasBytes("gone"))
        assertFalse(s.thumbFile("gone").isFile)
    }

    @Test
    fun `hasBytes is false for a zero-length file`() {
        val s = store()
        s.fullFile("empty").parentFile?.mkdirs()
        s.fullFile("empty").writeBytes(ByteArray(0))
        assertFalse(s.hasBytes("empty"))
    }

    // ── animated-source sniffing ────────────────────────────────────────
    //
    // Reflection is used because the sniffer is private; it is worth testing directly
    // because a false positive here mislabels every static WebP as a GIF, and WebP is the
    // format we re-encode INTO — so our own stored files round-trip through it.

    private fun sniff(bytes: ByteArray): Boolean {
        val m = ClipboardImageStore::class.java.getDeclaredMethod(
            "looksAnimated", ByteArray::class.java, Int::class.javaPrimitiveType
        )
        m.isAccessible = true
        val padded = bytes.copyOf(maxOf(32, bytes.size))
        return m.invoke(store(), padded, bytes.size) as Boolean
    }

    private fun webp(chunk: String, flags: Int): ByteArray {
        val b = ByteArray(32)
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(b, 0)
        "WEBP".toByteArray(Charsets.US_ASCII).copyInto(b, 8)
        chunk.toByteArray(Charsets.US_ASCII).copyInto(b, 12)
        b[20] = flags.toByte()
        return b
    }

    @Test
    fun `GIF89a is animated`() {
        assertTrue(sniff("GIF89a".toByteArray(Charsets.US_ASCII).copyOf(32)))
    }

    @Test
    fun `GIF87a is animated`() {
        assertTrue(sniff("GIF87a".toByteArray(Charsets.US_ASCII).copyOf(32)))
    }

    @Test
    fun `static lossy WebP is not animated`() {
        assertFalse("VP8 is single-frame by definition", sniff(webp("VP8 ", 0)))
    }

    @Test
    fun `static lossless WebP is not animated`() {
        assertFalse(sniff(webp("VP8L", 0)))
    }

    @Test
    fun `extended WebP without the animation flag is not animated`() {
        // This is the regression that mislabelled our own re-encoded files as GIFs.
        assertFalse(sniff(webp("VP8X", 0x10)))   // has ICC/EXIF bits, no ANIM bit
    }

    @Test
    fun `extended WebP with the animation flag is animated`() {
        assertTrue(sniff(webp("VP8X", 0x02)))
    }

    @Test
    fun `a PNG is not animated`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyOf(32)
        assertFalse(sniff(png))
    }

    @Test
    fun `a truncated header never throws`() {
        assertFalse(sniff(ByteArray(3)))
        assertFalse(sniff(ByteArray(0)))
    }
}

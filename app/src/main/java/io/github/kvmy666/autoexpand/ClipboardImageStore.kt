package io.github.kvmy666.autoexpand

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Byte-level storage for clipboard images.
 *
 * ## Where the files live
 *
 * `<Gboard filesDir>/ae_clipimg/{thumb,full}/<sha256>.webp`
 *
 * Gboard's own private data dir — the same place [ClipboardDatabase] already sits, because
 * both the capture listener and the vault UI run inside the Gboard process. That makes the
 * spec's "cross-process file access" problem disappear: there is exactly one writer and one
 * reader and they are the same process. No world-readable path, no `su`, no chmod, nothing
 * in `/data/local/tmp`. Files inherit Gboard's uid and the default 0700 app-dir mode, so
 * nothing else on the device can read a copied image.
 *
 * ## Why content hashing
 *
 * Files are named by the SHA-256 of the **source** bytes, so copying the same image twice
 * writes one file and creates two rows. Deletion of bytes is driven entirely by
 * [ClipboardDatabase.hashRefCount] — this class never decides on its own that a file is
 * unused, except in [gc], which is given the keep-set explicitly.
 *
 * Every public method swallows its own failures and returns null/false. Nothing here may
 * throw into the Gboard process.
 */
class ClipboardImageStore(private val root: File) {

    constructor(ctx: Context) : this(File(ctx.filesDir, DIR_NAME))

    companion object {
        const val DIR_NAME = "ae_clipimg"
        private const val THUMB = "thumb"
        private const val FULL = "full"
        private const val EXT = ".webp"
    }

    data class Saved(
        val hash: String,
        val thumbPath: String,
        val fullPath: String,
        val width: Int,
        val height: Int,
        val bytes: Long,
        val animated: Boolean
    )

    private val thumbDir get() = File(root, THUMB)
    private val fullDir get() = File(root, FULL)

    private fun ensureDirs(): Boolean = try {
        thumbDir.mkdirs(); fullDir.mkdirs()
        thumbDir.isDirectory && fullDir.isDirectory
    } catch (_: Throwable) {
        false
    }

    fun thumbFile(hash: String) = File(thumbDir, hash + EXT)
    fun fullFile(hash: String) = File(fullDir, hash + EXT)

    /**
     * Copy the bytes behind [uri], hash them, and write the thumb + full artifacts.
     *
     * Content URIs are transient — the grant dies when the source app releases the clip —
     * so this must run promptly, but off the host's main thread. Returns null on any
     * failure (revoked grant, undecodable bytes, oversize source, no space); the caller
     * inserts nothing, which is what keeps a broken row out of the list.
     */
    fun saveFromUri(ctx: Context, uri: Uri): Saved? {
        if (!ensureDirs()) return null
        var temp: File? = null
        try {
            // 1. Stream to a temp file while hashing. Streaming (rather than reading a
            //    25 MB array) keeps peak heap low in a host process we do not own, and
            //    lets us decode bounds and pixels from disk afterwards.
            temp = File(root, "in_${System.nanoTime()}.tmp")
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            val head = ByteArray(32)
            var headLen = 0

            val input: InputStream = ctx.contentResolver.openInputStream(uri) ?: return null
            input.use { ins ->
                FileOutputStream(temp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        if (headLen < head.size) {
                            val take = minOf(head.size - headLen, n)
                            System.arraycopy(buf, 0, head, headLen, take)
                            headLen += take
                        }
                        total += n
                        // Bail the moment we cross the cap, without buffering the rest.
                        if (total > ClipboardImagePolicy.MAX_SOURCE_BYTES) return null
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
            if (total <= 0L) return null

            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val animated = looksAnimated(head, headLen)

            // 2. Already stored? Reuse the bytes verbatim — this is the dedup path.
            val thumb = thumbFile(hash)
            val full = fullFile(hash)
            if (thumb.isFile && full.isFile && full.length() > 0) {
                val (w, h) = boundsOf(full.absolutePath)
                return Saved(hash, thumb.absolutePath, full.absolutePath, w, h, full.length(), animated)
            }

            // 3. Decode bounds only, then decode downsampled. The full bitmap is never
            //    materialised at source resolution.
            val (srcW, srcH) = boundsOf(temp.absolutePath)
            if (srcW <= 0 || srcH <= 0) return null

            // The dimensions recorded on the row must be the ones we actually stored, so
            // writeScaled hands back the post-scale size.
            val stored = writeScaled(
                temp.absolutePath, srcW, srcH, full,
                ClipboardImagePolicy.FULL_MAX_EDGE, ClipboardImagePolicy.FULL_QUALITY
            ) ?: return null

            // A missing thumbnail is survivable — the row falls back to a placeholder —
            // so its failure does not abort the save.
            writeScaled(
                temp.absolutePath, srcW, srcH, thumb,
                ClipboardImagePolicy.THUMB_MAX_EDGE, ClipboardImagePolicy.THUMB_QUALITY
            )

            val (outW, outH) = stored

            if (!full.isFile || full.length() <= 0L) return null
            return Saved(hash, thumb.absolutePath, full.absolutePath, outW, outH, full.length(), animated)
        } catch (_: Throwable) {
            return null
        } finally {
            try { temp?.delete() } catch (_: Throwable) {}
        }
    }

    private fun boundsOf(path: String): Pair<Int, Int> = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        o.outWidth to o.outHeight
    } catch (_: Throwable) {
        0 to 0
    }

    /**
     * Decode [path] downsampled, scale it exactly to [maxEdge], compress into [target].
     * Returns the stored pixel size, or null if any step failed. Bitmaps are recycled on
     * every exit path — this runs in a host process whose heap is not ours to leak.
     */
    private fun writeScaled(
        path: String, srcW: Int, srcH: Int, target: File, maxEdge: Int, quality: Int
    ): Pair<Int, Int>? {
        val decoded = decodeSampled(path, srcW, srcH, maxEdge) ?: return null
        var scaled: Bitmap? = null
        try {
            val (tw, th) = ClipboardImagePolicy.scaledSize(decoded.width, decoded.height, maxEdge)
            scaled = if (tw != decoded.width || th != decoded.height)
                Bitmap.createScaledBitmap(decoded, tw, th, true) else decoded
            if (!writeAtomic(target, scaled, quality)) return null
            return scaled.width to scaled.height
        } catch (_: Throwable) {
            return null
        } catch (_: OutOfMemoryError) {
            return null
        } finally {
            if (scaled != null && scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }

    private fun decodeSampled(path: String, srcW: Int, srcH: Int, maxEdge: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply {
            inSampleSize = ClipboardImagePolicy.sampleSizeFor(srcW, srcH, maxEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeFile(path, o)
    } catch (_: Throwable) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    /** temp file + rename, so a crash mid-write can never leave a half-image behind. */
    private fun writeAtomic(target: File, bmp: Bitmap, quality: Int): Boolean = try {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, it) }
        if (tmp.length() <= 0L) {
            tmp.delete(); false
        } else {
            target.delete()
            tmp.renameTo(target)
        }
    } catch (_: Throwable) {
        false
    }

    /**
     * Is the SOURCE animated? We only ever store the decoded first frame, so this is purely
     * the `GIF` badge signal.
     *
     * A plain "RIFF....WEBP" test is not good enough: WebP is also what we re-encode INTO,
     * so every static WebP — including our own stored files coming back round through a
     * paste — would be badged as animated. The container has to be inspected properly:
     * only the extended `VP8X` form carries an animation flag (bit 1 of the flags byte).
     */
    private fun looksAnimated(head: ByteArray, len: Int): Boolean {
        if (len >= 6) {
            val s = String(head, 0, 6, Charsets.US_ASCII)
            if (s == "GIF87a" || s == "GIF89a") return true
        }
        if (len >= 21) {
            val riff = String(head, 0, 4, Charsets.US_ASCII)
            val webp = String(head, 8, 4, Charsets.US_ASCII)
            val chunk = String(head, 12, 4, Charsets.US_ASCII)
            // "VP8 " (lossy) and "VP8L" (lossless) are single-frame by definition.
            if (riff == "RIFF" && webp == "WEBP" && chunk == "VP8X") {
                return (head[20].toInt() and 0x02) != 0
            }
        }
        return false
    }

    /** Unlink both artifacts for [hash]. Callers must have checked the refcount first. */
    fun deleteHash(hash: String): Boolean {
        var ok = true
        try { if (thumbFile(hash).exists()) ok = thumbFile(hash).delete() && ok } catch (_: Throwable) { ok = false }
        try { if (fullFile(hash).exists()) ok = fullFile(hash).delete() && ok } catch (_: Throwable) { ok = false }
        return ok
    }

    fun hasBytes(hash: String): Boolean = try {
        fullFile(hash).isFile && fullFile(hash).length() > 0
    } catch (_: Throwable) {
        false
    }

    data class GcResult(val orphanFilesDeleted: Int, val tempFilesDeleted: Int)

    /**
     * Delete files no row references any more, plus stale `.tmp` leftovers. [keep] is the
     * set of hashes still referenced — anything referenced is never touched, which is the
     * invariant that makes running this on every vault open safe.
     *
     * The other half of self-healing (dropping rows whose file vanished) belongs to the
     * caller, which has the database.
     */
    fun gc(keep: Set<String>): GcResult {
        var orphans = 0
        var temps = 0
        try {
            for (dir in listOf(thumbDir, fullDir)) {
                val files = dir.listFiles() ?: continue
                for (f in files) {
                    val name = f.name
                    if (name.endsWith(".tmp")) {
                        if (f.delete()) temps++
                        continue
                    }
                    if (!name.endsWith(EXT)) continue
                    val hash = name.removeSuffix(EXT)
                    if (hash !in keep) {
                        if (f.delete()) orphans++
                    }
                }
            }
            // Interrupted ingests.
            root.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".tmp")) { if (f.delete()) temps++ }
            }
        } catch (_: Throwable) {
        }
        return GcResult(orphans, temps)
    }

    /** Bytes actually occupied on disk by the `full` artifacts. */
    fun diskBytes(): Long = try {
        (fullDir.listFiles() ?: emptyArray()).sumOf { it.length() }
    } catch (_: Throwable) {
        0L
    }
}

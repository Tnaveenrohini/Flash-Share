package com.flikk.share

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Base64
import java.io.FileOutputStream
import java.util.BitSet

/**
 * Vachina files Downloads/Flikk Share/ lo save. Transfer madhyalo aagithe,
 * enta varaku vachindo gurtupettukuntam — malli pampithe akkadi nunchi continue.
 */
object Storage {
    const val ROOT = "Download/Flikk Share"
    private val resume get() = App.instance.getSharedPreferences("resume", Context.MODE_PRIVATE)

    fun clean(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trimStart('.').ifEmpty { "file" }.take(150)

    fun cleanDir(d: String): String =
        d.split('/', '\\').map { it.trim() }.filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/") { clean(it) }

    /** bits = ee file lo already vachina 4MB mukkalu (resume ki) */
    class Slot(val uri: Uri, val key: String, val bits: BitSet = BitSet()) {
        val offset: Long get() = 0
    }

    private fun decodeBits(s: String): BitSet = try {
        if (s.isEmpty()) BitSet() else BitSet.valueOf(Base64.decode(s, Base64.NO_WRAP))
    } catch (_: Exception) { BitSet() }

    fun saveBits(slot: Slot) {
        if (slot.key.isEmpty()) return
        val b = synchronized(slot.bits) { slot.bits.toByteArray() }
        resume.edit().putString(slot.key, "${slot.uri}|${Base64.encodeToString(b, Base64.NO_WRAP)}").apply()
    }

    /** Kotha entry create cheyyi leda paata (half) entry ni resume ki ivvu */
    fun prepare(ctx: Context, name: String, relDir: String, size: Long, mime: String, key: String?): Slot {
        val cr = ctx.contentResolver
        if (key != null) {
            resume.getString(key, null)?.let { saved ->
                val uri = Uri.parse(saved.substringBefore('|'))
                try {
                    cr.openFileDescriptor(uri, "r")?.use { return Slot(uri, key, decodeBits(saved.substringAfter('|', ""))) }
                } catch (_: Exception) { }
                resume.edit().remove(key).apply()
            }
        }
        val dir = cleanDir(relDir)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, clean(name))
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (dir.isEmpty()) "$ROOT/" else "$ROOT/$dir/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Couldn't create $name in Downloads")
        if (key != null) resume.edit().putString(key, "$uri|").apply()
        return Slot(uri, key ?: "")
    }

    class Writer(ctx: Context, val slot: Slot) : AutoCloseable {
        private val pfd: ParcelFileDescriptor = ctx.contentResolver.openFileDescriptor(slot.uri, "rw")
            ?: error("Can't write file")
        private val fos = FileOutputStream(pfd.fileDescriptor)
        var written = slot.offset
            private set

        init {
            fos.channel.truncate(slot.offset)
            fos.channel.position(slot.offset)
        }

        fun write(b: ByteArray, len: Int) {
            fos.write(b, 0, len)
            written += len
        }

        override fun close() {
            try { fos.flush(); fos.fd.sync() } catch (_: Exception) { }
            try { fos.close() } catch (_: Exception) { }
            try { pfd.close() } catch (_: Exception) { }
        }
    }

    fun finish(ctx: Context, slot: Slot) {
        val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        try { ctx.contentResolver.update(slot.uri, v, null, null) } catch (_: Exception) { }
        if (slot.key.isNotEmpty()) resume.edit().remove(slot.key).apply()
    }

    fun discard(ctx: Context, slot: Slot) {
        try { ctx.contentResolver.delete(slot.uri, null, null) } catch (_: Exception) { }
        if (slot.key.isNotEmpty()) resume.edit().remove(slot.key).apply()
    }
}

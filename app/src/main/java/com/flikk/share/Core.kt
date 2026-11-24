package com.flikk.share

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import java.io.InputStream
import java.util.Locale
import java.util.UUID

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val CHANNEL = "transfers"
        lateinit var instance: App
    }
}

/** Okka file — pampali anukunnadi. uri (content://) leda path (APK files) lo okati untundi. */
data class SendItem(
    val name: String,
    val relDir: String,          // folder pampinappudu "Photos/2024" laantidi, lekapothe ""
    val size: Long,
    val uri: Uri? = null,
    val path: String? = null,
    val mime: String = mimeOf(name),
    val modified: Long = 0,
) {
    /** Resume ki: same file ni malli pampithe receiver gurtupattadaniki */
    val key: String get() = "${Prefs.deviceId}|$relDir/$name|$size|$modified"

    /** offset nunchi chadive stream (pedda files ki skip kakunda seek chestham) */
    fun open(ctx: Context, offset: Long): InputStream {
        if (path != null) {
            val fis = FileInputStream(path)
            if (offset > 0) fis.channel.position(offset)
            return fis
        }
        val u = uri!!
        try {
            val pfd = ctx.contentResolver.openFileDescriptor(u, "r")
            if (pfd != null) {
                val fis = ParcelFileDescriptor.AutoCloseInputStream(pfd)
                if (offset > 0) fis.channel.position(offset)
                return fis
            }
        } catch (_: Exception) { }
        val ins = ctx.contentResolver.openInputStream(u) ?: error("Can't open $name")
        var left = offset
        while (left > 0) {
            val s = ins.skip(left)
            if (s <= 0) { if (ins.read() < 0) break; left-- } else left -= s
        }
        return ins
    }
}

fun mimeOf(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    if (ext == "apk") return "application/vnd.android.package-archive"
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
}

/** QR lo unde vishayalu */
data class Target(
    val name: String,
    val ssid: String,
    val pass: String,
    val ip: String,
    val port: Int,
    val token: String,
) {
    fun toUri(): String = Uri.Builder().scheme("flikkshare").authority("r")
        .appendQueryParameter("n", name)
        .appendQueryParameter("s", ssid)
        .appendQueryParameter("p", pass)
        .appendQueryParameter("i", ip)
        .appendQueryParameter("o", port.toString())
        .appendQueryParameter("t", token)
        .build().toString()

    companion object {
        fun parse(text: String?): Target? {
            if (text == null || !text.startsWith("flikkshare://")) return null
            return try {
                val u = Uri.parse(text)
                Target(
                    name = u.getQueryParameter("n") ?: "Phone",
                    ssid = u.getQueryParameter("s") ?: "",
                    pass = u.getQueryParameter("p") ?: "",
                    ip = u.getQueryParameter("i") ?: "",
                    port = u.getQueryParameter("o")?.toIntOrNull() ?: Proto.PORT,
                    token = u.getQueryParameter("t") ?: "",
                ).takeIf { it.token.isNotEmpty() && (it.ssid.isNotEmpty() || it.ip.isNotEmpty()) }
            } catch (_: Exception) { null }
        }
    }
}

enum class Phase { IDLE, WAITING, CONNECTING, TRANSFERRING, DONE, FAILED }

data class FileProg(val name: String, val size: Long, val done: Long = 0)

data class TState(
    val role: String = "",           // "send" / "recv"
    val phase: Phase = Phase.IDLE,
    val peer: String = "",
    val files: List<FileProg> = emptyList(),
    val current: Int = -1,
    val total: Long = 0,
    val done: Long = 0,
    val speed: Long = 0,             // bytes / second
    val avgSpeed: Long = 0,
    val message: String = "",
    val via: String = "",
)

data class ReceiveInfo(val target: Target?, val direct: Boolean, val band: String, val error: String = "")

data class WebInfo(val urls: List<String>, val shared: Int, val downloads: Int, val uploads: Int, val base: String = "")

/** Activities, Service madhya share chese state */
object Hub {
    val state = MutableStateFlow(TState())
    val receive = MutableStateFlow<ReceiveInfo?>(null)
    val web = MutableStateFlow<WebInfo?>(null)

    @Volatile var pending: List<SendItem> = emptyList()
    @Volatile var lastTarget: Target? = null
    @Volatile var webItems: List<SendItem> = emptyList()
}

object Prefs {
    private val p get() = App.instance.getSharedPreferences("flikk", Context.MODE_PRIVATE)

    val deviceId: String
        get() = p.getString("id", null) ?: UUID.randomUUID().toString().take(8).also { p.edit().putString("id", it).apply() }

    var deviceName: String
        get() = p.getString("name", null) ?: defaultName()
        set(v) { p.edit().putString("name", v.trim().take(30)).apply() }

    private fun defaultName(): String {
        val m = Build.MODEL ?: "Phone"
        val b = Build.MANUFACTURER ?: ""
        return if (m.lowercase().startsWith(b.lowercase())) m else "${b.replaceFirstChar { it.uppercase() }} $m".trim()
    }
}

data class HistoryEntry(
    val time: Long,
    val dir: String,                 // sent / recv / pc_in
    val peer: String,
    val count: Int,
    val bytes: Long,
    val ok: Boolean,
    val files: List<Pair<String, String>>, // name, uri ("" if unknown)
)

object History {
    private val p get() = App.instance.getSharedPreferences("history", Context.MODE_PRIVATE)

    @Synchronized
    fun add(e: HistoryEntry) {
        val arr = try { JSONArray(p.getString("list", "[]")) } catch (_: Exception) { JSONArray() }
        val o = JSONObject()
            .put("t", e.time).put("d", e.dir).put("p", e.peer).put("c", e.count)
            .put("b", e.bytes).put("ok", e.ok)
        val fa = JSONArray()
        e.files.take(200).forEach { fa.put(JSONObject().put("n", it.first).put("u", it.second)) }
        o.put("f", fa)
        val out = JSONArray().put(o)
        for (i in 0 until minOf(arr.length(), 199)) out.put(arr.get(i))
        p.edit().putString("list", out.toString()).apply()
    }

    fun all(): List<HistoryEntry> {
        val arr = try { JSONArray(p.getString("list", "[]")) } catch (_: Exception) { return emptyList() }
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val fa = o.optJSONArray("f") ?: JSONArray()
            HistoryEntry(
                o.optLong("t"), o.optString("d"), o.optString("p"), o.optInt("c"),
                o.optLong("b"), o.optBoolean("ok", true),
                (0 until fa.length()).map { fa.getJSONObject(it).let { f -> f.optString("n") to f.optString("u") } },
            )
        }
    }

    fun clear() = p.edit().remove("list").apply()
}

fun fmtSize(b: Long): String {
    if (b < 1024) return "$b B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = b / 1024.0
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return if (v >= 100) String.format(Locale.US, "%.0f %s", v, units[i])
    else String.format(Locale.US, "%.1f %s", v, units[i])
}

fun fmtTime(sec: Long): String = when {
    sec < 0 -> "…"
    sec < 60 -> "${sec}s"
    sec < 3600 -> "${sec / 60}m ${sec % 60}s"
    else -> "${sec / 3600}h ${(sec % 3600) / 60}m"
}

fun randomToken(len: Int): String {
    val chars = "abcdefghjkmnpqrstuvwxyz23456789"
    val r = java.security.SecureRandom()
    return (1..len).map { chars[r.nextInt(chars.length)] }.joinToString("")
}

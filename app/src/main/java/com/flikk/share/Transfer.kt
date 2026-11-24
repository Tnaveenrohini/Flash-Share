package com.flikk.share

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Flikk Share protocol (okate TCP connection, pedda buffers — Wi-Fi Direct speed full ga vaadukuntam):
 *  sender → MAGIC, header JSON (token, peru, files list)
 *  receiver → JSON {ok, prathi file ki enta varaku already undo (resume offset)}
 *  sender → prathi file yokka migilina bytes, okati tarvata okati
 *  receiver → 1 byte ack
 */
object Proto {
    const val PORT = 47474
    const val MAGIC = 0x464C4B53 // "FLKS"
    const val BUF = 512 * 1024
    const val SOCK_BUF = 4 * 1024 * 1024

    fun writeJson(out: DataOutputStream, o: JSONObject) {
        val b = o.toString().toByteArray(Charsets.UTF_8)
        out.writeInt(b.size); out.write(b); out.flush()
    }

    fun readJson(inp: DataInputStream): JSONObject {
        val n = inp.readInt()
        if (n <= 0 || n > 32 shl 20) throw IOException("Bad header")
        val b = ByteArray(n); inp.readFully(b)
        return JSONObject(String(b, Charsets.UTF_8))
    }
}

/** Speed + progress lekkalu, UI ki 150ms ki okasari */
class Meter(private val role: String, private val peer: String, private val via: String, names: List<Pair<String, Long>>) {
    private val files = names.map { FileProg(it.first, it.second) }.toMutableList()
    private val total = names.sumOf { it.second }
    private var done = 0L
    private var current = 0
    private val start = SystemClock.elapsedRealtime()
    private var lastEmit = 0L
    private var lastBytes = 0L
    private var lastT = start
    private var speed = 0.0

    fun skip(i: Int, bytes: Long) { files[i] = files[i].copy(done = bytes); done += bytes }

    fun add(i: Int, n: Int) {
        current = i
        done += n
        files[i] = files[i].copy(done = files[i].done + n)
        val now = SystemClock.elapsedRealtime()
        if (now - lastEmit >= 150) emit(now)
    }

    private fun emit(now: Long) {
        val dt = (now - lastT).coerceAtLeast(1)
        val inst = (done - lastBytes) * 1000.0 / dt
        speed = if (speed == 0.0) inst else speed * 0.7 + inst * 0.3
        lastT = now; lastBytes = done; lastEmit = now
        Hub.state.value = snapshot(Phase.TRANSFERRING)
    }

    fun avg(): Long {
        val el = (SystemClock.elapsedRealtime() - start).coerceAtLeast(1)
        return (done * 1000 / el)
    }

    fun snapshot(phase: Phase, msg: String = "") = TState(
        role = role, phase = phase, peer = peer, files = files.toList(), current = current,
        total = total, done = done, speed = speed.toLong(), avgSpeed = avg(), message = msg, via = via,
    )
}

object Sender {
    private const val TAG = "FlikkSend"
    @Volatile private var sock: Socket? = null

    fun abort() { try { sock?.close() } catch (_: Exception) { } }

    private fun tryConnect(host: String, port: Int, tries: Int, timeout: Int): Socket? {
        repeat(tries) {
            try {
                val s = Socket()
                s.sendBufferSize = Proto.SOCK_BUF
                s.receiveBufferSize = 256 * 1024
                s.connect(InetSocketAddress(host, port), timeout)
                return s
            } catch (e: Exception) {
                Log.w(TAG, "connect $host try $it: $e")
                Thread.sleep(700)
            }
        }
        return null
    }

    suspend fun run(ctx: Context, items: List<SendItem>, t: Target) {
        Hub.lastTarget = t
        val names = items.map { it.name to it.size }
        Hub.state.value = TState(role = "send", phase = Phase.CONNECTING, peer = t.name,
            files = names.map { FileProg(it.first, it.second) }, total = names.sumOf { it.second })

        var s: Socket? = null
        var via = ""
        // 1) Wi-Fi Direct (fastest, router avasaram ledu). 2) Kudarakapothe same Wi-Fi.
        if (t.ssid.isNotEmpty()) {
            val host = try { P2p.join(ctx, t.ssid, t.pass) } catch (e: Exception) { Log.w(TAG, "join", e); null }
            if (host != null) {
                s = tryConnect(host, t.port, 6, 4000)
                if (s != null) via = "Wi-Fi Direct"
            }
        }
        currentCoroutineContext().ensureActive()
        if (s == null && t.ip.isNotEmpty()) {
            s = tryConnect(t.ip, t.port, 3, 3000)
            if (s != null) via = "Wi-Fi"
        }
        if (s == null) {
            Hub.state.value = Hub.state.value.copy(phase = Phase.FAILED,
                message = "Couldn't reach ${t.name}. Keep both phones close, Wi-Fi ON, and the receiver on the QR screen.")
            History.add(HistoryEntry(System.currentTimeMillis(), "sent", t.name, items.size, 0, false, names.map { it.first to "" }))
            return
        }
        sock = s
        val meter = Meter("send", t.name, via, names)
        try {
            s.soTimeout = 30_000
            s.tcpNoDelay = false
            val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 64 * 1024))
            val inp = DataInputStream(BufferedInputStream(s.getInputStream()))
            out.writeInt(Proto.MAGIC)
            val fa = JSONArray()
            items.forEach {
                fa.put(JSONObject().put("n", it.name).put("p", it.relDir).put("s", it.size)
                    .put("m", it.mime).put("k", it.key))
            }
            Proto.writeJson(out, JSONObject().put("t", t.token).put("n", Prefs.deviceName).put("f", fa))
            val resp = Proto.readJson(inp)
            if (!resp.optBoolean("ok")) throw IOException(resp.optString("e", "Receiver said no"))
            val offs = resp.getJSONArray("o")
            items.indices.forEach { meter.skip(it, offs.optLong(it, 0)) }
            Hub.state.value = meter.snapshot(Phase.TRANSFERRING)

            val buf = ByteArray(Proto.BUF)
            for ((i, item) in items.withIndex()) {
                val off = offs.optLong(i, 0).coerceIn(0, item.size)
                var left = item.size - off
                if (left == 0L) continue
                item.open(ctx, off).use { ins ->
                    while (left > 0) {
                        val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) throw IOException("${item.name} changed while sending")
                        out.write(buf, 0, n)
                        left -= n
                        meter.add(i, n)
                    }
                }
            }
            out.flush()
            s.soTimeout = 60_000
            inp.readByte()
            Hub.state.value = meter.snapshot(Phase.DONE)
            History.add(HistoryEntry(System.currentTimeMillis(), "sent", t.name, items.size,
                items.sumOf { it.size }, true, items.map { it.name to (it.uri?.toString() ?: "") }))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "send failed", e)
            Hub.state.value = meter.snapshot(Phase.FAILED, e.message ?: "Connection lost")
            History.add(HistoryEntry(System.currentTimeMillis(), "sent", t.name, items.size, 0, false, names.map { it.first to "" }))
        } finally {
            try { s.close() } catch (_: Exception) { }
            sock = null
            delay(300)
            try { P2p.leave(ctx) } catch (_: Exception) { }
        }
    }
}

object Receiver {
    private const val TAG = "FlikkRecv"
    @Volatile private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null

    fun stop() {
        val ss = server
        server = null
        try { client?.close() } catch (_: Exception) { }
        try { ss?.close() } catch (_: Exception) { }
    }

    /** Group create chesi, QR ichi, connections kosam wait. Cancel ayye varaku nadustundi. */
    suspend fun run(ctx: Context) {
        Hub.state.value = TState(role = "recv", phase = Phase.WAITING)
        Hub.receive.value = null
        val token = randomToken(8)
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.receiveBufferSize = Proto.SOCK_BUF
        try {
            ss.bind(InetSocketAddress(Proto.PORT))
        } catch (e: Exception) {
            ss.bind(InetSocketAddress(0))
        }
        server = ss
        val port = ss.localPort

        val group = if (P2p.wifiOn(ctx)) try { P2p.createGroup(ctx) } catch (e: Exception) { Log.w(TAG, "group", e); null } else null
        val ip = P2p.lanAddresses().firstOrNull() ?: ""
        val t = Target(Prefs.deviceName, group?.first ?: "", group?.second ?: "", ip, port, token)
        Hub.receive.value = if (group == null && ip.isEmpty())
            ReceiveInfo(null, false, "", "Turn on Wi-Fi to receive")
        else ReceiveInfo(t, group != null, group?.third ?: "")

        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val c = try { ss.accept() } catch (e: Exception) { if (server == null) return else throw e }
                client = c
                try { handle(ctx, c, token) } catch (e: Exception) {
                    Log.w(TAG, "receive failed", e)
                } finally {
                    try { c.close() } catch (_: Exception) { }
                    client = null
                }
            }
        } finally {
            try { ss.close() } catch (_: Exception) { }
            server = null
            try { P2p.leave(ctx) } catch (_: Exception) { }
            Hub.receive.value = null
        }
    }

    private fun handle(ctx: Context, s: Socket, token: String) {
        s.soTimeout = 30_000
        val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 256 * 1024))
        val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
        if (inp.readInt() != Proto.MAGIC) return
        val head = Proto.readJson(inp)
        if (head.optString("t") != token) {
            Proto.writeJson(out, JSONObject().put("ok", false).put("e", "Old QR code — scan again"))
            return
        }
        val peer = head.optString("n", "Phone")
        val fa = head.getJSONArray("f")
        data class F(val name: String, val dir: String, val size: Long, val mime: String, val key: String)
        val files = (0 until fa.length()).map {
            val o = fa.getJSONObject(it)
            F(o.optString("n", "file"), o.optString("p", ""), o.optLong("s"), o.optString("m", "application/octet-stream"), o.optString("k"))
        }
        val slots = files.map { Storage.prepare(ctx, it.name, it.dir, it.size, it.mime, it.key) }
        val offs = JSONArray(); slots.forEach { offs.put(it.offset) }
        Proto.writeJson(out, JSONObject().put("ok", true).put("n", Prefs.deviceName).put("o", offs))

        val via = if (s.localAddress?.hostAddress?.startsWith("192.168.49.") == true) "Wi-Fi Direct" else "Wi-Fi"
        val meter = Meter("recv", peer, via, files.map { it.name to it.size })
        slots.forEachIndexed { i, sl -> meter.skip(i, sl.offset) }
        Hub.state.value = meter.snapshot(Phase.TRANSFERRING)

        val buf = ByteArray(Proto.BUF)
        var i = 0
        try {
            while (i < files.size) {
                val f = files[i]; val slot = slots[i]
                var left = f.size - slot.offset
                if (left > 0) {
                    Storage.Writer(ctx, slot).use { w ->
                        var sinceCheck = 0L
                        try {
                            while (left > 0) {
                                val n = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                if (n < 0) throw EOFException("Sender disconnected")
                                w.write(buf, n)
                                left -= n
                                meter.add(i, n)
                                sinceCheck += n
                                if (sinceCheck >= 16L shl 20) { w.checkpoint(); sinceCheck = 0 }
                            }
                        } finally { w.checkpoint() }
                    }
                }
                Storage.finish(ctx, slot)
                i++
            }
            out.writeByte(1); out.flush()
            Hub.state.value = meter.snapshot(Phase.DONE)
            History.add(HistoryEntry(System.currentTimeMillis(), "recv", peer, files.size, files.sumOf { it.size }, true,
                files.indices.map { files[it].name to slots[it].uri.toString() }))
        } catch (e: Exception) {
            Hub.state.value = meter.snapshot(Phase.FAILED,
                "Connection lost. Ask the sender to tap Retry — it continues from where it stopped.")
            History.add(HistoryEntry(System.currentTimeMillis(), "recv", peer, files.size, 0, false,
                files.indices.take(i).map { files[it].name to slots[it].uri.toString() }))
            throw e
        }
    }
}

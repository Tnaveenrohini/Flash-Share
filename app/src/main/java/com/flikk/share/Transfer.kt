package com.flikk.share

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.io.FileInputStream
import java.io.InputStream
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import android.os.ParcelFileDescriptor
import android.util.Base64

/**
 * Flikk Share protocol (okate TCP connection, pedda buffers — Wi-Fi Direct speed full ga vaadukuntam):
 *  sender → MAGIC, header JSON (token, peru, files list)
 *  receiver → JSON {ok, prathi file ki enta varaku already undo (resume offset)}
 *  sender → prathi file yokka migilina bytes, okati tarvata okati
 *  receiver → 1 byte ack
 */
/**
 * Flikk Share protocol v2 — FAST: okate saari 4 TCP connections.
 * Files ni 4 MB mukkalu (chunks) ga chesi, 4 connections lo parallel ga pamputham.
 * Okka connection disk lo raasthunte migatavi network nunchi teesukuntayi.
 *  control socket: MAGIC, header JSON → receiver: {ok, prathi file ki vachina chunks (resume)}
 *  data sockets:   MAGIC_DATA, session id
 *  prathi socket lo frames: [file idx][chunk idx][length][bytes] ... [-1] = ayipoyindi
 *  receiver anni chunks raasaka control socket lo 1 byte ack
 */
object Proto {
    const val PORT = 47474
    const val MAGIC = 0x464C4B32      // "FLK2"
    const val MAGIC_DATA = 0x464C4B44 // "FLKD"
    const val CHUNK = 4 * 1024 * 1024
    const val BUF = 512 * 1024
    const val STREAMS = 4
    const val SOCK_BUF = 4 * 1024 * 1024

    fun chunks(size: Long): Int = ((size + CHUNK - 1) / CHUNK).toInt()
    fun chunkLen(size: Long, c: Int): Int = minOf(CHUNK.toLong(), size - c.toLong() * CHUNK).toInt()

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

    fun bitsToB64(b: BitSet): String = Base64.encodeToString(b.toByteArray(), Base64.NO_WRAP)
    fun b64ToBits(s: String): BitSet = try { if (s.isEmpty()) BitSet() else BitSet.valueOf(Base64.decode(s, Base64.NO_WRAP)) } catch (_: Exception) { BitSet() }
}

/** Speed + progress lekkalu (anni threads nunchi), UI ki 150ms ki okasari */
class Meter(private val role: String, private val peer: String, @Volatile var via: String, names: List<Pair<String, Long>>) {
    private val files = names.map { FileProg(it.first, it.second) }.toMutableList()
    private val total = names.sumOf { it.second }
    private var done = 0L
    private var current = 0
    private val start = SystemClock.elapsedRealtime()
    private var lastEmit = 0L
    private var lastBytes = 0L
    private var lastT = start
    private var speed = 0.0

    @Synchronized fun skip(i: Int, bytes: Long) { files[i] = files[i].copy(done = files[i].done + bytes); done += bytes; lastBytes = done }

    @Synchronized fun add(i: Int, n: Int) {
        current = i
        done += n
        files[i] = files[i].copy(done = files[i].done + n)
        val now = SystemClock.elapsedRealtime()
        if (now - lastEmit >= 150) emit(now)
    }

    private fun emit(now: Long) {
        val dt = (now - lastT).coerceAtLeast(1)
        val inst = (done - lastBytes) * 1000.0 / dt
        speed = if (speed == 0.0) inst else speed * 0.75 + inst * 0.25
        lastT = now; lastBytes = done; lastEmit = now
        Hub.state.value = snap(Phase.TRANSFERRING, "")
    }

    private var moved = 0L
    private var firstMove = 0L
    @Synchronized fun avg(): Long {
        val el = (SystemClock.elapsedRealtime() - start).coerceAtLeast(1)
        return (done * 1000 / el)
    }

    @Synchronized fun snapshot(phase: Phase, msg: String = "") = snap(phase, msg)

    private fun snap(phase: Phase, msg: String) = TState(
        role = role, phase = phase, peer = peer, files = files.toList(), current = current,
        total = total, done = done, speed = speed.toLong(), avgSpeed = avg(), message = msg, via = via,
    )
}

/** Okka file lo ye position nunchi ayina chadavadam (parallel chunks kosam) */
private class ChunkReader(private val ctx: Context, private val items: List<SendItem>) : AutoCloseable {
    private var idx = -1
    private var ch: FileChannel? = null
    private var pfd: ParcelFileDescriptor? = null
    private var raf: RandomAccessFile? = null
    private var stream: InputStream? = null
    private var streamPos = 0L

    private fun openFor(i: Int) {
        close()
        idx = i
        val it = items[i]
        try {
            if (it.path != null) { raf = RandomAccessFile(it.path, "r"); ch = raf!!.channel; return }
            val p = ctx.contentResolver.openFileDescriptor(it.uri!!, "r")
            if (p != null) { pfd = p; ch = FileInputStream(p.fileDescriptor).channel }
        } catch (_: Exception) { ch = null }
    }

    /** exactly len bytes, pos nunchi, buf lo */
    fun read(i: Int, pos: Long, buf: ByteArray, len: Int) {
        if (i != idx) openFor(i)
        val c = ch
        if (c != null) {
            val bb = ByteBuffer.wrap(buf, 0, len)
            var p = pos
            while (bb.hasRemaining()) {
                val n = c.read(bb, p)
                if (n < 0) throw IOException("${items[i].name} changed while sending")
                p += n
            }
            return
        }
        // fd ivvani files (konni cloud files) — stream tho
        if (stream == null || streamPos != pos) {
            try { stream?.close() } catch (_: Exception) { }
            stream = items[i].open(ctx, pos); streamPos = pos
        }
        var off = 0
        while (off < len) {
            val n = stream!!.read(buf, off, len - off)
            if (n < 0) throw IOException("${items[i].name} changed while sending")
            off += n
        }
        streamPos += len
    }

    override fun close() {
        try { stream?.close() } catch (_: Exception) { }
        try { ch?.close() } catch (_: Exception) { }
        try { raf?.close() } catch (_: Exception) { }
        try { pfd?.close() } catch (_: Exception) { }
        stream = null; ch = null; raf = null; pfd = null; idx = -1
    }
}

object Sender {
    private const val TAG = "FlikkSend"
    private val socks = CopyOnWriteArrayList<Socket>()

    fun abort() { socks.forEach { try { it.close() } catch (_: Exception) { } } }

    private fun tryConnect(host: String, port: Int, tries: Int, timeout: Int): Socket? {
        repeat(tries) {
            try {
                val s = Socket()
                s.sendBufferSize = Proto.SOCK_BUF
                s.receiveBufferSize = 256 * 1024
                s.tcpNoDelay = false
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
        var host = ""
        var via = ""
        val nearby = t.token.isEmpty()
        try { Nearby.stopNow(ctx) } catch (_: Exception) { }
        // 1) Wi-Fi Direct (fastest, router avasaram ledu). 2) Kudarakapothe same Wi-Fi.
        if (t.ssid.isNotEmpty()) {
            val h = try { P2p.join(ctx, t.ssid, t.pass) } catch (e: Exception) { Log.w(TAG, "join", e); null }
            if (h != null) {
                s = tryConnect(h, t.port, 6, 4000)
                if (s != null) { host = h; via = "Wi-Fi Direct" + (P2p.band(ctx)?.let { " · $it" } ?: "") }
            }
        }
        currentCoroutineContext().ensureActive()
        if (s == null && t.ip.isNotEmpty()) {
            s = tryConnect(t.ip, t.port, 3, 3000)
            if (s != null) { host = t.ip; via = "Wi-Fi" }
        }
        if (s == null) {
            Hub.state.value = Hub.state.value.copy(phase = Phase.FAILED,
                message = "Couldn't reach ${t.name}. Keep both phones close, Wi-Fi ON, and the receiver on the QR screen.")
            History.add(HistoryEntry(System.currentTimeMillis(), "sent", t.name, items.size, 0, false, names.map { it.first to "" }))
            return
        }
        socks.clear(); socks += s
        val meter = Meter("send", t.name, via, names)
        val threads = mutableListOf<Thread>()
        try {
            s.soTimeout = 30_000
            val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 64 * 1024))
            val inp = DataInputStream(BufferedInputStream(s.getInputStream()))
            out.writeInt(Proto.MAGIC)
            val sid = randomToken(10)
            val fa = JSONArray()
            items.forEach {
                fa.put(JSONObject().put("n", it.name).put("p", it.relDir).put("s", it.size)
                    .put("m", it.mime).put("k", it.key))
            }
            Proto.writeJson(out, JSONObject().put("t", t.token).put("nb", nearby).put("n", Prefs.deviceName)
                .put("sid", sid).put("f", fa))
            if (nearby) {
                // receiver Accept nokkali — 75 sec varaku wait
                s.soTimeout = 75_000
                Hub.state.value = Hub.state.value.copy(message = "accept")
            }
            val resp = Proto.readJson(inp)
            s.soTimeout = 30_000
            if (!resp.optBoolean("ok")) {
                val e = resp.optString("e", "Receiver said no")
                throw IOException(if (e == "declined") ctx.getString(R.string.declined, t.name) else e)
            }
            // Pampalsina chunks list (receiver daggara already unnavi tappinchi)
            val have = resp.optJSONArray("d") ?: JSONArray()
            val queue = ConcurrentLinkedQueue<Pair<Int, Int>>()
            items.forEachIndexed { i, item ->
                val bits = Proto.b64ToBits(have.optString(i, ""))
                var doneBytes = 0L
                for (c in 0 until Proto.chunks(item.size)) {
                    if (bits.get(c)) doneBytes += Proto.chunkLen(item.size, c) else queue.add(i to c)
                }
                if (doneBytes > 0) meter.skip(i, doneBytes)
            }
            Hub.state.value = meter.snapshot(Phase.TRANSFERRING)

            // Extra connections (3 more) — kudirithe
            val streams = mutableListOf(s to out)
            val want = minOf(resp.optInt("st", Proto.STREAMS), Proto.STREAMS, maxOf(1, queue.size))
            for (k in 1 until want) {
                val x = tryConnect(host, t.port, 2, 3000) ?: break
                x.soTimeout = 30_000
                socks += x
                val o = DataOutputStream(BufferedOutputStream(x.getOutputStream(), 64 * 1024))
                o.writeInt(Proto.MAGIC_DATA); o.writeUTF(sid); o.flush()
                streams += x to o
            }

            val error = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
            for ((_, o) in streams) {
                val th = Thread {
                    val buf = ByteArray(Proto.BUF)
                    try {
                        ChunkReader(ctx, items).use { rd ->
                            while (error.get() == null) {
                                val (i, c) = queue.poll() ?: break
                                val size = items[i].size
                                val len = Proto.chunkLen(size, c)
                                o.writeInt(i); o.writeLong(c.toLong()); o.writeInt(len)
                                var off = 0
                                val base = c.toLong() * Proto.CHUNK
                                while (off < len) {
                                    val n = minOf(buf.size, len - off)
                                    rd.read(i, base + off, buf, n)
                                    o.write(buf, 0, n)
                                    off += n
                                    meter.add(i, n)
                                }
                            }
                        }
                        o.writeInt(-1); o.flush()
                    } catch (e: Throwable) {
                        error.compareAndSet(null, e)
                        abort()
                    }
                }
                th.priority = Thread.MAX_PRIORITY
                threads += th
                th.start()
            }
            for (th in threads) {
                while (th.isAlive) { th.join(250); currentCoroutineContext().ensureActive() }
            }
            error.get()?.let { throw it }
            s.soTimeout = 120_000
            if (inp.readByte().toInt() != 1) throw IOException("Receiver couldn't save the files")
            Hub.state.value = meter.snapshot(Phase.DONE)
            History.add(HistoryEntry(System.currentTimeMillis(), "sent", t.name, items.size,
                items.sumOf { it.size }, true, items.map { it.name to (it.uri?.toString() ?: "") }))
        } catch (e: Throwable) {
            if (e is CancellationException) { abort(); throw e }
            Log.w(TAG, "send failed", e)
            Hub.state.value = meter.snapshot(Phase.FAILED, e.message ?: "Connection lost")
            History.add(HistoryEntry(System.currentTimeMillis(), "sent", t.name, items.size, 0, false, names.map { it.first to "" }))
        } finally {
            abort()
            threads.forEach { try { it.join(2000) } catch (_: Exception) { } }
            socks.clear()
            delay(300)
            try { P2p.leave(ctx) } catch (_: Exception) { }
        }
    }
}

/** Okka transfer (oka sender nunchi) — anni connections share chestayi */
private class Session(
    val ctx: Context,
    val peer: String,
    val names: List<String>,
    val sizes: List<Long>,
    val slots: List<Storage.Slot>,
    val meter: Meter,
) {
    val sockets = CopyOnWriteArrayList<Socket>()
    private val chans = arrayOfNulls<FileChannel>(slots.size)
    private val pfds = arrayOfNulls<ParcelFileDescriptor>(slots.size)
    private val left = IntArray(slots.size)
    private val sinceSave = IntArray(slots.size)
    private val remaining = AtomicInteger(0)
    val done = CountDownLatch(1)
    @Volatile var failed = false

    init {
        var total = 0
        for (i in slots.indices) {
            val n = Proto.chunks(sizes[i])
            var l = 0
            for (c in 0 until n) if (!slots[i].bits.get(c)) l++
            left[i] = l; total += l
        }
        remaining.set(total)
        // 0 bytes / already full ga vachina files — ippude finish
        for (i in slots.indices) if (left[i] == 0) Storage.finish(ctx, slots[i])
        if (total == 0) done.countDown()
    }

    fun pending(i: Int) = left[i]

    @Synchronized fun channel(i: Int): FileChannel {
        chans[i]?.let { return it }
        val p = ctx.contentResolver.openFileDescriptor(slots[i].uri, "rw") ?: throw IOException("Can't write ${names[i]}")
        pfds[i] = p
        val c = FileOutputStream(p.fileDescriptor).channel
        chans[i] = c
        return c
    }

    fun chunkDone(i: Int, c: Int) {
        var finish = false
        synchronized(this) {
            synchronized(slots[i].bits) { slots[i].bits.set(c) }
            left[i]--
            if (left[i] == 0) {
                finish = true
                try { chans[i]?.force(false) } catch (_: Exception) { }
                try { chans[i]?.close() } catch (_: Exception) { }
                try { pfds[i]?.close() } catch (_: Exception) { }
                chans[i] = null; pfds[i] = null
            } else if (++sinceSave[i] >= 8) { sinceSave[i] = 0; Storage.saveBits(slots[i]) }
        }
        if (finish) Storage.finish(ctx, slots[i])
        if (remaining.decrementAndGet() == 0) done.countDown()
    }

    fun fail() {
        if (failed) return
        failed = true
        synchronized(this) {
            for (i in slots.indices) {
                if (left[i] > 0) Storage.saveBits(slots[i])
                try { chans[i]?.close() } catch (_: Exception) { }
                try { pfds[i]?.close() } catch (_: Exception) { }
                chans[i] = null; pfds[i] = null
            }
        }
        sockets.forEach { try { it.close() } catch (_: Exception) { } }
        done.countDown()
    }

    /** Okka socket nunchi frames chadivi files lo raayi */
    fun pump(inp: DataInputStream) {
        val buf = ByteArray(Proto.BUF)
        while (true) {
            val fi = inp.readInt()
            if (fi < 0) return
            val ci = inp.readLong()
            val len = inp.readInt()
            if (fi >= slots.size || ci < 0 || len < 0 || len > Proto.CHUNK) throw IOException("Bad frame")
            val ch = channel(fi)
            var pos = ci * Proto.CHUNK
            var rest = len
            while (rest > 0) {
                val n = inp.read(buf, 0, minOf(buf.size, rest))
                if (n < 0) throw EOFException("Sender disconnected")
                val bb = ByteBuffer.wrap(buf, 0, n)
                while (bb.hasRemaining()) pos += ch.write(bb, pos)
                rest -= n
                meter.add(fi, n)
            }
            chunkDone(fi, ci.toInt())
        }
    }
}

object Receiver {
    private const val TAG = "FlikkRecv"
    @Volatile private var server: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Socket>()
    private val sessions = ConcurrentHashMap<String, Session>()

    fun stop() {
        val ss = server
        server = null
        sessions.values.forEach { it.fail() }
        clients.forEach { try { it.close() } catch (_: Exception) { } }
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
            ss.bind(InetSocketAddress(Proto.PORT), 16)
        } catch (e: Exception) {
            ss.bind(InetSocketAddress(0), 16)
        }
        server = ss
        val port = ss.localPort

        val group = if (P2p.wifiOn(ctx)) try { P2p.createGroup(ctx) } catch (e: Exception) { Log.w(TAG, "group", e); null } else null
        val ip = P2p.lanAddresses().firstOrNull() ?: ""
        val t = Target(Prefs.deviceName, group?.first ?: "", group?.second ?: "", ip, port, token)
        Hub.receive.value = if (group == null && ip.isEmpty())
            ReceiveInfo(null, false, "", "Turn on Wi-Fi to receive")
        else ReceiveInfo(t, group != null, group?.third ?: "")

        if (group != null) {
            try {
                P2p.advertise(ctx, mapOf("i" to Prefs.deviceId, "n" to Prefs.deviceName.take(40), "s" to group.first,
                    "p" to group.second, "o" to port.toString(), "b" to group.third))
            } catch (e: Exception) { Log.w(TAG, "advertise", e) }
        }
        val band = group?.third ?: ""
        try {
            coroutineScope {
                val keep = launch {
                    while (isActive) { delay(20_000); try { P2p.ping(ctx) } catch (_: Exception) { } }
                }
                try {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val c = try { ss.accept() } catch (e: Exception) { if (server == null) break else throw e }
                        clients += c
                        Thread {
                            try { dispatch(ctx, c, token, band) } catch (e: Exception) {
                                Log.w(TAG, "receive failed", e)
                            } finally {
                                try { c.close() } catch (_: Exception) { }
                                clients.remove(c)
                            }
                        }.apply { priority = Thread.MAX_PRIORITY }.start()
                    }
                } finally { keep.cancel() }
            }
        } finally {
            try { ss.close() } catch (_: Exception) { }
            server = null
            try { P2p.leave(ctx) } catch (_: Exception) { }
            Hub.receive.value = null
        }
    }

    private fun dispatch(ctx: Context, s: Socket, token: String, band: String) {
        s.soTimeout = 30_000
        val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 256 * 1024))
        when (inp.readInt()) {
            Proto.MAGIC -> control(ctx, s, inp, token, band)
            Proto.MAGIC_DATA -> {
                val sid = inp.readUTF()
                var sess: Session? = null
                repeat(50) { if (sess == null) { sess = sessions[sid]; if (sess == null) Thread.sleep(100) } }
                val se = sess ?: return
                se.sockets += s
                try { se.pump(inp) } catch (e: Exception) { if (!se.failed) { Log.w(TAG, "data", e); se.fail() } }
            }
        }
    }

    private fun control(ctx: Context, s: Socket, inp: DataInputStream, token: String, band: String) {
        val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
        val head = Proto.readJson(inp)
        val peer = head.optString("n", "Phone")
        val fa = head.getJSONArray("f")
        data class F(val name: String, val dir: String, val size: Long, val mime: String, val key: String)
        val files = (0 until fa.length()).map {
            val o = fa.getJSONObject(it)
            F(o.optString("n", "file"), o.optString("p", ""), o.optLong("s"), o.optString("m", "application/octet-stream"), o.optString("k"))
        }
        if (head.optString("t") != token) {
            val fromRadar = head.optBoolean("nb") && head.optString("t").isEmpty()
            if (!fromRadar) {
                Proto.writeJson(out, JSONObject().put("ok", false).put("e", "Old QR code — scan again"))
                return
            }
            // QR scan cheyyaledu — receiver ni adugudam
            val req = Incoming(peer, files.size, files.sumOf { it.size })
            Hub.incoming.value = req
            val ok = try {
                runBlocking { withTimeoutOrNull(60_000) { req.answer.await() } } ?: false
            } finally { Hub.incoming.value = null }
            if (!ok) {
                Proto.writeJson(out, JSONObject().put("ok", false).put("e", "declined"))
                return
            }
        }
        val sid = head.optString("sid", randomToken(10))
        val slots = files.map { Storage.prepare(ctx, it.name, it.dir, it.size, it.mime, it.key) }
        val direct = s.localAddress?.hostAddress?.startsWith("192.168.49.") == true
        val via = if (direct) "Wi-Fi Direct" + (if (band.isNotEmpty()) " · $band" else "") else "Wi-Fi"
        val meter = Meter("recv", peer, via, files.map { it.name to it.size })
        slots.forEachIndexed { i, sl ->
            var b = 0L
            for (c in 0 until Proto.chunks(files[i].size)) if (sl.bits.get(c)) b += Proto.chunkLen(files[i].size, c)
            if (b > 0) meter.skip(i, b)
        }
        val sess = Session(ctx, peer, files.map { it.name }, files.map { it.size }, slots, meter)
        sess.sockets += s
        sessions[sid] = sess
        try {
            val have = JSONArray(); slots.forEach { have.put(Proto.bitsToB64(it.bits)) }
            Proto.writeJson(out, JSONObject().put("ok", true).put("n", Prefs.deviceName).put("st", Proto.STREAMS).put("d", have))
            Hub.state.value = meter.snapshot(Phase.TRANSFERRING)
            try { sess.pump(inp) } catch (e: Exception) { if (!sess.failed) { Log.w(TAG, "ctrl", e); sess.fail() } }
            // migatha connections ayye varaku wait
            while (!sess.done.await(500, TimeUnit.MILLISECONDS)) { if (server == null) sess.fail() }
            if (sess.failed) throw IOException("Connection lost")
            out.writeByte(1); out.flush()
            Hub.state.value = meter.snapshot(Phase.DONE)
            History.add(HistoryEntry(System.currentTimeMillis(), "recv", peer, files.size, files.sumOf { it.size }, true,
                files.indices.map { files[it].name to slots[it].uri.toString() }))
        } catch (e: Exception) {
            sess.fail()
            Hub.state.value = meter.snapshot(Phase.FAILED,
                "Connection lost. Ask the sender to tap Retry — it continues from where it stopped.")
            History.add(HistoryEntry(System.currentTimeMillis(), "recv", peer, files.size, 0, false,
                files.indices.filter { sess.pending(it) == 0 }.map { files[it].name to slots[it].uri.toString() }))
        } finally {
            sessions.remove(sid)
        }
    }
}

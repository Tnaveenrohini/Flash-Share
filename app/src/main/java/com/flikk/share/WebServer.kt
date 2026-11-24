package com.flikk.share

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * PC browser ki chinna web server. Link: http://<phone-ip>:8080/<secret>/
 *  - phone files download (okkokati leda anni okate ZIP)
 *  - PC nunchi phone ki upload (drag & drop)
 */
class WebServer(private val ctx: Context, private val items: List<SendItem>) {
    private val secret = randomToken(6)
    private val pool = Executors.newCachedThreadPool()
    private var ss: ServerSocket? = null
    private val downloads = AtomicInteger()
    private val uploads = AtomicInteger()
    private val received = mutableListOf<Pair<String, String>>()
    var port = 8080
        private set

    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        var bound = false
        for (p in listOf(8080, 8888, 8000, 0)) {
            try { s.bind(InetSocketAddress(p)); bound = true; break } catch (_: Exception) { }
        }
        if (!bound) error("No free port")
        port = s.localPort
        ss = s
        publish()
        pool.execute {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { break }
                pool.execute { try { serve(c) } catch (e: Exception) { Log.w("FlikkWeb", "req", e) } finally { try { c.close() } catch (_: Exception) { } } }
            }
        }
    }

    fun stop() {
        try { ss?.close() } catch (_: Exception) { }
        pool.shutdownNow()
        if (received.isNotEmpty()) {
            History.add(HistoryEntry(System.currentTimeMillis(), "pc_in", "PC", received.size, 0, true, received.toList()))
        }
    }

    fun urls(): List<String> = P2p.lanAddresses().map { "http://$it:$port/$secret/" }

    private fun publish() {
        Hub.web.value = WebInfo(urls(), items.size, downloads.get(), uploads.get(), "$port/$secret/")
    }

    private fun readLine(ins: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = ins.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun serve(c: Socket) {
        c.soTimeout = 60_000
        c.sendBufferSize = Proto.SOCK_BUF
        val ins = BufferedInputStream(c.getInputStream(), 64 * 1024)
        val out = BufferedOutputStream(c.getOutputStream(), 64 * 1024)
        val req = readLine(ins) ?: return
        val parts = req.split(' ')
        if (parts.size < 2) return
        val method = parts[0]
        val full = parts[1]
        val headers = HashMap<String, String>()
        while (true) {
            val l = readLine(ins) ?: break
            if (l.isEmpty()) break
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val uri = Uri.parse("http://x$full")
        val path = uri.path ?: "/"
        val pre = "/$secret"
        if (!path.startsWith(pre)) return text(out, 404, "Open the full link shown in the Flikk Share app.")
        val sub = path.removePrefix(pre)
        when {
            method == "GET" && (sub == "" || sub == "/") -> html(out)
            method == "GET" && sub.startsWith("/d/") -> download(out, sub.removePrefix("/d/").toIntOrNull(), headers["range"])
            method == "GET" && sub == "/all.zip" -> zip(out)
            method == "POST" && sub == "/u" -> upload(ins, out, uri, headers)
            else -> text(out, 404, "Not found")
        }
    }

    private fun head(out: OutputStream, code: Int, type: String, len: Long?, extra: String = "") {
        val status = when (code) { 200 -> "OK"; 206 -> "Partial Content"; 400 -> "Bad Request"; 416 -> "Range Not Satisfiable"; else -> "Not Found" }
        val sb = StringBuilder("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nConnection: close\r\nCache-Control: no-store\r\n")
        if (len != null) sb.append("Content-Length: $len\r\n")
        sb.append(extra)
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    private fun text(out: OutputStream, code: Int, msg: String) {
        val b = msg.toByteArray(Charsets.UTF_8)
        head(out, code, "text/plain; charset=utf-8", b.size.toLong())
        out.write(b); out.flush()
    }

    private fun disposition(name: String) =
        "Content-Disposition: attachment; filename=\"${name.replace("\"", "")}\"; filename*=UTF-8''${URLEncoder.encode(name, "UTF-8").replace("+", "%20")}\r\n"

    private fun download(out: OutputStream, idx: Int?, range: String?) {
        val item = idx?.let { items.getOrNull(it) } ?: return text(out, 404, "Not found")
        var start = 0L
        if (range != null && range.startsWith("bytes=")) start = range.removePrefix("bytes=").substringBefore('-').toLongOrNull() ?: 0
        if (start >= item.size && item.size > 0) return text(out, 416, "Bad range")
        val len = item.size - start
        val extra = disposition(item.name) + "Accept-Ranges: bytes\r\n" +
            (if (start > 0) "Content-Range: bytes $start-${item.size - 1}/${item.size}\r\n" else "")
        head(out, if (start > 0) 206 else 200, item.mime, len, extra)
        item.open(ctx, start).use { it.copyTo(out, Proto.BUF) }
        out.flush()
        downloads.incrementAndGet(); publish()
    }

    private fun zip(out: OutputStream) {
        head(out, 200, "application/zip", null, disposition("Flikk Share.zip"))
        val z = ZipOutputStream(out)
        z.setLevel(Deflater.NO_COMPRESSION) // videos/photos already compressed — speed mukhyam
        val used = HashSet<String>()
        for (it in items) {
            var name = if (it.relDir.isEmpty()) it.name else "${it.relDir}/${it.name}"
            var n = 1
            while (!used.add(name)) name = "${name.substringBeforeLast('.')} (${n++}).${name.substringAfterLast('.', "")}".trimEnd('.')
            z.putNextEntry(ZipEntry(name))
            it.open(ctx, 0).use { s -> s.copyTo(z, Proto.BUF) }
            z.closeEntry()
        }
        z.finish(); out.flush()
        downloads.incrementAndGet(); publish()
    }

    private fun upload(ins: InputStream, out: OutputStream, uri: Uri, headers: Map<String, String>) {
        val len = headers["content-length"]?.toLongOrNull() ?: return text(out, 400, "Need Content-Length")
        val name = Storage.clean(uri.getQueryParameter("n") ?: "file")
        val dir = "From PC/" + Storage.cleanDir(uri.getQueryParameter("p") ?: "")
        val slot = Storage.prepare(ctx, name, dir, len, mimeOf(name), null)
        try {
            Storage.Writer(ctx, slot).use { w ->
                val buf = ByteArray(Proto.BUF)
                var left = len
                while (left > 0) {
                    val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw java.io.EOFException()
                    w.write(buf, n); left -= n
                }
            }
            Storage.finish(ctx, slot)
            synchronized(received) { received += name to slot.uri.toString() }
            uploads.incrementAndGet(); publish()
            text(out, 200, "ok")
        } catch (e: Exception) {
            Storage.discard(ctx, slot)
            throw e
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun html(out: OutputStream) {
        val rows = StringBuilder()
        items.forEachIndexed { i, it ->
            val label = if (it.relDir.isEmpty()) it.name else "${it.relDir}/${it.name}"
            rows.append("<a class=f href=\"d/$i\" download><span>${esc(label)}</span><b>${fmtSize(it.size)}</b></a>")
        }
        val total = fmtSize(items.sumOf { it.size })
        val filesCard = if (items.isEmpty()) "" else
            "<div class=c><h2>Files on the phone · ${items.size} · $total</h2>" +
            "<a class=btn href=\"all.zip\">Download all (.zip)</a><div style=\"margin-top:12px\">$rows</div></div>"
        val page = """<!doctype html><html><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<title>Flikk Share · ${esc(Prefs.deviceName)}</title><style>
*{box-sizing:border-box}body{margin:0;background:#0B0F17;color:#fff;font:15px/1.4 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
.w{max-width:760px;margin:0 auto;padding:28px 18px}h1{font-size:22px;letter-spacing:.12em;margin:0}
.m{color:#A9B4C8;margin:4px 0 24px}.c{background:#151B26;border-radius:18px;padding:18px;margin-bottom:18px}
h2{font-size:16px;margin:0 0 12px}.f{display:flex;justify-content:space-between;gap:12px;padding:12px 4px;border-top:1px solid #1E2838;color:#fff;text-decoration:none}
.f:hover{background:#1A2230}.f span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.f b{color:#A9B4C8;font-weight:500;flex:none}
.btn{display:inline-block;background:#7CC4FF;color:#0B0F17;border:0;border-radius:24px;padding:11px 20px;font-weight:600;text-decoration:none;cursor:pointer}
#drop{border:2px dashed #2A3548;border-radius:14px;padding:30px;text-align:center;color:#A9B4C8;cursor:pointer}
#drop.on{border-color:#7CC4FF;color:#fff}.bar{height:6px;background:#1E2838;border-radius:3px;margin-top:8px;overflow:hidden}
.bar i{display:block;height:100%;width:0;background:#7CC4FF}.up{margin-top:12px;font-size:14px}.foot{color:#555;font-size:12px;text-align:center;margin-top:24px}
</style></head><body><div class=w>
<h1>FLIKK SHARE</h1><div class=m>Connected to ${esc(Prefs.deviceName)}</div>
$filesCard
<div class=c><h2>Send files to the phone</h2><div id=drop>Drop files or folders here, or click to choose</div>
<input id=pick type=file multiple hidden><div id=list></div></div>
<div class=foot>Made in India 🇮🇳 · goes straight over your Wi-Fi, nothing is uploaded to the internet</div></div>
<script>
const d=document.getElementById('drop'),p=document.getElementById('pick'),L=document.getElementById('list');
d.onclick=()=>p.click();p.onchange=()=>send([...p.files].map(f=>[f,'']));
d.ondragover=e=>{e.preventDefault();d.classList.add('on')};d.ondragleave=()=>d.classList.remove('on');
d.ondrop=async e=>{e.preventDefault();d.classList.remove('on');const out=[];
 const its=[...e.dataTransfer.items].map(i=>i.webkitGetAsEntry&&i.webkitGetAsEntry()).filter(Boolean);
 async function walk(en,dir){if(en.isFile){await new Promise(r=>en.file(f=>{out.push([f,dir]);r()},r))}
  else if(en.isDirectory){const rd=en.createReader();let b;do{b=await new Promise(r=>rd.readEntries(r,()=>r([])));for(const c of b)await walk(c,dir?dir+'/'+en.name:en.name)}while(b.length)}}
 if(its.length){for(const en of its)await walk(en,'')}else{for(const f of e.dataTransfer.files)out.push([f,''])}
 send(out)};
let q=Promise.resolve();
function send(fs){for(const [f,dir] of fs){const r=document.createElement('div');r.className='up';
 r.innerHTML='<div></div><div class=bar><i></i></div>';r.firstChild.textContent=(dir?dir+'/':'')+f.name;L.appendChild(r);
 q=q.then(()=>new Promise(res=>{const x=new XMLHttpRequest();const t0=Date.now();
  x.open('POST','u?n='+encodeURIComponent(f.name)+'&p='+encodeURIComponent(dir));
  x.upload.onprogress=e=>{r.querySelector('i').style.width=(e.loaded/e.total*100)+'%';
   const s=e.loaded/Math.max(1,(Date.now()-t0)/1000);r.firstChild.textContent=(dir?dir+'/':'')+f.name+' · '+(s/1048576).toFixed(1)+' MB/s'};
  x.onload=()=>{r.firstChild.textContent+=x.status==200?' ✓':' ✗';res()};x.onerror=()=>{r.firstChild.textContent+=' ✗';res()};x.send(f)}))}}
</script></body></html>"""
        val b = page.toByteArray(Charsets.UTF_8)
        head(out, 200, "text/html; charset=utf-8", b.size.toLong())
        out.write(b); out.flush()
    }
}

package com.flikk.share

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Radar lo kanipinche phone (Receive screen open chesi unnadi) */
data class NearbyDevice(
    val id: String,
    val name: String,
    val ssid: String,
    val pass: String,
    val port: Int,
    val band: String,
    val seen: Long,
) {
    fun toTarget() = Target(name, ssid, pass, "", port, "")
    val initial: String get() = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}

/** Chuttu unna Flikk Share receivers ni vethukutundi (internet / same Wi-Fi avasaram ledu) */
object Nearby {
    val devices = MutableStateFlow<List<NearbyDevice>>(emptyList())
    val searching = MutableStateFlow(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val map = ConcurrentHashMap<String, NearbyDevice>()
    private var job: Job? = null
    private var gen = 0

    fun start(ctx: Context) {
        gen++
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        job = scope.launch {
            searching.value = true
            P2p.listen(app) { rec, _ ->
                val id = rec["i"] ?: return@listen
                if (id == Prefs.deviceId) return@listen
                val d = NearbyDevice(
                    id = id,
                    name = rec["n"] ?: "Phone",
                    ssid = rec["s"] ?: return@listen,
                    pass = rec["p"] ?: return@listen,
                    port = rec["o"]?.toIntOrNull() ?: Proto.PORT,
                    band = rec["b"] ?: "",
                    seen = SystemClock.elapsedRealtime(),
                )
                map[id] = d
                publish()
            }
            while (isActive) {
                try { P2p.discover(app) } catch (_: Exception) { }
                repeat(10) {
                    delay(1000)
                    val now = SystemClock.elapsedRealtime()
                    map.values.removeAll { now - it.seen > 45_000 }
                    publish()
                }
            }
        }
    }

    private fun publish() {
        devices.value = map.values.sortedBy { it.id }
    }

    /** Screen vadilesthe aapu (chinna delay — next screen malli start cheyyochu) */
    fun stop(ctx: Context) {
        val g = ++gen
        val app = ctx.applicationContext
        scope.launch {
            delay(600)
            if (g != gen) return@launch
            job?.cancel(); job = null
            searching.value = false
            try { P2p.stopDiscovery(app) } catch (_: Exception) { }
        }
    }

    /** Pampadaniki mundu — Wi-Fi Direct join ki discovery addu raakudadu */
    suspend fun stopNow(ctx: Context) {
        gen++
        job?.cancel(); job = null
        searching.value = false
        try { P2p.stopDiscovery(ctx.applicationContext) } catch (_: Exception) { }
    }

    fun find(id: String?): NearbyDevice? = id?.let { map[it] }
}

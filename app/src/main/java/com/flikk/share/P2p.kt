package com.flikk.share

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.coroutines.resume

/**
 * Wi-Fi Direct: receiver oka chinna "group" (hotspot laantidi) create chestundi,
 * sender QR lo unna name + password tho direct ga join avthundi — router avasaram ledu.
 * 5 GHz band try chestham (fast), kudaraka pothe auto.
 */
@SuppressLint("MissingPermission")
object P2p {
    private const val TAG = "FlikkP2p"
    private var mgr: WifiP2pManager? = null
    private var ch: WifiP2pManager.Channel? = null

    private fun init(ctx: Context): Pair<WifiP2pManager, WifiP2pManager.Channel>? {
        val m = mgr ?: (ctx.applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager) ?: return null
        val c = ch ?: m.initialize(ctx.applicationContext, Looper.getMainLooper()) { ch = null } ?: return null
        mgr = m; ch = c
        return m to c
    }

    private suspend fun action(block: (WifiP2pManager.ActionListener) -> Unit): Int =
        suspendCancellableCoroutine { cont ->
            block(object : WifiP2pManager.ActionListener {
                override fun onSuccess() { if (cont.isActive) cont.resume(-1) }
                override fun onFailure(reason: Int) { if (cont.isActive) cont.resume(reason) }
            })
        }

    private suspend fun groupInfo(m: WifiP2pManager, c: WifiP2pManager.Channel): WifiP2pGroup? =
        suspendCancellableCoroutine { cont -> m.requestGroupInfo(c) { g -> if (cont.isActive) cont.resume(g) } }

    private suspend fun connInfo(m: WifiP2pManager, c: WifiP2pManager.Channel): WifiP2pInfo? =
        suspendCancellableCoroutine { cont -> m.requestConnectionInfo(c) { i -> if (cont.isActive) cont.resume(i) } }

    fun wifiOn(ctx: Context): Boolean =
        (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled

    fun supports5g(ctx: Context): Boolean =
        (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).is5GHzBandSupported

    /** Receiver: group create chesi (ssid, pass, band) ivvu. Fail aithe null. */
    suspend fun createGroup(ctx: Context): Triple<String, String, String>? {
        val (m, c) = init(ctx) ?: return null
        action { m.removeGroup(c, it) }
        delay(300)
        val ssid = "DIRECT-FS-" + Prefs.deviceName.filter { it.isLetterOrDigit() }.take(12).ifEmpty { "Flikk" } + randomToken(3)
        val pass = randomToken(10)
        val bands = if (supports5g(ctx)) listOf(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ, WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
        else listOf(WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
        for (band in bands) {
            for (attempt in 0..2) {
                val cfg = try {
                    WifiP2pConfig.Builder()
                        .setNetworkName(ssid)
                        .setPassphrase(pass)
                        .setGroupOperatingBand(band)
                        .enablePersistentMode(false)
                        .build()
                } catch (e: Exception) { Log.w(TAG, "config", e); return null }
                val r = action { m.createGroup(c, cfg, it) }
                if (r == -1) {
                    // group ready ayye varaku wait
                    repeat(20) {
                        val g = groupInfo(m, c)
                        if (g != null && g.isGroupOwner) {
                            val label = if (band == WifiP2pConfig.GROUP_OWNER_BAND_5GHZ) "5 GHz" else
                                if (g.frequency > 4000) "5 GHz" else "2.4 GHz"
                            return Triple(g.networkName ?: ssid, g.passphrase ?: pass, label)
                        }
                        delay(250)
                    }
                    return Triple(ssid, pass, if (band == WifiP2pConfig.GROUP_OWNER_BAND_5GHZ) "5 GHz" else "Wi-Fi")
                }
                Log.w(TAG, "createGroup band=$band failed reason=$r")
                if (r == WifiP2pManager.BUSY) { action { m.removeGroup(c, it) }; delay(800) } else break
            }
        }
        return null
    }

    /** Sender: QR lo unna group ki join ayyi group owner IP ivvu */
    suspend fun join(ctx: Context, ssid: String, pass: String, timeoutMs: Long = 25_000): String? {
        val (m, c) = init(ctx) ?: return null
        action { m.removeGroup(c, it) }
        action { m.cancelConnect(c, it) }
        delay(300)
        val cfg = WifiP2pConfig.Builder()
            .setNetworkName(ssid)
            .setPassphrase(pass)
            .enablePersistentMode(false)
            .build()
        var r = action { m.connect(c, cfg, it) }
        if (r == WifiP2pManager.BUSY) { delay(1000); r = action { m.connect(c, cfg, it) } }
        if (r != -1) { Log.w(TAG, "connect failed $r"); return null }
        return withTimeoutOrNull(timeoutMs) {
            var host: String? = null
            while (host == null) {
                val info = connInfo(m, c)
                if (info != null && info.groupFormed && !info.isGroupOwner && info.groupOwnerAddress != null) {
                    host = info.groupOwnerAddress.hostAddress
                } else delay(400)
            }
            host
        }
    }

    /** Ippudu join ayina group band (5 GHz / 2.4 GHz) */
    suspend fun band(ctx: Context): String? {
        val (m, c) = init(ctx) ?: return null
        val f = groupInfo(m, c)?.frequency ?: return null
        return when { f >= 5900 -> "6 GHz"; f >= 4900 -> "5 GHz"; f > 0 -> "2.4 GHz"; else -> null }
    }

    suspend fun leave(ctx: Context) {
        val (m, c) = init(ctx) ?: return
        action { m.clearLocalServices(c, it) }
        action { m.removeGroup(c, it) }
    }

    // ---------- Nearby (radar): Wi-Fi Direct service discovery ----------
    const val SERVICE = "_flikkshare._tcp"

    /** Receiver: "nenu ikkada unna" ani chuttu unna phones ki cheppu */
    suspend fun advertise(ctx: Context, record: Map<String, String>) {
        val (m, c) = init(ctx) ?: return
        action { m.clearLocalServices(c, it) }
        val info = WifiP2pDnsSdServiceInfo.newInstance("Flikk-" + Prefs.deviceId, SERVICE, record)
        val r = action { m.addLocalService(c, info, it) }
        if (r != -1) Log.w(TAG, "addLocalService failed $r")
        action { m.discoverPeers(c, it) }
    }

    /** Konni phones lo discoverable ga undadaniki peer discovery nadusthu undali */
    suspend fun ping(ctx: Context) {
        val (m, c) = init(ctx) ?: return
        action { m.discoverPeers(c, it) }
    }

    fun listen(ctx: Context, onFound: (Map<String, String>, WifiP2pDevice) -> Unit) {
        val (m, c) = init(ctx) ?: return
        m.setDnsSdResponseListeners(c,
            { _, _, _ -> },
            { domain, rec, dev -> if (domain != null && domain.contains("_flikkshare") && rec != null && dev != null) onFound(rec, dev) })
    }

    suspend fun discover(ctx: Context) {
        val (m, c) = init(ctx) ?: return
        action { m.clearServiceRequests(c, it) }
        action { m.addServiceRequest(c, WifiP2pDnsSdServiceRequest.newInstance(), it) }
        action { m.discoverPeers(c, it) }
        val r = action { m.discoverServices(c, it) }
        if (r != -1) Log.w(TAG, "discoverServices failed $r")
    }

    suspend fun stopDiscovery(ctx: Context) {
        val (m, c) = init(ctx) ?: return
        action { m.clearServiceRequests(c, it) }
        action { m.stopPeerDiscovery(c, it) }
    }

    /** Same Wi-Fi / hotspot lo ee phone IP addresses (p2p interface tappinchi) */
    fun lanAddresses(): List<String> {
        val out = mutableListOf<Pair<String, String>>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                if (ni.name.startsWith("p2p")) continue
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress) out += ni.name to a.hostAddress!!
                }
            }
        } catch (_: Exception) { }
        // wlan0 modata
        return out.sortedBy { if (it.first.startsWith("wlan")) 0 else if (it.first.startsWith("swlan") || it.first.startsWith("ap")) 1 else 2 }
            .map { it.second }.distinct()
    }
}

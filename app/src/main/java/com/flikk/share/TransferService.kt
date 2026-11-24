package com.flikk.share

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Screen off aina, app minimize chesina transfer aagakunda — foreground service */
class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recvJob: Job? = null
    private var sendJob: Job? = null
    private var web: WebServer? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var fastLock: WifiManager.WifiLock? = null
    private var notifJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(getString(R.string.app_name), null)
        when (intent?.action) {
            ACT_RECV_START -> if (recvJob?.isActive != true) {
                recvJob = scope.launch { try { Receiver.run(this@TransferService) } catch (_: Exception) { } }
            }
            ACT_RECV_STOP -> { Receiver.stop(); recvJob?.cancel(); recvJob = null }
            ACT_SEND -> {
                val items = Hub.pending
                val t = Hub.lastTarget
                if (items.isNotEmpty() && t != null && sendJob?.isActive != true) {
                    sendJob = scope.launch { try { Sender.run(this@TransferService, items, t) } catch (_: Exception) { } }
                }
            }
            ACT_CANCEL -> {
                Sender.abort(); sendJob?.cancel()
                val s = Hub.state.value
                if (s.phase == Phase.CONNECTING || s.phase == Phase.TRANSFERRING)
                    Hub.state.value = s.copy(phase = Phase.FAILED, message = "Stopped")
                scope.launch { try { P2p.leave(this@TransferService) } catch (_: Exception) { } }
            }
            ACT_WEB_START -> if (web == null) {
                web = WebServer(this, Hub.webItems).also { w -> try { w.start() } catch (_: Exception) { web = null } }
            }
            ACT_WEB_STOP -> { web?.stop(); web = null; Hub.web.value = null }
        }
        locks(true)
        startNotifLoop()
        scope.launch { delay(500); stopIfIdle() }
        return START_NOT_STICKY
    }

    private fun busy() = recvJob?.isActive == true || sendJob?.isActive == true || web != null

    private fun stopIfIdle() {
        if (!busy()) {
            locks(false)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startNotifLoop() {
        if (notifJob?.isActive == true) return
        notifJob = scope.launch {
            launch { while (true) { delay(2000); stopIfIdle() } }
            Hub.state.collectLatest { s ->
                val (title, pct) = when (s.phase) {
                    Phase.TRANSFERRING -> {
                        val p = if (s.total > 0) (s.done * 100 / s.total).toInt() else 0
                        val who = if (s.role == "send") getString(R.string.sending_to, s.peer) else getString(R.string.receiving_from, s.peer)
                        "$who · $p% · ${fmtSize(s.speed)}/s" to p
                    }
                    Phase.CONNECTING -> getString(R.string.connecting, s.peer) to -1
                    else -> (if (web != null) getString(R.string.notif_pc)
                        else if (recvJob?.isActive == true) getString(R.string.notif_receiving)
                        else getString(R.string.app_name)) to null
                }
                goForeground(title, pct)
                delay(700)
            }
        }
    }

    private fun goForeground(title: String, pct: Int?) {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, App.CHANNEL)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (pct != null) b.setProgress(100, pct.coerceAtLeast(0), pct < 0)
        val n: Notification = b.build()
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (_: Exception) {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
        }
    }

    @Suppress("DEPRECATION")
    private fun locks(on: Boolean) {
        if (on) {
            if (wake == null) wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "flikk:transfer").apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
            if (wifiLock == null) wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "flikk:wifi").apply { setReferenceCounted(false); acquire() }
            // Wi-Fi power save off — transfer speed kosam
            if (fastLock == null) fastLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "flikk:fast").apply { setReferenceCounted(false); acquire() }
        } else {
            try { wake?.release() } catch (_: Exception) { }
            try { wifiLock?.release() } catch (_: Exception) { }
            try { fastLock?.release() } catch (_: Exception) { }
            wake = null; wifiLock = null; fastLock = null
        }
    }

    override fun onDestroy() {
        Receiver.stop(); Sender.abort()
        web?.stop(); web = null
        locks(false)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 7
        const val ACT_RECV_START = "recv_start"
        const val ACT_RECV_STOP = "recv_stop"
        const val ACT_SEND = "send"
        const val ACT_CANCEL = "cancel"
        const val ACT_WEB_START = "web_start"
        const val ACT_WEB_STOP = "web_stop"

        fun go(ctx: Context, action: String) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, TransferService::class.java).setAction(action))
        }
    }
}

package com.flikk.share

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Wi-Fi Direct ki kavalsina permissions + Wi-Fi / Location ON check */
open class BaseActivity : AppCompatActivity() {
    private var pendingOk: (() -> Unit)? = null
    private var awaitingSettings = false

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (needed().all(::granted)) checkRadios()
        else {
            pendingOk = null
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.need_perm)
                .setPositiveButton(R.string.settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun needed(): List<String> =
        if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun withWifiReady(ok: () -> Unit) {
        pendingOk = ok
        val ask = needed().filterNot(::granted).toMutableList()
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) ask += Manifest.permission.POST_NOTIFICATIONS
        if (ask.isEmpty()) checkRadios() else permLauncher.launch(ask.toTypedArray())
    }

    fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS))
            registerLessNotif()
    }

    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private fun registerLessNotif() = notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

    private fun checkRadios() {
        if (!P2p.wifiOn(this)) {
            awaitingSettings = true
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.wifi_off)
                .setPositiveButton(R.string.settings) { _, _ -> startActivity(Intent(Settings.Panel.ACTION_WIFI)) }
                .setNegativeButton(R.string.cancel) { _, _ -> pendingOk = null; awaitingSettings = false }
                .show()
            return
        }
        if (Build.VERSION.SDK_INT <= 32) {
            val lm = getSystemService(LocationManager::class.java)
            if (!lm.isLocationEnabled) {
                awaitingSettings = true
                MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.location_off)
                    .setPositiveButton(R.string.settings) { _, _ -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                    .setNegativeButton(R.string.cancel) { _, _ -> pendingOk = null; awaitingSettings = false }
                    .show()
                return
            }
        }
        awaitingSettings = false
        val ok = pendingOk
        pendingOk = null
        ok?.invoke()
    }

    override fun onResume() {
        super.onResume()
        if (awaitingSettings && pendingOk != null) {
            val wifi = P2p.wifiOn(this)
            val loc = Build.VERSION.SDK_INT > 32 || getSystemService(LocationManager::class.java).isLocationEnabled
            if (wifi && loc) checkRadios()
        }
    }
}

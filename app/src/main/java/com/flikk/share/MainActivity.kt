package com.flikk.share

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Radar home: chuttu unna phones kanipistayi, tap chesi pampochu */
class MainActivity : BaseActivity() {
    private lateinit var radar: RadarView
    private lateinit var sub: TextView
    private lateinit var you: TextView
    private var askedOnce = false
    private var startedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen(null, scroll = true) {
            setPadding(dp(20), dp(24), dp(20), dp(20))
            val top = hbox()
            top.addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_launcher_foreground)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, lp(dp(40), dp(40)))
            top.addView(label(getString(R.string.app_name), 20f, bold = true).apply { setPadding(dp(8), 0, 0, 0) }, lp(0, -2, 1f))
            val hist = FrameLayout(this@MainActivity).apply {
                background = round(C.SURFACE, dp(22).toFloat())
                contentDescription = getString(R.string.history)
                isClickable = true
                setOnClickListener { startActivity(Intent(this@MainActivity, HistoryActivity::class.java)) }
                addView(icon(R.drawable.ic_history, 22).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
                })
            }
            top.addView(hist, lp(dp(44), dp(44)))
            addView(top, lp())

            addView(label(getString(R.string.radar_title), 30f, bold = true).apply {
                setLineSpacing(0f, 1.05f)
            }, lp().apply { topMargin = dp(22) })
            sub = label(getString(R.string.radar_sub), 14f, C.MUTED)
            sub.setOnClickListener { fixRadios() }
            addView(sub, lp().apply { topMargin = dp(6) })

            radar = RadarView(this@MainActivity)
            radar.onTap = { d -> sendTo(d) }
            addView(radar, lp(h = dp(340)).apply { topMargin = dp(14) })
            you = label("", 12f, C.MUTED).apply { gravity = Gravity.CENTER }
            you.setOnClickListener { rename() }
            addView(you, lp())

            addView(View(this@MainActivity), lp(h = 0, weight = 1f))
            val row = hbox()
            val send = button(getString(R.string.send)) { startActivity(Intent(this@MainActivity, SendActivity::class.java)) }
            send.setIconResource(R.drawable.ic_send)
            send.iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
            row.addView(send, lp(0, -2, 1f).apply { rightMargin = dp(5) })
            val recv = button(getString(R.string.receive), primary = false) {
                Nearby.stop(this@MainActivity)
                withWifiReady { startActivity(Intent(this@MainActivity, ReceiveActivity::class.java)) }
            }
            recv.setIconResource(R.drawable.ic_receive)
            recv.iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
            row.addView(recv, lp(0, -2, 1f).apply { leftMargin = dp(5) })
            addView(row, lp().apply { topMargin = dp(16) })

            val pc = hbox().apply {
                gravity = Gravity.CENTER
                isClickable = true
                minimumHeight = dp(48)
                setOnClickListener {
                    Hub.webItems = emptyList()
                    startActivity(Intent(this@MainActivity, WebShareActivity::class.java))
                }
            }
            pc.addView(icon(R.drawable.ic_computer, 18, C.SOFT))
            pc.addView(label(getString(R.string.pc_instead), 14f, C.SOFT).apply { setPadding(dp(8), 0, 0, 0) })
            addView(pc, lp().apply { topMargin = dp(6) })
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Nearby.devices.collect { list ->
                    radar.setDevices(list)
                    updateSub(list.size)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { delay(4000); updateSub(Nearby.devices.value.size) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        you.text = getString(R.string.visible_as, Prefs.deviceName)
        startedAt = System.currentTimeMillis()
        if (hasNearbyPerm()) {
            if (radiosReady()) Nearby.start(this)
        } else if (!askedOnce) {
            askedOnce = true
            withWifiReady { Nearby.start(this) }
        }
        updateSub(Nearby.devices.value.size)
    }

    override fun onPause() {
        Nearby.stop(this)
        super.onPause()
    }

    private fun updateSub(count: Int) {
        sub.setTextColor(C.MUTED)
        sub.text = when {
            !hasNearbyPerm() -> getString(R.string.need_perm_short)
            !radiosReady() -> { sub.setTextColor(C.ACCENT); getString(R.string.radar_wifi_off) }
            count == 0 && System.currentTimeMillis() - startedAt > 12_000 -> getString(R.string.radar_none)
            else -> getString(R.string.radar_sub)
        }
    }

    private fun fixRadios() {
        withWifiReady { Nearby.start(this) }
    }

    private fun sendTo(d: NearbyDevice) {
        startActivity(Intent(this, SendActivity::class.java).putExtra(SendActivity.EXTRA_DEVICE, d.id))
    }

    private fun rename() {
        val e = EditText(this).apply { setText(Prefs.deviceName); setSelection(text.length); setSingleLine() }
        val box = FrameLayout(this).apply { setPadding(dp(22), dp(8), dp(22), 0); addView(e) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                if (e.text.isNotBlank()) Prefs.deviceName = e.text.toString()
                you.text = getString(R.string.visible_as, Prefs.deviceName)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

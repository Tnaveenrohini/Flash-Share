package com.flikk.share

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : BaseActivity() {
    private lateinit var nameView: android.widget.TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen(null, scroll = true) {
            setPadding(dp(20), dp(28), dp(20), dp(20))
            // Logo + brand
            val top = hbox()
            val logo = FrameLayout(this@MainActivity).apply {
                background = round(0xFF111114.toInt(), dp(16).toFloat())
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_launcher_foreground)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }, FrameLayout.LayoutParams(-1, -1))
            }
            top.addView(logo, lp(dp(52), dp(52)))
            val tb = vbox().apply { setPadding(dp(14), 0, 0, 0) }
            tb.addView(label(getString(R.string.brand), 22f, bold = true).apply { letterSpacing = 0.12f })
            nameView = label("", 13f, C.MUTED)
            nameView.setOnClickListener { rename() }
            tb.addView(nameView)
            top.addView(tb, lp(0, -2, 1f))
            addView(top, lp())

            val row = hbox().apply { gravity = Gravity.TOP }
            row.addView(card(R.drawable.ic_send, getString(R.string.send), getString(R.string.send_sub), true) {
                startActivity(Intent(this@MainActivity, SendActivity::class.java))
            }, lp(0, -1, 1f).apply { rightMargin = dp(6) })
            row.addView(card(R.drawable.ic_receive, getString(R.string.receive), getString(R.string.receive_sub), true) {
                withWifiReady { startActivity(Intent(this@MainActivity, ReceiveActivity::class.java)) }
            }, lp(0, -1, 1f).apply { leftMargin = dp(6) })
            addView(row, lp().apply { topMargin = dp(28) })

            addView(card(R.drawable.ic_computer, getString(R.string.pc), getString(R.string.pc_sub), false) {
                Hub.webItems = emptyList()
                startActivity(Intent(this@MainActivity, WebShareActivity::class.java))
            }, lp().apply { topMargin = dp(12) })
            addView(card(R.drawable.ic_history, getString(R.string.history), getString(R.string.history_sub), false) {
                startActivity(Intent(this@MainActivity, HistoryActivity::class.java))
            }, lp().apply { topMargin = dp(12) })

            addView(android.view.View(this@MainActivity), lp(h = 0, weight = 1f))
            addView(label(getString(R.string.footer), 12f, C.MUTED).apply { gravity = Gravity.CENTER }, lp().apply { topMargin = dp(32) })
        }
    }

    override fun onResume() {
        super.onResume()
        nameView.text = getString(R.string.visible_as, Prefs.deviceName)
    }

    private fun rename() {
        val e = EditText(this).apply { setText(Prefs.deviceName); setSelection(text.length); setSingleLine() }
        val box = FrameLayout(this).apply { setPadding(dp(22), dp(8), dp(22), 0); addView(e) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                if (e.text.isNotBlank()) Prefs.deviceName = e.text.toString()
                nameView.text = getString(R.string.visible_as, Prefs.deviceName)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

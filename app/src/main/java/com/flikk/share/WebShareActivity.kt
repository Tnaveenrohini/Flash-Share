package com.flikk.share

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Phone ↔ PC: browser lo open chese link + QR */
class WebShareActivity : BaseActivity() {
    private lateinit var url: TextView
    private lateinit var more: TextView
    private lateinit var qr: ImageView
    private lateinit var stats: TextView
    private lateinit var shared: TextView
    private var lastUrl = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askNotifications()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        screen(getString(R.string.pc_title), scroll = true) {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(label(getString(R.string.pc_hint), 15f, C.MUTED).apply { gravity = Gravity.CENTER }, lp().apply { topMargin = dp(8) })
            url = label("", 22f, bold = true).apply {
                gravity = Gravity.CENTER
                setTextIsSelectable(true)
                background = round(C.SURFACE, dp(18).toFloat())
                setPadding(dp(16), dp(18), dp(16), dp(18))
                setOnClickListener { copy() }
            }
            addView(url, lp().apply { topMargin = dp(18) })
            more = label("", 13f, C.MUTED).apply { gravity = Gravity.CENTER }
            addView(more, lp().apply { topMargin = dp(8) })
            val frame = FrameLayout(this@WebShareActivity).apply {
                background = round(Color.WHITE, dp(20).toFloat())
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
            qr = ImageView(this@WebShareActivity)
            frame.addView(qr, FrameLayout.LayoutParams(-1, -1))
            addView(frame, lp(dp(200), dp(200)).apply { topMargin = dp(18) })
            shared = label("", 14f).apply { gravity = Gravity.CENTER }
            addView(shared, lp().apply { topMargin = dp(20) })
            stats = label("", 14f, C.GOOD).apply { gravity = Gravity.CENTER }
            addView(stats, lp().apply { topMargin = dp(8) })
            addView(View(this@WebShareActivity), lp(h = 0, weight = 1f))
            addView(label(getString(R.string.saved_to), 12f, C.MUTED).apply { gravity = Gravity.CENTER }, lp().apply { topMargin = dp(24) })
        }
        TransferService.go(this, TransferService.ACT_WEB_START)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    // Wi-Fi / hotspot on chesthe IP marchochu — refresh
                    while (true) { delay(3000); Hub.web.value?.let { w -> Hub.web.value = w.copy(urls = currentUrls(w)) } }
                }
                Hub.web.collect { w -> render(w) }
            }
        }
    }

    private fun currentUrls(w: WebInfo): List<String> {
        if (w.base.isEmpty()) return w.urls
        return P2p.lanAddresses().map { "http://$it:${w.base}" }
    }

    private fun render(w: WebInfo?) {
        if (w == null) { url.text = getString(R.string.starting); return }
        val u = w.urls.firstOrNull()
        if (u == null) {
            url.text = getString(R.string.pc_no_wifi); url.setTextColor(C.BAD); qr.setImageDrawable(null); more.text = ""
        } else {
            url.setTextColor(C.TEXT)
            url.text = u.removePrefix("http://")
            more.text = w.urls.drop(1).joinToString("\n") { it.removePrefix("http://") }
            if (u != lastUrl) { lastUrl = u; qr.setImageBitmap(qrBitmap(u, dp(180))) }
        }
        shared.text = getString(R.string.pc_files, w.shared)
        stats.text = getString(R.string.pc_stats, w.downloads, w.uploads)
    }

    private fun copy() {
        val u = Hub.web.value?.urls?.firstOrNull() ?: return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Flikk Share", u))
        Toast.makeText(this, u, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        if (isFinishing) TransferService.go(this, TransferService.ACT_WEB_STOP)
        super.onDestroy()
    }
}

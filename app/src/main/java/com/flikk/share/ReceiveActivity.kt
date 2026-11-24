package com.flikk.share

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.journeyapps.barcodescanner.BarcodeEncoder
import kotlinx.coroutines.launch

fun qrBitmap(text: String, size: Int): Bitmap =
    BarcodeEncoder().encodeBitmap(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))

/** Receiver: QR chupinchi wait chestundi, files vachinappudu progress */
class ReceiveActivity : BaseActivity() {
    private lateinit var qrBox: View
    private lateinit var qr: ImageView
    private lateinit var spinner: ProgressBar
    private lateinit var name: TextView
    private lateinit var mode: TextView
    private lateinit var hint: TextView
    private lateinit var panel: ProgressPanel
    private lateinit var again: MaterialButton
    private lateinit var files: MaterialButton
    private var lastQr = ""
    private var dialog: androidx.appcompat.app.AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        screen(getString(R.string.receive), scroll = true) {
            gravity = Gravity.CENTER_HORIZONTAL
            qrBox = vbox().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(label(getString(R.string.receive_title), 24f, bold = true).apply { gravity = Gravity.CENTER })
                name = label("", 15f, C.MUTED).apply { gravity = Gravity.CENTER }
                addView(name, lp().apply { topMargin = dp(4) })
                val frame = FrameLayout(this@ReceiveActivity).apply {
                    background = round(Color.WHITE, dp(24).toFloat())
                    setPadding(dp(16), dp(16), dp(16), dp(16))
                }
                qr = ImageView(this@ReceiveActivity)
                spinner = ProgressBar(this@ReceiveActivity)
                frame.addView(qr, FrameLayout.LayoutParams(-1, -1))
                frame.addView(spinner, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
                addView(frame, lp(dp(270), dp(270)).apply { topMargin = dp(24) })
                mode = label(getString(R.string.starting), 14f, C.GOOD).apply { gravity = Gravity.CENTER }
                addView(mode, lp().apply { topMargin = dp(18) })
                hint = label(getString(R.string.receive_hint), 14f, C.MUTED).apply { gravity = Gravity.CENTER }
                addView(hint, lp().apply { topMargin = dp(10) })
                addView(label(getString(R.string.saved_to), 12f, C.MUTED).apply { gravity = Gravity.CENTER }, lp().apply { topMargin = dp(18) })
            }
            addView(qrBox, lp().apply { topMargin = dp(12) })

            panel = ProgressPanel(this@ReceiveActivity).apply { visibility = View.GONE }
            addView(panel, lp().apply { topMargin = dp(60) })
            addView(View(this@ReceiveActivity), lp(h = 0, weight = 1f))
            files = button(getString(R.string.open_downloads)) { openDownloads() }.apply { visibility = View.GONE }
            addView(files, lp().apply { topMargin = dp(28) })
            again = button(getString(R.string.receive), primary = false) {
                Hub.state.value = TState(role = "recv", phase = Phase.WAITING)
            }.apply { visibility = View.GONE }
            addView(again, lp().apply { topMargin = dp(10) })
        }

        Hub.state.value = TState(role = "recv", phase = Phase.WAITING)
        TransferService.go(this, TransferService.ACT_RECV_START)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    Hub.receive.collect { info ->
                        name.text = Prefs.deviceName
                        if (info == null) { spinner.visibility = View.VISIBLE; qr.setImageDrawable(null); mode.text = getString(R.string.starting); return@collect }
                        if (info.target == null) {
                            spinner.visibility = View.GONE
                            mode.setTextColor(C.BAD); mode.text = info.error.ifEmpty { getString(R.string.wifi_off) }
                            return@collect
                        }
                        val text = info.target.toUri()
                        if (text != lastQr) { lastQr = text; qr.setImageBitmap(qrBitmap(text, dp(240))) }
                        spinner.visibility = View.GONE
                        mode.setTextColor(if (info.direct) C.GOOD else C.MUTED)
                        mode.text = if (info.direct) getString(R.string.mode_direct, info.band) else getString(R.string.mode_lan)
                    }
                }
                launch {
                    Hub.incoming.collect { req ->
                        dialog?.dismiss(); dialog = null
                        if (req != null) {
                            dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this@ReceiveActivity)
                                .setTitle(req.peer)
                                .setMessage(getString(R.string.incoming, req.count, fmtSize(req.bytes)))
                                .setCancelable(false)
                                .setPositiveButton(R.string.accept) { _, _ -> req.answer.complete(true) }
                                .setNegativeButton(R.string.decline) { _, _ -> req.answer.complete(false) }
                                .show()
                        }
                    }
                }
                Hub.state.collect { s ->
                    if (s.role != "recv") return@collect
                    val active = s.phase == Phase.TRANSFERRING || s.phase == Phase.DONE || s.phase == Phase.FAILED
                    qrBox.visibility = if (active) View.GONE else View.VISIBLE
                    panel.visibility = if (active) View.VISIBLE else View.GONE
                    if (active) panel.render(s)
                    val finished = s.phase == Phase.DONE || s.phase == Phase.FAILED
                    again.visibility = if (finished) View.VISIBLE else View.GONE
                    files.visibility = if (s.phase == Phase.DONE) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun openDownloads() {
        val i = Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(i) } catch (_: Exception) { startActivity(Intent(this, HistoryActivity::class.java)) }
    }

    override fun onDestroy() {
        if (isFinishing) TransferService.go(this, TransferService.ACT_RECV_STOP)
        super.onDestroy()
    }
}

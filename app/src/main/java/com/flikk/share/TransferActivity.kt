package com.flikk.share

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/** Sender side progress screen */
class TransferActivity : BaseActivity() {
    private lateinit var panel: ProgressPanel
    private lateinit var main: MaterialButton
    private lateinit var retry: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        screen(getString(R.string.send), scroll = false) {
            addView(View(this@TransferActivity), lp(h = 0, weight = 1f))
            panel = ProgressPanel(this@TransferActivity)
            addView(panel, lp())
            addView(View(this@TransferActivity), lp(h = 0, weight = 1f))
            retry = button(getString(R.string.retry)) {
                val t = Hub.lastTarget ?: return@button
                Hub.state.value = TState(role = "send", phase = Phase.CONNECTING, peer = t.name)
                TransferService.go(this@TransferActivity, TransferService.ACT_SEND)
            }
            addView(retry, lp().apply { bottomMargin = dp(10) })
            main = button(getString(R.string.stop), primary = false) { onMain() }
            addView(main, lp())
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Hub.state.collect { s ->
                    if (s.role != "send") return@collect
                    panel.render(s)
                    retry.visibility = if (s.phase == Phase.FAILED) View.VISIBLE else View.GONE
                    main.text = getString(if (s.phase == Phase.DONE || s.phase == Phase.FAILED) R.string.done else R.string.stop)
                }
            }
        }
    }

    private fun onMain() {
        val s = Hub.state.value
        if (s.phase == Phase.CONNECTING || s.phase == Phase.TRANSFERRING) {
            TransferService.go(this, TransferService.ACT_CANCEL)
        } else {
            if (s.phase == Phase.DONE) {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            }
            finish()
        }
    }
}

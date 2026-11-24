package com.flikk.share

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import com.google.android.material.progressindicator.LinearProgressIndicator

/** Pedda % + speed + ETA — send, receive rendu screens lo vaadatham */
class ProgressPanel(ctx: Context) : LinearLayout(ctx) {
    private val title = ctx.label("", 16f, C.MUTED).apply { gravity = Gravity.CENTER }
    private val pct = ctx.label("", 64f, bold = true).apply { gravity = Gravity.CENTER }
    private val bar = LinearProgressIndicator(ctx).apply {
        max = 1000
        trackCornerRadius = ctx.dp(4)
        trackThickness = ctx.dp(8)
        setIndicatorColor(C.ACCENT)
        trackColor = C.LINE
    }
    private val speed = ctx.label("", 17f, bold = true).apply { gravity = Gravity.CENTER }
    private val file = ctx.label("", 13f, C.MUTED).apply { gravity = Gravity.CENTER; maxLines = 2 }
    private val via = ctx.label("", 13f, C.GOOD).apply { gravity = Gravity.CENTER }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(title, lp())
        addView(pct, lp().apply { topMargin = ctx.dp(12) })
        addView(bar, lp().apply { topMargin = ctx.dp(12) })
        addView(speed, lp().apply { topMargin = ctx.dp(18) })
        addView(file, lp().apply { topMargin = ctx.dp(6) })
        addView(via, lp().apply { topMargin = ctx.dp(10) })
    }

    fun render(s: TState) {
        val c = context
        val p = if (s.total > 0) (s.done * 1000 / s.total).toInt() else if (s.phase == Phase.DONE) 1000 else 0
        bar.isIndeterminate = s.phase == Phase.CONNECTING
        if (!bar.isIndeterminate) bar.setProgressCompat(p, true)
        pct.text = "${p / 10}%"
        via.text = if (s.via.isNotEmpty()) "⚡ " + c.getString(R.string.via, s.via) else ""
        when (s.phase) {
            Phase.CONNECTING -> {
                title.text = if (s.message == "accept") c.getString(R.string.waiting_accept, s.peer) else c.getString(R.string.connecting, s.peer)
                pct.text = "…"; speed.text = ""
                file.text = if (s.total > 0) c.getString(R.string.selected_summary, s.files.size, fmtSize(s.total)) else ""
            }
            Phase.TRANSFERRING, Phase.WAITING, Phase.IDLE -> {
                title.text = if (s.role == "send") c.getString(R.string.sending_to, s.peer) else c.getString(R.string.receiving_from, s.peer)
                val eta = if (s.speed > 0) (s.total - s.done) / s.speed else -1
                speed.text = c.getString(R.string.speed_eta, fmtSize(s.speed), fmtTime(eta))
                val f = s.files.getOrNull(s.current)
                file.text = if (f != null) c.getString(R.string.file_of, s.current + 1, s.files.size) + " · " + f.name else ""
            }
            Phase.DONE -> {
                title.text = if (s.role == "send") c.getString(R.string.done_sent, s.files.size, fmtSize(s.total))
                else c.getString(R.string.done_recv, s.files.size, fmtSize(s.total))
                pct.text = "✓"
                speed.text = c.getString(R.string.avg_speed, fmtSize(s.avgSpeed) + "/s")
                file.text = if (s.role == "recv") c.getString(R.string.saved_to) else ""
            }
            Phase.FAILED -> {
                title.text = c.getString(R.string.failed)
                speed.text = ""
                file.text = s.message
            }
        }
        pct.setTextColor(if (s.phase == Phase.FAILED) C.BAD else if (s.phase == Phase.DONE) C.GOOD else C.TEXT)
    }
}

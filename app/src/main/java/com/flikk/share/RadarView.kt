package com.flikk.share

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Home screen radar: rings + pulse + madhyalo mana phone + chuttu nearby phones */
class RadarView(ctx: Context) : FrameLayout(ctx) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dp(1).toFloat() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = C.ACCENT }
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dp(2).toFloat(); color = C.ACCENT }
    private var pulse = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
    }
    private val center = FrameLayout(ctx).apply {
        background = round(C.ACCENT, ctx.dp(40).toFloat())
        addView(ctx.icon(R.drawable.ic_phone, 34, C.ON_ACCENT).apply {
            layoutParams = LayoutParams(ctx.dp(34), ctx.dp(34), Gravity.CENTER)
        })
    }
    private var devices: List<NearbyDevice> = emptyList()
    private val bubbles = mutableListOf<View>()
    var onTap: ((NearbyDevice) -> Unit)? = null

    // Bubbles kosam sthalalu (degrees) — design lo laaga
    private val slots = listOf(-135.0, -50.0, 25.0, 150.0, 95.0, -95.0, 200.0, 60.0)

    init {
        setWillNotDraw(false)
        clipChildren = false
        addView(center, LayoutParams(ctx.dp(80), ctx.dp(80), Gravity.CENTER))
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f; val cy = height / 2f
        val r = min(cx, cy) - ring.strokeWidth
        ring.color = C.LINE
        canvas.drawCircle(cx, cy, r, ring)
        ring.color = 0xFF26334A.toInt()
        canvas.drawCircle(cx, cy, r * 0.686f, ring)
        fill.alpha = 40
        canvas.drawCircle(cx, cy, r * 0.37f, fill)
        val pr = r * 0.23f + pulse * r * 0.77f
        pulsePaint.alpha = ((1f - pulse) * 170).toInt()
        canvas.drawCircle(cx, cy, pr, pulsePaint)
    }

    fun setDevices(list: List<NearbyDevice>) {
        if (list.map { it.id to it.name } == devices.map { it.id to it.name }) { devices = list; return }
        devices = list
        bubbles.forEach { removeView(it) }
        bubbles.clear()
        list.take(slots.size).forEach { d ->
            val b = bubble(d)
            bubbles += b
            addView(b, LayoutParams(context.dp(84), LayoutParams.WRAP_CONTENT))
        }
        place()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        post { place() }
    }

    private fun place() {
        if (width == 0) return
        val cx = width / 2f; val cy = height / 2f
        val rr = min(cx, cy) * 0.70f
        bubbles.forEachIndexed { i, b ->
            val a = Math.toRadians(slots[i])
            val lp = b.layoutParams as LayoutParams
            lp.gravity = Gravity.TOP or Gravity.START
            lp.leftMargin = (cx + cos(a) * rr - context.dp(42)).toInt()
            lp.topMargin = (cy + sin(a) * rr - context.dp(26)).toInt()
            b.layoutParams = lp
        }
    }

    private fun bubble(d: NearbyDevice): View {
        val ctx = context
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            contentDescription = ctx.getString(R.string.send_to, d.name)
            setOnClickListener { onTap?.invoke(d) }
        }
        val circle = TextView(ctx).apply {
            text = d.initial
            textSize = 18f
            setTextColor(0xFFE6EEFF.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = round(0xFF1A2230.toInt(), ctx.dp(26).toFloat(), 0xFF3B4A63.toInt(), ctx.dp(1))
        }
        box.addView(circle, LinearLayout.LayoutParams(ctx.dp(52), ctx.dp(52)))
        box.addView(ctx.label(d.name, 12f, C.SOFT).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER
            setPadding(0, ctx.dp(6), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
        return box
    }
}

package com.flikk.share

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

object C {
    const val BG = Color.BLACK
    val SURFACE = Color.parseColor("#141414")
    val LINE = Color.parseColor("#1F1F1F")
    const val TEXT = Color.WHITE
    val MUTED = Color.parseColor("#A3A3A3")
    val GOOD = Color.parseColor("#3DDC84")
    val BAD = Color.parseColor("#FF5C5C")
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun round(color: Int, radius: Float, stroke: Int? = null, strokeW: Int = 0) = GradientDrawable().apply {
    setColor(color); cornerRadius = radius
    if (stroke != null) setStroke(strokeW, stroke)
}

fun Context.label(s: CharSequence = "", size: Float = 15f, color: Int = C.TEXT, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

fun Context.vbox(pad: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
}

fun Context.hbox(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun Context.icon(res: Int, sizeDp: Int = 24, tint: Int = C.TEXT): ImageView = ImageView(this).apply {
    setImageResource(res)
    imageTintList = ColorStateList.valueOf(tint)
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

fun Context.button(s: CharSequence, primary: Boolean = true, onClick: () -> Unit): MaterialButton =
    MaterialButton(this).apply {
        text = s
        isAllCaps = false
        textSize = 16f
        cornerRadius = dp(28)
        minHeight = dp(54)
        insetTop = 0; insetBottom = 0
        if (primary) {
            backgroundTintList = ColorStateList.valueOf(Color.WHITE)
            setTextColor(Color.BLACK)
        } else {
            backgroundTintList = ColorStateList.valueOf(C.SURFACE)
            setTextColor(Color.WHITE)
            strokeColor = ColorStateList.valueOf(Color.parseColor("#33FFFFFF"))
            strokeWidth = dp(1)
        }
        setOnClickListener { onClick() }
    }

fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
    LinearLayout.LayoutParams(w, h, weight)

fun View.margins(ctx: Context, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): View {
    val p = (layoutParams as? ViewGroup.MarginLayoutParams) ?: lp()
    p.setMargins(ctx.dp(l), ctx.dp(t), ctx.dp(r), ctx.dp(b))
    layoutParams = p
    return this
}

/** Top bar: back + title, kinda content. */
fun AppCompatActivity.screen(title: String?, scroll: Boolean, body: LinearLayout.() -> Unit): LinearLayout {
    val root = vbox()
    root.setBackgroundColor(C.BG)
    if (title != null) {
        val bar = hbox().apply { setPadding(dp(8), dp(8), dp(16), dp(8)) }
        val back = icon(R.drawable.ic_back, 48).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { onBackPressedDispatcher.onBackPressed() }
            background = android.util.TypedValue().let { tv ->
                theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
                this@screen.getDrawable(tv.resourceId)
            }
            contentDescription = "Back"
        }
        bar.addView(back)
        bar.addView(label(title, 20f, bold = true).apply { setPadding(dp(8), 0, 0, 0) })
        root.addView(bar, lp())
    }
    val content = vbox().apply { setPadding(dp(20), dp(8), dp(20), dp(20)) }
    content.body()
    if (scroll) {
        val sv = ScrollView(this).apply { isFillViewport = true; addView(content, FrameLayout.LayoutParams(-1, -2)) }
        root.addView(sv, lp(h = 0, weight = 1f))
    } else root.addView(content, lp(h = 0, weight = 1f))
    setContentView(root)
    return content
}

/** Home screen lo pedda card */
fun Context.card(iconRes: Int, title: String, sub: String, big: Boolean, onClick: () -> Unit): View {
    val box = if (big) vbox(20) else hbox().apply { setPadding(dp(18), dp(16), dp(18), dp(16)) }
    box.background = round(C.SURFACE, dp(22).toFloat())
    box.isClickable = true
    box.foreground = android.util.TypedValue().let { tv ->
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        getDrawable(tv.resourceId)
    }
    box.setOnClickListener { onClick() }
    val circle = FrameLayout(this).apply {
        background = round(if (big) Color.WHITE else C.LINE, dp(28).toFloat())
        addView(icon(iconRes, if (big) 30 else 22, if (big) Color.BLACK else Color.WHITE).apply {
            layoutParams = FrameLayout.LayoutParams(dp(if (big) 30 else 22), dp(if (big) 30 else 22), Gravity.CENTER)
        })
    }
    val cs = dp(if (big) 56 else 44)
    box.addView(circle, LinearLayout.LayoutParams(cs, cs))
    val texts = vbox()
    texts.addView(label(title, if (big) 22f else 17f, bold = true))
    texts.addView(label(sub, 13f, C.MUTED).apply { setPadding(0, dp(2), 0, 0) })
    if (big) texts.setPadding(0, dp(18), 0, 0) else texts.setPadding(dp(14), 0, 0, 0)
    box.addView(texts, if (big) lp() else lp(0, -2, 1f))
    return box
}

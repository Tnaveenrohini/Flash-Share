package com.flikk.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class HistoryActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        var list = History.all()
        screen(getString(R.string.history), scroll = false) {
            if (list.isEmpty()) {
                addView(label(getString(R.string.history_empty), 15f, C.MUTED), lp().apply { topMargin = dp(24) })
                return@screen
            }
            val rv = RecyclerView(this@HistoryActivity)
            rv.layoutManager = LinearLayoutManager(this@HistoryActivity)
            rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = list.size
                override fun onCreateViewHolder(p: ViewGroup, vt: Int): RecyclerView.ViewHolder {
                    val row = hbox().apply { setPadding(0, dp(12), 0, dp(12)) }
                    row.addView(icon(R.drawable.ic_send, 24).apply { id = 1 })
                    val t = vbox().apply { setPadding(dp(16), 0, 0, 0) }
                    t.addView(label("", 15f).apply { id = 2; maxLines = 1 })
                    t.addView(label("", 12f, C.MUTED).apply { id = 3; maxLines = 2 })
                    row.addView(t, lp(0, -2, 1f))
                    row.layoutParams = RecyclerView.LayoutParams(-1, -2)
                    return object : RecyclerView.ViewHolder(row) {}
                }
                override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                    val e = list[pos]
                    val v = h.itemView
                    v.findViewById<ImageView>(1).setImageResource(if (e.dir == "sent") R.drawable.ic_send else if (e.dir == "pc_in") R.drawable.ic_computer else R.drawable.ic_receive)
                    val what = when (e.dir) { "sent" -> getString(R.string.sent); "pc_in" -> getString(R.string.from_pc); else -> getString(R.string.received) }
                    v.findViewById<TextView>(2).text = "$what · ${e.peer}" + if (!e.ok) " · " + getString(R.string.interrupted) else ""
                    v.findViewById<TextView>(2).setTextColor(if (e.ok) C.TEXT else C.BAD)
                    val names = e.files.take(3).joinToString(", ") { it.first } + if (e.files.size > 3) " +${e.files.size - 3}" else ""
                    val bytes = if (e.bytes > 0) " · " + fmtSize(e.bytes) else ""
                    v.findViewById<TextView>(3).text = DateUtils.getRelativeTimeSpanString(e.time).toString() + bytes + "\n" + names
                    v.setOnClickListener { showFiles(e) }
                }
            }
            addView(rv, lp(h = 0, weight = 1f))
            addView(button(getString(R.string.clear_history), primary = false) {
                History.clear(); list = emptyList(); rv.adapter?.notifyDataSetChanged()
            }, lp().apply { topMargin = dp(10) })
        }
    }

    private fun showFiles(e: HistoryEntry) {
        val openable = e.files.filter { it.second.isNotEmpty() }
        if (openable.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setItems(openable.map { it.first }.toTypedArray()) { _, i -> open(openable[i]) }
            .show()
    }

    private fun open(f: Pair<String, String>) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(f.second), mimeOf(f.first))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(i) } catch (_: Exception) { Toast.makeText(this, f.first, Toast.LENGTH_SHORT).show() }
    }
}

package com.flikk.share

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** QR scanner portrait lo */
class ScanActivity : CaptureActivity()

class SendActivity : BaseActivity() {
    private val items = mutableListOf<SendItem>()
    private lateinit var summary: TextView
    private lateinit var sendBtn: MaterialButton
    private lateinit var pcBtn: MaterialButton
    private lateinit var adapter: ItemAdapter
    private lateinit var nearbyBox: LinearLayout
    private lateinit var nearbyRow: LinearLayout
    private var preset: NearbyDevice? = null

    companion object { const val EXTRA_DEVICE = "device" }

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        addUris(uris, persist = true)
    }
    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(500)) { uris ->
        addUris(uris, persist = false)
    }
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) addFolder(uri)
    }
    private val scan = registerForActivityResult(ScanContract()) { r ->
        val t = Target.parse(r.contents)
        if (r.contents == null) return@registerForActivityResult
        if (t == null) { Toast.makeText(this, R.string.bad_qr, Toast.LENGTH_LONG).show(); return@registerForActivityResult }
        startSend(t)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askNotifications()
        preset = Nearby.find(intent.getStringExtra(EXTRA_DEVICE))
        screen(preset?.let { getString(R.string.send_to, it.name) } ?: getString(R.string.send), scroll = false) {
            setPadding(dp(20), 0, dp(20), dp(16))
            val chips = LinearLayout(this@SendActivity).apply { orientation = LinearLayout.HORIZONTAL }
            fun chip(ic: Int, s: Int, f: () -> Unit) {
                val c = vbox().apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(4), dp(14), dp(4), dp(12))
                    background = round(C.SURFACE, dp(18).toFloat())
                    isClickable = true
                    setOnClickListener { f() }
                }
                c.addView(icon(ic, 26))
                c.addView(label(getString(s), 12f).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0); maxLines = 2 })
                chips.addView(c, lp(0, -2, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
            }
            chip(R.drawable.ic_file, R.string.pick_files) { pickFiles.launch(arrayOf("*/*")) }
            chip(R.drawable.ic_photo, R.string.pick_media) {
                pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            }
            chip(R.drawable.ic_folder, R.string.pick_folder) { pickFolder.launch(null) }
            chip(R.drawable.ic_apps, R.string.pick_apps) { pickApps() }
            addView(chips, lp().apply { setMargins(-dp(4), dp(4), -dp(4), 0) })

            val sumRow = hbox().apply { setPadding(0, dp(18), 0, dp(6)) }
            summary = label("", 15f, bold = true)
            sumRow.addView(summary, lp(0, -2, 1f))
            sumRow.addView(label(getString(R.string.clear), 14f, C.MUTED).apply {
                setPadding(dp(12), dp(6), 0, dp(6))
                setOnClickListener { items.clear(); refresh() }
            })
            addView(sumRow, lp())

            adapter = ItemAdapter()
            val rv = RecyclerView(this@SendActivity).apply {
                layoutManager = LinearLayoutManager(this@SendActivity)
                this.adapter = this@SendActivity.adapter
            }
            addView(rv, lp(h = 0, weight = 1f))

            nearbyBox = vbox().apply { visibility = View.GONE }
            nearbyBox.addView(label(getString(R.string.nearby_label), 13f, C.MUTED))
            nearbyRow = hbox()
            nearbyBox.addView(android.widget.HorizontalScrollView(this@SendActivity).apply {
                isHorizontalScrollBarEnabled = false
                addView(nearbyRow)
            }, lp().apply { topMargin = dp(8) })
            addView(nearbyBox, lp().apply { topMargin = dp(10) })

            sendBtn = button(preset?.let { getString(R.string.send_to, it.name) } ?: getString(R.string.scan_send)) {
                val p = preset
                if (p != null) withWifiReady { startSend(p.toTarget()) } else scanQr()
            }
            sendBtn.setIconResource(if (preset != null) R.drawable.ic_send else R.drawable.ic_qr)
            sendBtn.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            addView(sendBtn, lp().apply { topMargin = dp(10) })
            pcBtn = button(getString(R.string.send_pc_instead), primary = false) {
                Hub.webItems = items.toList()
                startActivity(Intent(this@SendActivity, WebShareActivity::class.java))
            }
            addView(pcBtn, lp().apply { topMargin = dp(10) })
        }
        handleShareIntent(intent)
        refresh()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Nearby.devices.collect { showNearby(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (preset == null && hasNearbyPerm() && radiosReady()) Nearby.start(this)
    }

    override fun onPause() {
        Nearby.stop(this)
        super.onPause()
    }

    private fun showNearby(list: List<NearbyDevice>) {
        nearbyBox.visibility = if (preset == null && list.isNotEmpty() && items.isNotEmpty()) View.VISIBLE else View.GONE
        nearbyRow.removeAllViews()
        list.forEach { d ->
            val chip = hbox().apply {
                background = round(C.SURFACE, dp(24).toFloat(), C.BORDER, dp(1))
                setPadding(dp(6), dp(6), dp(16), dp(6))
                isClickable = true
                contentDescription = getString(R.string.send_to, d.name)
                setOnClickListener { if (items.isNotEmpty()) withWifiReady { startSend(d.toTarget()) } }
            }
            chip.addView(label(d.initial, 15f, bold = true).apply {
                gravity = Gravity.CENTER
                background = round(C.ACCENT, dp(18).toFloat())
                setTextColor(C.ON_ACCENT)
            }, lp(dp(36), dp(36)))
            chip.addView(label(d.name, 14f).apply { setPadding(dp(10), 0, 0, 0); maxLines = 1 })
            nearbyRow.addView(chip, lp(-2, -2).apply { rightMargin = dp(8) })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    @Suppress("DEPRECATION")
    private fun handleShareIntent(i: Intent?) {
        i ?: return
        val uris = mutableListOf<Uri>()
        when (i.action) {
            Intent.ACTION_SEND -> (if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else i.getParcelableExtra(Intent.EXTRA_STREAM))?.let { uris += it }
            Intent.ACTION_SEND_MULTIPLE -> (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM))?.let { uris += it }
        }
        if (uris.isNotEmpty()) addUris(uris, persist = false)
    }

    private fun addUris(uris: List<Uri>, persist: Boolean) {
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) {
                uris.mapNotNull { u ->
                    if (persist) try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
                    var name = u.lastPathSegment?.substringAfterLast('/') ?: "file"
                    var size = -1L
                    try {
                        contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                            if (c.moveToFirst()) {
                                c.getString(0)?.let { name = it }
                                if (!c.isNull(1)) size = c.getLong(1)
                            }
                        }
                    } catch (_: Exception) { }
                    if (size < 0) size = try { contentResolver.openFileDescriptor(u, "r")?.use { it.statSize } ?: -1 } catch (_: Exception) { -1 }
                    if (size < 0) null
                    else {
                        val mime = contentResolver.getType(u) ?: mimeOf(name)
                        SendItem(name, "", size, uri = u, mime = mime, modified = size xor name.hashCode().toLong())
                    }
                }
            }
            items += added.filter { a -> items.none { it.uri == a.uri } }
            refresh()
        }
    }

    private fun addFolder(tree: Uri) {
        try { contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
        Toast.makeText(this, R.string.reading_folder, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                val out = mutableListOf<SendItem>()
                val root = DocumentFile.fromTreeUri(this@SendActivity, tree)
                fun walk(d: DocumentFile, dir: String) {
                    for (f in d.listFiles()) {
                        val n = f.name ?: continue
                        if (f.isDirectory) walk(f, "$dir/$n")
                        else if (f.isFile) out += SendItem(n, dir, f.length(), uri = f.uri,
                            mime = f.type ?: mimeOf(n), modified = f.lastModified())
                    }
                }
                if (root != null) walk(root, root.name ?: "Folder")
                out
            }
            items += found
            refresh()
        }
    }

    private class AppRow(val label: String, val info: ApplicationInfo, val size: Long, val split: Boolean, var icon: Drawable? = null, var checked: Boolean = false)

    private fun pickApps() {
        val loading = Toast.makeText(this, R.string.loading_apps, Toast.LENGTH_SHORT)
        loading.show()
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val pm = packageManager
                val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                pm.queryIntentActivities(launch, PackageManager.MATCH_ALL)
                    .map { it.activityInfo.applicationInfo }
                    .distinctBy { it.packageName }
                    .filter { it.packageName != packageName && ((it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) }
                    .map { AppRow(pm.getApplicationLabel(it).toString(), it, File(it.sourceDir).length(), !it.splitSourceDirs.isNullOrEmpty()) }
                    .sortedBy { it.label.lowercase() }
            }
            showAppsDialog(apps)
        }
    }

    private fun showAppsDialog(apps: List<AppRow>) {
        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@SendActivity)
            adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = apps.size
                override fun onCreateViewHolder(p: ViewGroup, vt: Int): RecyclerView.ViewHolder {
                    val row = hbox().apply { setPadding(dp(20), dp(10), dp(16), dp(10)) }
                    row.addView(ImageView(this@SendActivity).apply { id = 1 }, LinearLayout.LayoutParams(dp(40), dp(40)))
                    val t = vbox().apply { setPadding(dp(14), 0, 0, 0) }
                    t.addView(label("", 15f).apply { id = 2; maxLines = 1 })
                    t.addView(label("", 12f, C.MUTED).apply { id = 3 })
                    row.addView(t, lp(0, -2, 1f))
                    row.addView(CheckBox(this@SendActivity).apply { id = 4; isClickable = false })
                    row.layoutParams = RecyclerView.LayoutParams(-1, -2)
                    return object : RecyclerView.ViewHolder(row) {}
                }
                override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                    val a = apps[pos]
                    val v = h.itemView
                    if (a.icon == null) a.icon = try { packageManager.getApplicationIcon(a.info) } catch (_: Exception) { null }
                    v.findViewById<ImageView>(1).setImageDrawable(a.icon)
                    v.findViewById<TextView>(2).text = a.label
                    v.findViewById<TextView>(3).text = fmtSize(a.size) + if (a.split) " · " + getString(R.string.split_apk) else ""
                    v.findViewById<CheckBox>(4).isChecked = a.checked
                    v.setOnClickListener { a.checked = !a.checked; notifyItemChanged(pos) }
                }
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pick_apps)
            .setView(rv)
            .setPositiveButton(R.string.add) { _, _ ->
                apps.filter { it.checked }.forEach { a ->
                    val n = Storage.clean(a.label) + ".apk"
                    if (items.none { it.path == a.info.sourceDir })
                        items += SendItem(n, "", a.size, path = a.info.sourceDir, modified = File(a.info.sourceDir).lastModified())
                }
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refresh() {
        summary.text = if (items.isEmpty()) getString(R.string.nothing_selected)
        else getString(R.string.selected_summary, items.size, fmtSize(items.sumOf { it.size }))
        sendBtn.isEnabled = items.isNotEmpty()
        sendBtn.alpha = if (items.isEmpty()) 0.4f else 1f
        pcBtn.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        if (::nearbyBox.isInitialized) showNearby(Nearby.devices.value)
        adapter.notifyDataSetChanged()
    }

    private fun scanQr() {
        withWifiReady {
            scan.launch(ScanOptions().apply {
                setPrompt(getString(R.string.scan_prompt))
                setBeepEnabled(false)
                setOrientationLocked(true)
                setCaptureActivity(ScanActivity::class.java)
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            })
        }
    }

    private fun startSend(t: Target) {
        Hub.pending = items.toList()
        Hub.lastTarget = t
        Hub.state.value = TState(role = "send", phase = Phase.CONNECTING, peer = t.name)
        TransferService.go(this, TransferService.ACT_SEND)
        startActivity(Intent(this, TransferActivity::class.java))
    }

    private inner class ItemAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(p: ViewGroup, vt: Int): RecyclerView.ViewHolder {
            val row = hbox().apply { setPadding(0, dp(10), 0, dp(10)) }
            row.addView(icon(R.drawable.ic_file, 22, C.MUTED).apply { id = 1 })
            val t = vbox().apply { setPadding(dp(14), 0, dp(8), 0) }
            t.addView(label("", 15f).apply { id = 2; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
            t.addView(label("", 12f, C.MUTED).apply { id = 3; maxLines = 1 })
            row.addView(t, lp(0, -2, 1f))
            row.addView(icon(R.drawable.ic_close, 36, C.MUTED).apply { id = 4; setPadding(dp(8), dp(8), dp(8), dp(8)) })
            row.layoutParams = RecyclerView.LayoutParams(-1, -2)
            return object : RecyclerView.ViewHolder(row) {}
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            val it = items[pos]
            val v = h.itemView
            val ic = when {
                it.path != null -> R.drawable.ic_apps
                it.mime.startsWith("image/") || it.mime.startsWith("video/") -> R.drawable.ic_photo
                it.relDir.isNotEmpty() -> R.drawable.ic_folder
                else -> R.drawable.ic_file
            }
            v.findViewById<ImageView>(1).setImageResource(ic)
            v.findViewById<TextView>(2).text = it.name
            v.findViewById<TextView>(3).text = if (it.relDir.isEmpty()) fmtSize(it.size) else "${it.relDir} · ${fmtSize(it.size)}"
            v.findViewById<View>(4).setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p >= 0) { items.removeAt(p); refresh() }
            }
        }
    }
}

package com.example.myapp

import android.app.Activity
import android.app.AlertDialog
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONArray
import java.util.concurrent.Executors

// ───────────────────────── constants & helpers ─────────────────────────

const val MP = ViewGroup.LayoutParams.MATCH_PARENT
const val WC = ViewGroup.LayoutParams.WRAP_CONTENT
const val MAX_SIDE = 4096
const val PREFS = "gallery_state"
val SUPPORTED: Set<String> = setOf(".jpg", ".jpeg", ".png", ".gif", ".bmp", ".tiff", ".tga", ".webp")

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun View.dp(v: Int): Int = context.dp(v)

interface Host {
    fun pickFolder()
    fun pickNamesFile(create: Boolean)
    fun requestFloat()
    fun requestFull()
    fun requestQuit()
}

object Bridge {
    @Volatile
    var overlay: GalleryView? = null
}

fun splitNatural(s: String): List<String> {
    val out = ArrayList<String>()
    val cur = StringBuilder()
    var digitMode = false
    for (ch in s) {
        val d = ch in '0'..'9'
        if (d != digitMode) {
            out.add(cur.toString())
            cur.setLength(0)
            digitMode = d
        }
        cur.append(ch)
    }
    out.add(cur.toString())
    return out
}

fun cmpDigits(x: String, y: String): Int {
    val a = x.trimStart('0')
    val b = y.trimStart('0')
    if (a.length != b.length) return a.length.compareTo(b.length)
    return a.compareTo(b)
}

fun naturalCompare(a: String, b: String): Int {
    val pa = splitNatural(a)
    val pb = splitNatural(b)
    val n = minOf(pa.size, pb.size)
    for (i in 0 until n) {
        val c = if (i % 2 == 1) cmpDigits(pa[i], pb[i]) else pa[i].lowercase().compareTo(pb[i].lowercase())
        if (c != 0) return c
    }
    return pa.size.compareTo(pb.size)
}

fun buildPickIntent(kind: Int): Intent {
    val persist = Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
    return when (kind) {
        0 -> Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(persist)
        1 -> Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TITLE, "names.txt")
            .addFlags(persist or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        else -> Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .addFlags(persist or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
}

fun persistUri(ctx: Context, uri: Uri, write: Boolean) {
    try {
        var f = Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (write) f = f or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        ctx.contentResolver.takePersistableUriPermission(uri, f)
    } catch (e: Exception) {
    }
}

fun tokensOf(s: String): List<String> {
    val out = ArrayList<String>()
    for (t in s.split(Regex("\\s+"))) {
        if (t.isNotEmpty()) out.add(t)
    }
    return out
}

// ───────────────────────── scroll bar (draggable) ─────────────────────────

class ScrollBarView(ctx: Context, private val horizontal: Boolean) : View(ctx) {
    private var frac = 0f
    private var len = 1f
    var onDrag: ((Float) -> Unit)? = null
    private var grab = 0f
    private val trackPaint = Paint()
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        trackPaint.color = Color.parseColor("#262626")
        thumbPaint.color = Color.parseColor("#9A9A9A")
    }

    private fun drawLen(): Float {
        val l = if (len < 0.08f) 0.08f else len
        return if (l > 1f) 1f else l
    }

    fun setThumb(f: Float, l: Float) {
        frac = f.coerceIn(0f, 1f)
        len = l
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawRect(0f, 0f, w, h, trackPaint)
        val dl = drawLen()
        val s = frac * (1f - dl)
        val pad = 2f * resources.displayMetrics.density
        if (horizontal) {
            rect.set(s * w, pad, (s + dl) * w, h - pad)
        } else {
            rect.set(pad, s * h, w - pad, (s + dl) * h)
        }
        c.drawRoundRect(rect, 8f, 8f, thumbPaint)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val total = (if (horizontal) width else height).toFloat()
        if (total <= 0f) return false
        val pos = (if (horizontal) ev.x else ev.y) / total
        val dl = drawLen()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val s = frac * (1f - dl)
                grab = if (pos >= s && pos <= s + dl) pos - s else dl / 2f
                moveTo(pos - grab, dl)
            }
            MotionEvent.ACTION_MOVE -> moveTo(pos - grab, dl)
        }
        return true
    }

    private fun moveTo(s: Float, dl: Float) {
        val range = 1f - dl
        val f = if (range <= 0f) 0f else (s / range).coerceIn(0f, 1f)
        frac = f
        invalidate()
        onDrag?.invoke(f)
    }
}

// ───────────────────────── image canvas ─────────────────────────

class CanvasView(ctx: Context) : View(ctx) {
    private var bmp: Bitmap? = null
    var zoom = 1f
    var sx = 0f
    var sy = 0f
    var onViewChanged: (() -> Unit)? = null
    var onSizeChangedCb: (() -> Unit)? = null
    var onWheel: ((Float, Float, Boolean, Boolean, Float, Float) -> Unit)? = null
    var onPinch: ((Float, Float, Float) -> Unit)? = null
    var onGestureEnd: (() -> Unit)? = null
    var onTapDown: (() -> Unit)? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val mtx = Matrix()
    private var lastX = 0f
    private var lastY = 0f
    private var scaling = false
    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaling = true
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            onPinch?.invoke(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            scaling = false
            onGestureEnd?.invoke()
        }
    })

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setDefaultFocusHighlightEnabled(false)
        setBackgroundColor(Color.BLACK)
    }

    fun contentW(): Float {
        val b = bmp ?: return 0f
        return b.width * zoom
    }

    fun contentH(): Float {
        val b = bmp ?: return 0f
        return b.height * zoom
    }

    private fun clampScroll() {
        sx = sx.coerceIn(0f, maxOf(0f, contentW() - width))
        sy = sy.coerceIn(0f, maxOf(0f, contentH() - height))
    }

    fun setContent(b: Bitmap?, z: Float) {
        bmp = b
        zoom = z
        sx = 0f
        sy = 0f
        clampScroll()
        invalidate()
        onViewChanged?.invoke()
    }

    fun setZoomAround(nz: Float, fx: Float, fy: Float) {
        val b = bmp ?: return
        val oldCw = b.width * zoom
        val oldCh = b.height * zoom
        val ox = if (oldCw < width) (width - oldCw) / 2f else -sx
        val oy = if (oldCh < height) (height - oldCh) / 2f else -sy
        val cx = (fx - ox) / zoom
        val cy = (fy - oy) / zoom
        zoom = nz
        val nCw = b.width * zoom
        val nCh = b.height * zoom
        sx = if (nCw <= width) 0f else cx * zoom - fx
        sy = if (nCh <= height) 0f else cy * zoom - fy
        clampScroll()
        invalidate()
        onViewChanged?.invoke()
    }

    fun scrollByPx(dx: Float, dy: Float) {
        sx += dx
        sy += dy
        clampScroll()
        invalidate()
        onViewChanged?.invoke()
    }

    fun scrollToFractionX(f: Float) {
        val range = contentW() - width
        if (range > 0f) {
            sx = f.coerceIn(0f, 1f) * range
            invalidate()
            onViewChanged?.invoke()
        }
    }

    fun scrollToFractionY(f: Float) {
        val range = contentH() - height
        if (range > 0f) {
            sy = f.coerceIn(0f, 1f) * range
            invalidate()
            onViewChanged?.invoke()
        }
    }

    fun setScrollFractions(fx: Float, fy: Float) {
        sx = fx * contentW()
        sy = fy * contentH()
        clampScroll()
        invalidate()
        onViewChanged?.invoke()
    }

    fun scrollFractions(): Pair<Float, Float> {
        val cw = contentW()
        val ch = contentH()
        return Pair(if (cw > 0f) sx / cw else 0f, if (ch > 0f) sy / ch else 0f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampScroll()
        onSizeChangedCb?.invoke()
        onViewChanged?.invoke()
    }

    override fun onDraw(c: Canvas) {
        val b = bmp ?: return
        val cw = b.width * zoom
        val ch = b.height * zoom
        val ox = if (cw < width) (width - cw) / 2f else -sx
        val oy = if (ch < height) (height - ch) / 2f else -sy
        mtx.reset()
        mtx.postScale(zoom, zoom)
        mtx.postTranslate(ox, oy)
        c.drawBitmap(b, mtx, paint)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                onTapDown?.invoke()
                lastX = ev.x
                lastY = ev.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remain = if (ev.actionIndex == 0) 1 else 0
                if (remain < ev.pointerCount) {
                    lastX = ev.getX(remain)
                    lastY = ev.getY(remain)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaling && ev.pointerCount == 1) {
                    val dx = lastX - ev.x
                    val dy = lastY - ev.y
                    lastX = ev.x
                    lastY = ev.y
                    scrollByPx(dx, dy)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                onGestureEnd?.invoke()
            }
        }
        return true
    }

    override fun onGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_SCROLL) {
            val v = ev.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val h = ev.getAxisValue(MotionEvent.AXIS_HSCROLL)
            val shift = (ev.metaState and KeyEvent.META_SHIFT_ON) != 0
            val alt = (ev.metaState and KeyEvent.META_ALT_ON) != 0
            onWheel?.invoke(v, h, shift, alt, ev.x, ev.y)
            return true
        }
        return super.onGenericMotionEvent(ev)
    }
}

// ───────────────────────── floating sub-window (names viewer) ─────────────────────────

class FloatPanel(ctx: Context, title: String) : LinearLayout(ctx) {
    private val titleTv = TextView(ctx)
    val bodyHolder = FrameLayout(ctx)
    val footerHolder = FrameLayout(ctx)
    var onClose: (() -> Unit)? = null
    var onGeometry: (() -> Unit)? = null
    private var dx0 = 0f
    private var dy0 = 0f
    private var l0 = 0
    private var t0 = 0
    private var w0 = 0
    private var h0 = 0

    init {
        orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#1E1E1E"))
        bg.setStroke(dp(2), Color.parseColor("#7E57C2"))
        background = bg

        val bar = LinearLayout(ctx)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setBackgroundColor(Color.parseColor("#7E57C2"))
        bar.gravity = Gravity.CENTER_VERTICAL

        titleTv.text = title
        titleTv.setTextColor(Color.WHITE)
        titleTv.typeface = Typeface.DEFAULT_BOLD
        titleTv.textSize = 13f
        titleTv.setPadding(dp(10), 0, dp(6), 0)
        titleTv.maxLines = 1
        titleTv.ellipsize = TextUtils.TruncateAt.END
        bar.addView(titleTv, LinearLayout.LayoutParams(0, MP, 1f))

        val close = TextView(ctx)
        close.text = "✕"
        close.setTextColor(Color.WHITE)
        close.textSize = 16f
        close.gravity = Gravity.CENTER
        close.setOnClickListener { onClose?.invoke() }
        bar.addView(close, LinearLayout.LayoutParams(dp(40), MP))

        addView(bar, LinearLayout.LayoutParams(MP, dp(38)))
        addView(bodyHolder, LinearLayout.LayoutParams(MP, 0, 1f))

        val foot = LinearLayout(ctx)
        foot.orientation = LinearLayout.HORIZONTAL
        foot.gravity = Gravity.CENTER_VERTICAL
        foot.addView(footerHolder, LinearLayout.LayoutParams(0, WC, 1f))
        val grip = TextView(ctx)
        grip.text = "◢"
        grip.setTextColor(Color.parseColor("#B39DDB"))
        grip.textSize = 22f
        grip.gravity = Gravity.CENTER
        foot.addView(grip, LinearLayout.LayoutParams(dp(36), dp(36)))
        addView(foot, LinearLayout.LayoutParams(MP, WC))

        bar.setOnTouchListener { _, ev -> dragTouch(ev) }
        grip.setOnTouchListener { _, ev -> resizeTouch(ev) }
    }

    fun setPanelTitle(t: String) {
        titleTv.text = t
    }

    private fun dragTouch(ev: MotionEvent): Boolean {
        val lp = layoutParams as? FrameLayout.LayoutParams ?: return false
        val p = parent as? View ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dx0 = ev.rawX
                dy0 = ev.rawY
                l0 = lp.leftMargin
                t0 = lp.topMargin
            }
            MotionEvent.ACTION_MOVE -> {
                lp.leftMargin = (l0 + (ev.rawX - dx0)).toInt().coerceIn(0, maxOf(0, p.width - width))
                lp.topMargin = (t0 + (ev.rawY - dy0)).toInt().coerceIn(0, maxOf(0, p.height - height))
                layoutParams = lp
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                onGeometry?.invoke()
            }
        }
        return true
    }

    private fun resizeTouch(ev: MotionEvent): Boolean {
        val lp = layoutParams as? FrameLayout.LayoutParams ?: return false
        val p = parent as? View ?: return false
        val minW = dp(200)
        val minH = dp(160)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dx0 = ev.rawX
                dy0 = ev.rawY
                w0 = width
                h0 = height
            }
            MotionEvent.ACTION_MOVE -> {
                val maxW = maxOf(minW, p.width - lp.leftMargin)
                val maxH = maxOf(minH, p.height - lp.topMargin)
                lp.width = (w0 + (ev.rawX - dx0)).toInt().coerceIn(minW, maxW)
                lp.height = (h0 + (ev.rawY - dy0)).toInt().coerceIn(minH, maxH)
                layoutParams = lp
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                onGeometry?.invoke()
            }
        }
        return true
    }
}

// ───────────────────────── the gallery (all app logic) ─────────────────────────

class GalleryView(ctx: Context, private val host: Host, private val floating: Boolean) : FrameLayout(ctx) {

    private class Entry(val uri: Uri, val name: String)

    private val prefs: SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val ui = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private val preloader = Executors.newSingleThreadExecutor()

    private var images: List<Entry> = emptyList()
    private var folderUri: Uri? = null
    private var currentIndex = 0
    private var original: Bitmap? = null
    private var zoomFactor = 1.0
    private var fixedZoom: Double? = null
    private var isZoomFixed = false
    private val cache = LinkedHashMap<Int, Bitmap>()
    private val cacheSize = 5
    private var loadToken = 0
    private var preloadToken = 0
    private var pendingScroll: Pair<Float, Float>? = null

    private var recentFolders: MutableList<String> = ArrayList()
    private val maxRecent = 8
    private var copiedNames: MutableList<String> = ArrayList()
    private var namesFileUri: Uri? = null
    private val savedNamesSet = HashSet<String>()
    private var pickPurpose = 0
    private var lastStamp = 0L

    private var lastSuggestPos = -1
    private var lastSuggestTime = 0L
    private val suggestItems = ArrayList<String>()

    private lateinit var imgView: CanvasView
    private lateinit var hBar: ScrollBarView
    private lateinit var vBar: ScrollBarView
    private lateinit var nameLabel: TextView
    private lateinit var copyStatus: TextView
    private lateinit var statusBar: TextView
    private lateinit var jumpEntry: EditText
    private lateinit var fixBtn: Button
    private lateinit var recentBtn: Button
    private lateinit var suggestList: ListView
    private lateinit var suggestAdapter: ArrayAdapter<String>

    private var namesPanel: FloatPanel? = null
    private var namesList: ListView? = null
    private var namesCount: TextView? = null
    private var namesAdapter: ArrayAdapter<String>? = null

    private val refitRunnable = Runnable { refit() }

    init {
        buildUi()
        restoreState(true)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        imgView.requestFocus()
    }

    fun shutdown() {
        try {
            loader.shutdownNow()
            preloader.shutdownNow()
        } catch (e: Exception) {
        }
        ui.removeCallbacksAndMessages(null)
    }

    // ── UI construction ──────────────────────────────────────────────────

    private fun mkBtn(label: String, bg: String, onClick: () -> Unit): Button {
        val b = Button(context)
        b.text = label
        b.setAllCaps(false)
        b.textSize = 11f
        b.setTextColor(Color.WHITE)
        b.setPadding(dp(4), 0, dp(4), 0)
        b.minHeight = 0
        b.minimumHeight = 0
        b.minWidth = 0
        b.minimumWidth = dp(54)
        val gd = GradientDrawable()
        gd.setColor(Color.parseColor(bg))
        gd.cornerRadius = dp(6).toFloat()
        b.tag = gd
        b.background = RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), gd, null)
        b.stateListAnimator = null
        b.isFocusable = false
        b.isFocusableInTouchMode = false
        b.setOnClickListener { onClick() }
        return b
    }

    private fun setBtnColor(b: Button, color: String) {
        val gd = b.tag as? GradientDrawable
        if (gd != null) gd.setColor(Color.parseColor(color))
        b.invalidate()
    }

    private fun scrollRow(vararg btns: Button): HorizontalScrollView {
        val hs = HorizontalScrollView(context)
        hs.isFillViewport = true
        hs.isHorizontalScrollBarEnabled = false
        hs.isFocusable = false
        hs.isFocusableInTouchMode = false
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        for (b in btns) {
            val lp = LinearLayout.LayoutParams(0, dp(40), 1f)
            lp.setMargins(dp(2), dp(2), dp(2), dp(2))
            row.addView(b, lp)
        }
        hs.addView(row, FrameLayout.LayoutParams(MP, WC))
        return hs
    }

    private fun buildUi() {
        setBackgroundColor(Color.BLACK)
        val col = LinearLayout(context)
        col.orientation = LinearLayout.VERTICAL
        addView(col, FrameLayout.LayoutParams(MP, MP))

        // Toolbar row 1 (file actions)
        val openBtn = mkBtn("📂 Open", "#4A90D9") { host.pickFolder() }
        recentBtn = mkBtn("🕑 Recent ▾", "#6B6B6B") { showRecentMenu() }
        val saveBtn = mkBtn("💾 Save", "#4A90D9") { saveCopiedNames() }
        val viewBtn = mkBtn("🗂 Names", "#E8785A") { openNamesViewer() }
        val fileBtn = mkBtn("📄 File", "#7E57C2") { changeNamesFile() }
        val modeBtn = mkBtn(if (floating) "⤢ App" else "⧉ Float", "#00897B") {
            if (floating) host.requestFull() else host.requestFloat()
        }
        col.addView(scrollRow(openBtn, recentBtn, saveBtn, viewBtn, fileBtn, modeBtn), LinearLayout.LayoutParams(MP, WC))

        // Toolbar row 2 (viewer actions)
        val prevBtn = mkBtn("◀ Prev", "#555555") { prevImage() }
        val nextBtn = mkBtn("Next ▶", "#555555") { nextImage() }
        val outBtn = mkBtn("−", "#555555") { zoomOut() }
        val inBtn = mkBtn("+", "#555555") { zoomIn() }
        val fitBtn = mkBtn("Fit", "#555555") { resetZoom() }
        col.addView(scrollRow(prevBtn, nextBtn, outBtn, inBtn, fitBtn), LinearLayout.LayoutParams(MP, WC))

        // Filename bar (tap to copy name)
        nameLabel = TextView(context)
        nameLabel.setBackgroundColor(Color.BLACK)
        nameLabel.setTextColor(Color.WHITE)
        nameLabel.typeface = Typeface.DEFAULT_BOLD
        nameLabel.textSize = 13f
        nameLabel.setPadding(dp(10), dp(6), dp(10), dp(6))
        nameLabel.maxLines = 1
        nameLabel.ellipsize = TextUtils.TruncateAt.MIDDLE
        nameLabel.setOnClickListener { copyCurrentFilename() }
        col.addView(nameLabel, LinearLayout.LayoutParams(MP, WC))

        // Canvas + scroll bars (bars never cover the picture)
        imgView = CanvasView(context)
        vBar = ScrollBarView(context, false)
        hBar = ScrollBarView(context, true)
        val rowC = LinearLayout(context)
        rowC.orientation = LinearLayout.HORIZONTAL
        rowC.addView(imgView, LinearLayout.LayoutParams(0, MP, 1f))
        rowC.addView(vBar, LinearLayout.LayoutParams(dp(18), MP))
        val rowH = LinearLayout(context)
        rowH.orientation = LinearLayout.HORIZONTAL
        rowH.addView(hBar, LinearLayout.LayoutParams(0, dp(18), 1f))
        rowH.addView(View(context), LinearLayout.LayoutParams(dp(18), dp(18)))
        val canvasBox = LinearLayout(context)
        canvasBox.orientation = LinearLayout.VERTICAL
        canvasBox.addView(rowC, LinearLayout.LayoutParams(MP, 0, 1f))
        canvasBox.addView(rowH, LinearLayout.LayoutParams(MP, dp(18)))
        col.addView(canvasBox, LinearLayout.LayoutParams(MP, 0, 1f))

        imgView.onViewChanged = { updateScrollBars() }
        imgView.onSizeChangedCb = { scheduleRefit() }
        imgView.onWheel = { v, h, shift, alt, x, y -> handleWheel(v, h, shift, alt, x, y) }
        imgView.onPinch = { sf, fx, fy -> zoomTo((zoomFactor * sf).coerceIn(0.1, 10.0), fx, fy, false) }
        imgView.onGestureEnd = { saveState() }
        imgView.onTapDown = { focusCanvas() }
        hBar.onDrag = { f -> imgView.scrollToFractionX(f) }
        vBar.onDrag = { f -> imgView.scrollToFractionY(f) }

        // Suggestions list
        suggestAdapter = object : ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, suggestItems) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                if (v is TextView) {
                    v.setTextColor(Color.WHITE)
                    v.textSize = 13f
                }
                return v
            }
        }
        suggestList = ListView(context)
        suggestList.adapter = suggestAdapter
        suggestList.setBackgroundColor(Color.parseColor("#222222"))
        suggestList.visibility = View.GONE
        suggestList.setOnItemClickListener { _, _, pos, _ ->
            val name = if (pos >= 0 && pos < suggestItems.size) suggestItems[pos] else null
            if (name != null) {
                val now = SystemClock.uptimeMillis()
                if (pos == lastSuggestPos && now - lastSuggestTime < 400L) {
                    lastSuggestTime = 0L
                    selectSuggestion(name)
                } else {
                    lastSuggestPos = pos
                    lastSuggestTime = now
                    addToPending(nameOnly(name), false)
                }
            }
        }
        suggestList.setOnItemLongClickListener { _, _, pos, _ ->
            val name = if (pos >= 0 && pos < suggestItems.size) suggestItems[pos] else null
            if (name != null) selectSuggestion(name)
            true
        }
        col.addView(suggestList, LinearLayout.LayoutParams(MP, dp(150)))

        // Jump bar
        val jump = LinearLayout(context)
        jump.orientation = LinearLayout.HORIZONTAL
        jump.gravity = Gravity.CENTER_VERTICAL
        jump.setPadding(dp(4), dp(2), dp(4), dp(2))
        jump.setBackgroundColor(Color.parseColor("#1A1A1A"))
        val jl = TextView(context)
        jl.text = "Jump:"
        jl.setTextColor(Color.LTGRAY)
        jl.textSize = 12f
        jump.addView(jl, LinearLayout.LayoutParams(WC, WC))
        jumpEntry = EditText(context)
        jumpEntry.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        jumpEntry.setSingleLine(true)
        jumpEntry.hint = "image name…"
        jumpEntry.setTextColor(Color.WHITE)
        jumpEntry.setHintTextColor(Color.GRAY)
        jumpEntry.textSize = 13f
        jumpEntry.imeOptions = EditorInfo.IME_ACTION_GO
        jumpEntry.setOnEditorActionListener { _, _, _ ->
            jumpToImage()
            true
        }
        jumpEntry.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateSuggestions()
            }
        })
        val elp = LinearLayout.LayoutParams(0, WC, 1f)
        elp.setMargins(dp(4), 0, dp(4), 0)
        jump.addView(jumpEntry, elp)
        val goBtn = mkBtn("Go", "#4A90D9") { jumpToImage() }
        jump.addView(goBtn, LinearLayout.LayoutParams(WC, dp(36)))
        fixBtn = mkBtn("Fix Zoom", "#555555") { toggleFixedZoom() }
        val flp = LinearLayout.LayoutParams(WC, dp(36))
        flp.leftMargin = dp(6)
        jump.addView(fixBtn, flp)
        col.addView(jump, LinearLayout.LayoutParams(MP, WC))

        // Copy / save feedback bar and main status bar
        copyStatus = TextView(context)
        copyStatus.setTextColor(Color.parseColor("#66BB6A"))
        copyStatus.textSize = 11f
        copyStatus.maxLines = 2
        copyStatus.setPadding(dp(8), dp(2), dp(8), dp(2))
        copyStatus.setBackgroundColor(Color.parseColor("#111111"))
        col.addView(copyStatus, LinearLayout.LayoutParams(MP, WC))

        statusBar = TextView(context)
        statusBar.text = "No folder selected"
        statusBar.setTextColor(Color.parseColor("#BBBBBB"))
        statusBar.textSize = 11f
        statusBar.maxLines = 2
        statusBar.ellipsize = TextUtils.TruncateAt.END
        statusBar.setPadding(dp(8), dp(2), dp(8), dp(2))
        statusBar.setBackgroundColor(Color.parseColor("#111111"))
        col.addView(statusBar, LinearLayout.LayoutParams(MP, WC))
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun nameOnly(n: String): String = n.substringBeforeLast('.', n)

    private fun folderLabel(uriStr: String): String {
        return try {
            val id = DocumentsContract.getTreeDocumentId(Uri.parse(uriStr))
            id.substringAfter(':')
        } catch (e: Exception) {
            uriStr
        }
    }

    private fun setCopyStatus(ok: Boolean, text: String) {
        copyStatus.setTextColor(Color.parseColor(if (ok) "#66BB6A" else "#EF5350"))
        copyStatus.text = text
    }

    private fun flashLabel(color: String) {
        nameLabel.setBackgroundColor(Color.parseColor(color))
        ui.postDelayed({ nameLabel.setBackgroundColor(Color.BLACK) }, 120)
    }

    private fun showDlg(b: AlertDialog.Builder) {
        val d = b.create()
        if (floating) d.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        d.show()
    }

    private fun infoDialog(title: String, msg: String) {
        showDlg(AlertDialog.Builder(context).setTitle(title).setMessage(msg).setPositiveButton("OK", null))
    }

    private fun confirmDialog(title: String, msg: String, onYes: () -> Unit) {
        showDlg(
            AlertDialog.Builder(context).setTitle(title).setMessage(msg)
                .setPositiveButton("Yes") { _, _ -> onYes() }
                .setNegativeButton("No", null)
        )
    }

    private fun focusCanvas() {
        imgView.requestFocus()
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(windowToken, 0)
        } catch (e: Exception) {
        }
    }

    private fun readTextUri(uri: Uri): String {
        return try {
            val ins = context.contentResolver.openInputStream(uri)
            if (ins == null) "" else ins.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            ""
        }
    }

    private fun writeTextUri(uri: Uri, text: String): Boolean {
        return try {
            val os = context.contentResolver.openOutputStream(uri, "wt") ?: return false
            os.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun displayName(uri: Uri): String {
        try {
            val c = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (c != null) {
                c.use {
                    if (it.moveToFirst()) {
                        val n = it.getString(0)
                        if (n != null) return n
                    }
                }
            }
        } catch (e: Exception) {
        }
        return uri.lastPathSegment ?: "names file"
    }

    private fun readJsonList(s: String?): MutableList<String> {
        val out = ArrayList<String>()
        if (s == null) return out
        try {
            val a = JSONArray(s)
            for (i in 0 until a.length()) out.add(a.getString(i))
        } catch (e: Exception) {
        }
        return out
    }

    // ── state persistence ────────────────────────────────────────────────

    fun saveState() {
        val e = prefs.edit()
        if (images.isNotEmpty()) {
            e.putString("folder", folderUri?.toString())
            e.putInt("current_index", currentIndex)
            e.putFloat("zoom_factor", zoomFactor.toFloat())
            val fr = imgView.scrollFractions()
            e.putFloat("scroll_x", fr.first)
            e.putFloat("scroll_y", fr.second)
        }
        e.putBoolean("is_zoom_fixed", isZoomFixed)
        val fz = fixedZoom
        e.putFloat("fixed_zoom", if (fz != null) fz.toFloat() else -1f)
        e.putString("recent", JSONArray(recentFolders).toString())
        e.putString("copied", JSONArray(copiedNames).toString())
        e.putString("names_file", namesFileUri?.toString())
        lastStamp = System.nanoTime()
        e.putLong("stamp", lastStamp)
        e.apply()
    }

    private fun persistPending() {
        prefs.edit().putString("copied", JSONArray(copiedNames).toString()).apply()
    }

    private fun restoreState(initial: Boolean) {
        lastStamp = prefs.getLong("stamp", 0L)
        recentFolders = readJsonList(prefs.getString("recent", null))
        copiedNames = readJsonList(prefs.getString("copied", null))
        val nf = prefs.getString("names_file", null)
        namesFileUri = if (nf != null) Uri.parse(nf) else null
        savedNamesSet.clear()
        loadExistingSavedNames()
        isZoomFixed = prefs.getBoolean("is_zoom_fixed", false)
        val fz = prefs.getFloat("fixed_zoom", -1f)
        fixedZoom = if (fz > 0f) fz.toDouble() else null
        zoomFactor = prefs.getFloat("zoom_factor", 1f).toDouble()
        updateFixButton()
        val folder = prefs.getString("folder", null)
        if (folder == null) {
            if (initial && !floating) ui.postDelayed({ host.pickFolder() }, 300)
            return
        }
        pendingScroll = Pair(prefs.getFloat("scroll_x", 0f), prefs.getFloat("scroll_y", 0f))
        openFolderUri(Uri.parse(folder), prefs.getInt("current_index", 0), if (initial) 1 else 2)
    }

    fun onHostResume() {
        val st = prefs.getLong("stamp", 0L)
        if (st != lastStamp) restoreState(false)
    }

    // ── folder / image loading ───────────────────────────────────────────

    private fun scanFolder(tree: Uri): List<Entry>? {
        val out = ArrayList<Entry>()
        try {
            val parentId = DocumentsContract.getTreeDocumentId(tree)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            val cur = context.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            ) ?: return null
            cur.use {
                while (it.moveToNext()) {
                    val id = it.getString(0) ?: continue
                    val name = it.getString(1) ?: continue
                    val mime = it.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val dot = name.lastIndexOf('.')
                    if (dot < 0) continue
                    val ext = name.substring(dot).lowercase()
                    if (!SUPPORTED.contains(ext)) continue
                    out.add(Entry(DocumentsContract.buildDocumentUriUsingTree(tree, id), name))
                }
            }
        } catch (e: Exception) {
            return null
        }
        out.sortWith(Comparator { a, b -> naturalCompare(a.name, b.name) })
        return out
    }

    // mode: 0 = user opened, 1 = restore at startup, 2 = restore on resume
    private fun openFolderUri(uri: Uri, startIndex: Int, mode: Int) {
        statusBar.text = "Loading folder…"
        Thread {
            val list = scanFolder(uri)
            ui.post { onFolderScanned(uri, list, startIndex, mode) }
        }.start()
    }

    private fun onFolderScanned(uri: Uri, list: List<Entry>?, startIndex: Int, mode: Int) {
        if (list == null) {
            recentFolders.remove(uri.toString())
            statusBar.text = "Folder not available"
            if (mode == 0) {
                infoDialog("Folder Not Found", "The folder is no longer accessible:\n" + folderLabel(uri.toString()))
            } else if (mode == 1 && !floating) {
                host.pickFolder()
            }
            return
        }
        if (list.isEmpty()) {
            statusBar.text = "No images loaded"
            if (mode == 0) {
                infoDialog("No Images", "No supported image files found in the selected folder.")
            } else if (mode == 1 && !floating) {
                host.pickFolder()
            }
            return
        }
        images = list
        folderUri = uri
        currentIndex = startIndex.coerceIn(0, list.size - 1)
        loadToken++
        preloadToken++
        cache.clear()
        if (mode == 0) {
            currentIndex = 0
            if (!isZoomFixed) zoomFactor = 1.0
            addToRecent(uri.toString())
        }
        loadCurrentImage()
        preload()
        updateStatus()
        saveState()
    }

    private fun addToRecent(s: String) {
        recentFolders.remove(s)
        recentFolders.add(0, s)
        while (recentFolders.size > maxRecent) recentFolders.removeAt(recentFolders.size - 1)
    }

    fun onFolderPicked(uri: Uri?) {
        if (uri == null) return
        openFolderUri(uri, 0, 0)
    }

    private fun showRecentMenu() {
        if (recentFolders.isEmpty()) {
            infoDialog("Recent Folders", "No recent folders yet.\nOpen a folder first.")
            return
        }
        val pm = PopupMenu(context, recentBtn)
        for ((i, f) in recentFolders.withIndex()) {
            var label = folderLabel(f)
            if (label.length > 60) label = "…" + label.takeLast(57)
            pm.menu.add(0, i, i, label)
        }
        pm.menu.add(0, 1000, 1000, "Clear Recent")
        pm.setOnMenuItemClickListener { item ->
            val id = item.itemId
            if (id == 1000) {
                recentFolders.clear()
                saveState()
            } else if (id >= 0 && id < recentFolders.size) {
                openFolderUri(Uri.parse(recentFolders[id]), 0, 0)
            }
            true
        }
        pm.show()
    }

    private fun decodeBitmap(uri: Uri): Bitmap? {
        return try {
            val src = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                val w = info.size.width
                val h = info.size.height
                val m = maxOf(w, h)
                if (m > MAX_SIDE) {
                    val s = MAX_SIDE.toFloat() / m
                    decoder.setTargetSize(maxOf(1, (w * s).toInt()), maxOf(1, (h * s).toInt()))
                }
            }
        } catch (e: Throwable) {
            null
        }
    }

    private fun cachePut(idx: Int, bmp: Bitmap) {
        cache[idx] = bmp
        trimCache()
    }

    private fun circDist(a: Int, b: Int, n: Int): Int {
        val d = Math.abs(a - b)
        return minOf(d, n - d)
    }

    private fun trimCache() {
        val n = images.size
        if (n == 0) {
            cache.clear()
            return
        }
        val keep = HashSet<Int>()
        for (o in -cacheSize..cacheSize) keep.add((((currentIndex + o) % n) + n) % n)
        val iter = cache.keys.iterator()
        while (iter.hasNext()) {
            if (!keep.contains(iter.next())) iter.remove()
        }
        var total = 0L
        for (b in cache.values) total += b.byteCount.toLong()
        val limit = (Runtime.getRuntime().maxMemory() * 0.35).toLong()
        while (total > limit && cache.size > 1) {
            var worst = -1
            var worstD = -1
            for (k in cache.keys) {
                if (k == currentIndex) continue
                val d = circDist(k, currentIndex, n)
                if (d > worstD) {
                    worstD = d
                    worst = k
                }
            }
            if (worst < 0) break
            val removed = cache.remove(worst)
            if (removed != null) total -= removed.byteCount.toLong()
        }
    }

    private fun preload() {
        val n = images.size
        if (n == 0) return
        val token = ++preloadToken
        val snapshot = images
        val todo = ArrayList<Int>()
        for (off in 1..cacheSize) {
            val a = (currentIndex + off) % n
            val b = (((currentIndex - off) % n) + n) % n
            if (a != currentIndex && !cache.containsKey(a) && !todo.contains(a)) todo.add(a)
            if (b != currentIndex && !cache.containsKey(b) && !todo.contains(b)) todo.add(b)
        }
        try {
            preloader.execute {
                for (idx in todo) {
                    if (token != preloadToken) break
                    val bmp = decodeBitmap(snapshot[idx].uri)
                    if (bmp != null) {
                        ui.post {
                            if (token == preloadToken && snapshot === images) cachePut(idx, bmp)
                        }
                    }
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun loadCurrentImage() {
        if (images.isEmpty()) return
        val idx = currentIndex
        val entry = images[idx]
        nameLabel.text = entry.name
        val cached = cache[idx]
        if (cached != null) {
            showBitmap(cached)
            return
        }
        loadToken++
        val token = loadToken
        statusBar.text = "Loading ${entry.name}…"
        try {
            loader.execute {
                val bmp = decodeBitmap(entry.uri)
                ui.post {
                    if (bmp != null) cachePut(idx, bmp)
                    if (token == loadToken) {
                        if (bmp == null) {
                            original = null
                            imgView.setContent(null, 1f)
                            setCopyStatus(false, "Could not load image: ${entry.name}")
                            updateStatus()
                        } else {
                            showBitmap(bmp)
                        }
                    }
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun showBitmap(bmp: Bitmap) {
        original = bmp
        val fz = fixedZoom
        if (isZoomFixed && fz != null) {
            zoomFactor = fz
        } else if (zoomFactor == 1.0 && !isZoomFixed) {
            val cw = imgView.width
            val ch = imgView.height
            if (cw > 1 && ch > 1) {
                val fit = minOf(cw.toDouble() / bmp.width, ch.toDouble() / bmp.height, 1.0)
                if (fit < 1.0) zoomFactor = fit
            }
        }
        imgView.setContent(bmp, zoomFactor.toFloat())
        val ps = pendingScroll
        if (ps != null) {
            pendingScroll = null
            imgView.setScrollFractions(ps.first, ps.second)
        }
        updateScrollBars()
        updateStatus()
    }

    private fun scheduleRefit() {
        ui.removeCallbacks(refitRunnable)
        ui.postDelayed(refitRunnable, 100)
    }

    private fun refit() {
        val o = original ?: return
        if (isZoomFixed) {
            updateScrollBars()
            return
        }
        zoomFactor = 1.0
        showBitmap(o)
    }

    private fun updateScrollBars() {
        val cw = imgView.contentW()
        val ch = imgView.contentH()
        val vw = imgView.width.toFloat()
        val vh = imgView.height.toFloat()
        if (cw <= 0f || ch <= 0f || vw <= 0f || vh <= 0f) {
            hBar.setThumb(0f, 1f)
            vBar.setThumb(0f, 1f)
            return
        }
        val lenX = if (cw > vw) vw / cw else 1f
        val lenY = if (ch > vh) vh / ch else 1f
        val fx = if (cw > vw) imgView.sx / (cw - vw) else 0f
        val fy = if (ch > vh) imgView.sy / (ch - vh) else 0f
        hBar.setThumb(fx, lenX)
        vBar.setThumb(fy, lenY)
    }

    private fun updateStatus() {
        if (images.isEmpty()) {
            statusBar.text = "No images loaded"
            return
        }
        val e = images[currentIndex]
        val fu = folderUri
        val folder = if (fu != null) folderLabel(fu.toString()) else ""
        val fixed = if (isZoomFixed) " [FIXED]" else ""
        statusBar.text = "Image ${currentIndex + 1} of ${images.size}  |  ${e.name}  |  📁 $folder  |  Zoom: ${(zoomFactor * 100).toInt()}%$fixed"
    }

    // ── navigation & zoom ────────────────────────────────────────────────

    private fun step(d: Int) {
        val n = images.size
        if (n == 0) return
        currentIndex = (((currentIndex + d) % n) + n) % n
        loadCurrentImage()
        preload()
        updateStatus()
        saveState()
    }

    private fun nextImage() {
        step(1)
    }

    private fun prevImage() {
        step(-1)
    }

    private fun zoomTo(z: Double, fx: Float, fy: Float, persist: Boolean) {
        if (original == null) return
        zoomFactor = z
        if (isZoomFixed) fixedZoom = z
        val cx = if (fx < 0f) imgView.width / 2f else fx
        val cy = if (fy < 0f) imgView.height / 2f else fy
        imgView.setZoomAround(z.toFloat(), cx, cy)
        updateStatus()
        if (persist) saveState()
    }

    private fun zoomIn(fx: Float = -1f, fy: Float = -1f) {
        zoomTo(minOf(zoomFactor * 1.2, 10.0), fx, fy, true)
    }

    private fun zoomOut(fx: Float = -1f, fy: Float = -1f) {
        zoomTo(maxOf(zoomFactor / 1.2, 0.1), fx, fy, true)
    }

    private fun resetZoom() {
        val o = original ?: return
        zoomFactor = 1.0
        if (isZoomFixed) fixedZoom = zoomFactor
        showBitmap(o)
        saveState()
    }

    private fun handleWheel(v: Float, h: Float, shift: Boolean, alt: Boolean, x: Float, y: Float) {
        if (original == null) return
        val stepPx = dp(70).toFloat()
        if (shift) {
            imgView.scrollByPx(-v * stepPx + h * stepPx, 0f)
        } else if (alt) {
            imgView.scrollByPx(0f, -v * stepPx)
        } else if (v > 0f) {
            zoomIn(x, y)
        } else if (v < 0f) {
            zoomOut(x, y)
        } else if (h != 0f) {
            imgView.scrollByPx(h * stepPx, 0f)
        }
    }

    private fun toggleFixedZoom() {
        if (isZoomFixed) {
            isZoomFixed = false
            fixedZoom = null
        } else {
            isZoomFixed = true
            fixedZoom = zoomFactor
        }
        updateFixButton()
        updateStatus()
        saveState()
    }

    private fun updateFixButton() {
        if (isZoomFixed) {
            fixBtn.text = "Unfix Zoom"
            setBtnColor(fixBtn, "#2E7D32")
        } else {
            fixBtn.text = "Fix Zoom"
            setBtnColor(fixBtn, "#555555")
        }
    }

    // ── jump to image / suggestions ──────────────────────────────────────

    private fun hideSuggestions() {
        suggestList.visibility = View.GONE
    }

    private fun jumpToImage() {
        if (images.isEmpty()) return
        val s = jumpEntry.text.toString().trim().lowercase()
        if (s.isEmpty()) return
        hideSuggestions()
        for ((i, e) in images.withIndex()) {
            if (e.name.lowercase().contains(s)) {
                currentIndex = i
                if (!isZoomFixed) zoomFactor = 1.0
                loadCurrentImage()
                preload()
                updateStatus()
                jumpEntry.setText("")
                saveState()
                focusCanvas()
                return
            }
        }
        infoDialog("Not Found", "No image found containing '$s'")
    }

    private fun updateSuggestions() {
        if (images.isEmpty()) return
        val s = jumpEntry.text.toString().trim().lowercase()
        if (s.isEmpty()) {
            hideSuggestions()
            return
        }
        val matches = ArrayList<String>()
        for (e in images) {
            if (e.name.lowercase().contains(s)) {
                matches.add(e.name)
                if (matches.size >= 10) break
            }
        }
        if (matches.isEmpty()) {
            hideSuggestions()
            return
        }
        suggestItems.clear()
        suggestItems.addAll(matches)
        suggestAdapter.notifyDataSetChanged()
        suggestList.visibility = View.VISIBLE
    }

    private fun selectSuggestion(name: String) {
        for ((i, e) in images.withIndex()) {
            if (e.name == name) {
                currentIndex = i
                if (!isZoomFixed) zoomFactor = 1.0
                loadCurrentImage()
                preload()
                updateStatus()
                jumpEntry.setText("")
                hideSuggestions()
                saveState()
                focusCanvas()
                return
            }
        }
    }

    // ── copy filename (tap) + save batch ('s') ───────────────────────────

    private fun copyCurrentFilename() {
        if (images.isEmpty()) return
        addToPending(nameOnly(images[currentIndex].name), true)
    }

    private fun addToPending(name: String, flash: Boolean) {
        if (copiedNames.contains(name) || savedNamesSet.contains(name)) {
            if (flash) flashLabel("#B71C1C")
            setCopyStatus(false, "'$name' already copied/saved — skipped duplicate")
            infoDialog("Duplicate Name", "'$name' has already been copied or saved.\nIt was not added again.")
            return
        }
        copiedNames.add(name)
        if (flash) flashLabel("#2E7D32")
        setCopyStatus(true, "Copied '$name'  |  ${copiedNames.size} name(s) pending — press 's' to save")
        persistPending()
    }

    private fun askNamesFile(purpose: Int) {
        val items = arrayOf("Create a new .txt file", "Use an existing file")
        showDlg(
            AlertDialog.Builder(context)
                .setTitle("Choose file to save copied names")
                .setItems(items) { _, which ->
                    pickPurpose = purpose
                    host.pickNamesFile(which == 0)
                }
                .setNegativeButton("Cancel") { _, _ ->
                    if (purpose == 2) setCopyStatus(false, "Change cancelled — keeping current names file")
                    else setCopyStatus(false, "Save cancelled — names still pending")
                }
        )
    }

    fun onNamesFilePicked(uri: Uri?) {
        val purpose = pickPurpose
        pickPurpose = 0
        if (uri == null) {
            if (purpose == 2) setCopyStatus(false, "Change cancelled — keeping current names file")
            else setCopyStatus(false, "Save cancelled — names still pending")
            return
        }
        namesFileUri = uri
        if (purpose == 2) savedNamesSet.clear()
        loadExistingSavedNames()
        saveState()
        if (purpose == 2) {
            val note = if (copiedNames.isNotEmpty()) "  (${copiedNames.size} pending name(s) carried over)" else ""
            setCopyStatus(true, "Now saving to ${displayName(uri)}$note")
            val p = namesPanel
            if (p != null) {
                p.setPanelTitle("Saved Names — " + displayName(uri))
                refreshNamesViewer(true)
            }
        } else if (purpose == 1) {
            saveCopiedNames()
        }
    }

    private fun loadExistingSavedNames() {
        val t = namesFileUri ?: return
        val content = readTextUri(t)
        for (tok in tokensOf(content)) savedNamesSet.add(tok)
    }

    private fun saveCopiedNames() {
        if (copiedNames.isEmpty()) {
            setCopyStatus(false, "No copied names to save — click a filename first")
            return
        }
        val target = namesFileUri
        if (target == null) {
            askNamesFile(1)
            return
        }
        val newUnique = ArrayList<String>()
        for (n in copiedNames) {
            if (!savedNamesSet.contains(n) && !newUnique.contains(n)) newUnique.add(n)
        }
        val skipped = copiedNames.size - newUnique.size
        if (newUnique.isEmpty()) {
            copiedNames.clear()
            persistPending()
            setCopyStatus(false, "All pending names were already saved — nothing new to write")
            return
        }
        val line = newUnique.joinToString(" ")
        val existing = readTextUri(target)
        val content = if (existing.isNotEmpty()) existing + "\n\n" + line else line
        if (!writeTextUri(target, content)) {
            infoDialog("Error", "Could not save names to the file.")
            return
        }
        savedNamesSet.addAll(newUnique)
        copiedNames.clear()
        persistPending()
        val extra = if (skipped > 0) "  ($skipped duplicate(s) skipped)" else ""
        setCopyStatus(true, "Saved ${newUnique.size} name(s) to ${displayName(target)}$extra")
        if (namesPanel != null) refreshNamesViewer(true)
    }

    private fun changeNamesFile() {
        val t = namesFileUri
        val cur = if (t != null) displayName(t) else "(none yet)"
        confirmDialog(
            "Change Names File",
            "Current names file: $cur\n\nChoose a different (or new) text file to save copied names to?"
        ) { askNamesFile(2) }
    }

    // ── names viewer (floating, movable, resizable, remembers geometry) ──

    private fun readSavedGroups(): MutableList<String> {
        val out = ArrayList<String>()
        val t = namesFileUri ?: return out
        val content = readTextUri(t).replace("\r\n", "\n")
        for (b in content.split("\n\n")) {
            val s = b.trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    private fun writeGroups(target: Uri, groups: List<String>): Boolean {
        val cleaned = ArrayList<String>()
        for (g in groups) {
            val s = g.trim()
            if (s.isNotEmpty()) cleaned.add(s)
        }
        return writeTextUri(target, cleaned.joinToString("\n\n"))
    }

    private fun openNamesViewer() {
        val target = namesFileUri
        if (target == null) {
            infoDialog(
                "No Save File Yet",
                "No names file has been chosen yet.\nCopy a filename and press 's' (or tap Save) first to choose where names are saved."
            )
            return
        }
        val existing = namesPanel
        if (existing != null) {
            refreshNamesViewer(true)
            existing.bringToFront()
            return
        }

        val panel = FloatPanel(context, "Saved Names — " + displayName(target))

        val info = TextView(context)
        info.text = "Each row is one saved group. Tap rows to select several, then Delete. Long-press a row to edit its names."
        info.textSize = 11f
        info.setTextColor(Color.parseColor("#AAAAAA"))
        info.setPadding(dp(8), dp(4), dp(8), dp(4))

        val adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_list_item_multiple_choice, ArrayList<String>()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                if (v is TextView) {
                    v.setTextColor(Color.WHITE)
                    v.textSize = 14f
                }
                return v
            }
        }
        val lv = ListView(context)
        lv.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        lv.adapter = adapter
        lv.setOnItemLongClickListener { _, _, pos, _ ->
            editGroup(pos)
            true
        }

        val body = LinearLayout(context)
        body.orientation = LinearLayout.VERTICAL
        body.addView(info, LinearLayout.LayoutParams(MP, WC))
        body.addView(lv, LinearLayout.LayoutParams(MP, 0, 1f))
        panel.bodyHolder.addView(body, FrameLayout.LayoutParams(MP, MP))

        val footer = LinearLayout(context)
        footer.orientation = LinearLayout.HORIZONTAL
        footer.gravity = Gravity.CENTER_VERTICAL
        val delBtn = mkBtn("🗑 Delete", "#C62828") { deleteSelectedGroups() }
        val refBtn = mkBtn("⟳ Refresh", "#555555") { refreshNamesViewer(true) }
        val countTv = TextView(context)
        countTv.setTextColor(Color.GRAY)
        countTv.textSize = 11f
        val dlp = LinearLayout.LayoutParams(WC, dp(34))
        dlp.setMargins(dp(4), dp(2), dp(2), dp(2))
        footer.addView(delBtn, dlp)
        val rlp = LinearLayout.LayoutParams(WC, dp(34))
        rlp.setMargins(dp(2), dp(2), dp(2), dp(2))
        footer.addView(refBtn, rlp)
        val clp = LinearLayout.LayoutParams(WC, WC)
        clp.leftMargin = dp(6)
        footer.addView(countTv, clp)
        panel.footerHolder.addView(footer, FrameLayout.LayoutParams(MP, WC))

        var gw = minOf(dp(360), maxOf(dp(220), width - dp(16)))
        var gh = minOf(dp(480), maxOf(dp(220), height - dp(100)))
        var gx = dp(8)
        var gy = dp(70)
        val saved = prefs.getString("np_geom", null)
        if (saved != null) {
            try {
                val p = saved.split(",")
                gx = p[0].toInt()
                gy = p[1].toInt()
                gw = p[2].toInt()
                gh = p[3].toInt()
            } catch (e: Exception) {
            }
        }
        gw = minOf(gw, maxOf(dp(200), width))
        gh = minOf(gh, maxOf(dp(160), height))
        gx = gx.coerceIn(0, maxOf(0, width - gw))
        gy = gy.coerceIn(0, maxOf(0, height - gh))
        val lp = FrameLayout.LayoutParams(gw, gh)
        lp.leftMargin = gx
        lp.topMargin = gy

        panel.onClose = { closeNamesViewer() }
        panel.onGeometry = { saveNamesGeometry() }
        addView(panel, lp)
        namesPanel = panel
        namesList = lv
        namesCount = countTv
        namesAdapter = adapter
        refreshNamesViewer(true)
    }

    private fun saveNamesGeometry() {
        val p = namesPanel ?: return
        val lp = p.layoutParams as? FrameLayout.LayoutParams ?: return
        prefs.edit().putString("np_geom", "${lp.leftMargin},${lp.topMargin},${lp.width},${lp.height}").apply()
    }

    private fun closeNamesViewer() {
        saveNamesGeometry()
        val p = namesPanel
        if (p != null) removeView(p)
        namesPanel = null
        namesList = null
        namesCount = null
        namesAdapter = null
    }

    private fun refreshNamesViewer(scrollToBottom: Boolean) {
        val ad = namesAdapter ?: return
        val lv = namesList ?: return
        val groups = readSavedGroups()
        ad.clear()
        ad.addAll(groups)
        lv.clearChoices()
        var total = 0
        for (g in groups) total += tokensOf(g).size
        namesCount?.text = "${groups.size} group(s), $total name(s)"
        if (scrollToBottom && groups.isNotEmpty()) lv.setSelection(groups.size - 1)
    }

    private fun deleteSelectedGroups() {
        val lv = namesList ?: return
        val target = namesFileUri ?: return
        val groups = readSavedGroups()
        val sel = ArrayList<Int>()
        for (i in 0 until lv.count) {
            if (lv.isItemChecked(i)) sel.add(i)
        }
        if (sel.isEmpty()) {
            infoDialog("Nothing Selected", "Select one or more groups to delete first.")
            return
        }
        var total = 0
        for (i in sel) {
            if (i < groups.size) total += tokensOf(groups[i]).size
        }
        confirmDialog(
            "Confirm Delete",
            "Delete ${sel.size} group(s) ($total name(s) total) from the saved file?\nThis cannot be undone."
        ) {
            val g2 = readSavedGroups()
            val remaining = ArrayList<String>()
            val removedTokens = ArrayList<String>()
            for ((i, g) in g2.withIndex()) {
                if (sel.contains(i)) {
                    removedTokens.addAll(tokensOf(g))
                } else {
                    remaining.add(g)
                }
            }
            if (!writeGroups(target, remaining)) {
                infoDialog("Error", "Could not update file.")
            } else {
                savedNamesSet.removeAll(removedTokens.toSet())
                refreshNamesViewer(false)
                setCopyStatus(true, "Deleted ${sel.size} group(s) from ${displayName(target)}")
            }
        }
    }

    private fun editGroup(pos: Int) {
        val groups = readSavedGroups()
        if (pos < 0 || pos >= groups.size) return
        val current = groups[pos]
        val et = EditText(context)
        et.setText(current)
        et.setSelection(current.length)
        showDlg(
            AlertDialog.Builder(context)
                .setTitle("Edit Group")
                .setMessage("Edit names (space separated). Remove a name by deleting it here. Clearing everything removes the group.")
                .setView(et)
                .setPositiveButton("Save") { _, _ -> applyGroupEdit(pos, current, et.text.toString().trim()) }
                .setNegativeButton("Cancel", null)
        )
    }

    private fun applyGroupEdit(pos: Int, oldText: String, newText: String) {
        val target = namesFileUri ?: return
        val groups = readSavedGroups()
        if (pos < groups.size) {
            if (newText.isNotEmpty()) {
                groups[pos] = newText
            } else {
                groups.removeAt(pos)
            }
            if (!writeGroups(target, groups)) {
                infoDialog("Error", "Could not update file.")
                return
            }
        }
        val removed = HashSet<String>(tokensOf(oldText))
        removed.removeAll(tokensOf(newText).toSet())
        savedNamesSet.removeAll(removed)
        refreshNamesViewer(false)
        setCopyStatus(true, "Updated group in ${displayName(target)}")
    }

    // ── keyboard shortcuts (same as the .py) ─────────────────────────────

    fun handleKey(ev: KeyEvent): Boolean {
        if (ev.action != KeyEvent.ACTION_DOWN) return false
        if (findFocus() is EditText) return false
        when (ev.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                nextImage()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                prevImage()
                return true
            }
            KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_NUMPAD_ADD -> {
                zoomIn()
                return true
            }
            KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> {
                zoomOut()
                return true
            }
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> {
                resetZoom()
                return true
            }
            KeyEvent.KEYCODE_S -> {
                saveCopiedNames()
                return true
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                saveState()
                host.requestQuit()
                return true
            }
            KeyEvent.KEYCODE_O -> {
                if (ev.isCtrlPressed) {
                    host.pickFolder()
                    return true
                }
            }
        }
        return false
    }
}

// ───────────────────────── floating window frame (move / resize / focus) ─────────────────────────

class FloatFrame(
    ctx: Context,
    private val wm: WindowManager,
    private val lp: WindowManager.LayoutParams,
    private val gallery: GalleryView,
    onFull: () -> Unit,
    onClose: () -> Unit,
    private val onChanged: () -> Unit
) : FrameLayout(ctx) {

    private val edge = dp(12)
    private val minW = dp(240)
    private val minH = dp(220)
    private var mode = 0
    private var sx0 = 0f
    private var sy0 = 0f
    private var x0 = 0
    private var y0 = 0
    private var w0 = 0
    private var h0 = 0

    init {
        setBackgroundColor(Color.parseColor("#7E57C2"))
        setPadding(dp(10), dp(10), dp(10), dp(10))

        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL

        val bar = LinearLayout(ctx)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(Color.parseColor("#4527A0"))

        val title = TextView(ctx)
        title.text = "⠿ Image Gallery  — drag here to move"
        title.setTextColor(Color.WHITE)
        title.typeface = Typeface.DEFAULT_BOLD
        title.textSize = 12f
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        title.setPadding(dp(8), 0, dp(4), 0)
        title.gravity = Gravity.CENTER_VERTICAL
        bar.addView(title, LinearLayout.LayoutParams(0, MP, 1f))

        val full = TextView(ctx)
        full.text = "⤢"
        full.setTextColor(Color.WHITE)
        full.textSize = 18f
        full.gravity = Gravity.CENTER
        full.setOnClickListener { onFull() }
        bar.addView(full, LinearLayout.LayoutParams(dp(44), MP))

        val close = TextView(ctx)
        close.text = "✕"
        close.setTextColor(Color.WHITE)
        close.textSize = 18f
        close.gravity = Gravity.CENTER
        close.setOnClickListener { onClose() }
        bar.addView(close, LinearLayout.LayoutParams(dp(44), MP))

        title.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx0 = ev.rawX
                    sy0 = ev.rawY
                    x0 = lp.x
                    y0 = lp.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dm = resources.displayMetrics
                    lp.x = (x0 + (ev.rawX - sx0)).toInt().coerceIn(-(lp.width - dp(80)), dm.widthPixels - dp(80))
                    lp.y = (y0 + (ev.rawY - sy0)).toInt().coerceIn(0, maxOf(0, dm.heightPixels - dp(40)))
                    applyLp()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    onChanged()
                }
            }
            true
        }

        col.addView(bar, LinearLayout.LayoutParams(MP, dp(34)))
        col.addView(gallery, LinearLayout.LayoutParams(MP, 0, 1f))
        addView(col, FrameLayout.LayoutParams(MP, MP))
    }

    private fun applyLp() {
        try {
            wm.updateViewLayout(this, lp)
        } catch (e: Exception) {
        }
    }

    fun setFocusableWindow(f: Boolean) {
        val has = (lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0
        if (f == has) return
        lp.flags = if (f) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        applyLp()
        if (!f) {
            try {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(windowToken, 0)
            } catch (e: Exception) {
            }
        }
    }

    private fun hit(x: Float, y: Float): Int {
        val w = width.toFloat()
        val h = height.toFloat()
        var m = 0
        if (x < edge) m = m or 1
        if (y < edge) m = m or 2
        if (x > w - edge) m = m or 4
        if (y > h - edge) m = m or 8
        if (m == 0) {
            val c = edge * 2.5f
            if (y > h - c) {
                if (x > w - c) {
                    m = 4 or 8
                } else if (x < c) {
                    m = 1 or 8
                }
            }
        }
        return m
    }

    private fun doResize(dx: Float, dy: Float) {
        val dm = resources.displayMetrics
        var nx = x0
        var ny = y0
        var nw = w0
        var nh = h0
        if ((mode and 4) != 0) nw = (w0 + dx).toInt()
        if ((mode and 8) != 0) nh = (h0 + dy).toInt()
        if ((mode and 1) != 0) nw = (w0 - dx).toInt()
        if ((mode and 2) != 0) nh = (h0 - dy).toInt()
        nw = nw.coerceIn(minW, maxOf(minW, dm.widthPixels))
        nh = nh.coerceIn(minH, maxOf(minH, dm.heightPixels))
        if ((mode and 1) != 0) nx = x0 + (w0 - nw)
        if ((mode and 2) != 0) ny = y0 + (h0 - nh)
        lp.x = nx
        lp.y = ny
        lp.width = nw
        lp.height = nh
        applyLp()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_OUTSIDE) {
            setFocusableWindow(false)
            return false
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            setFocusableWindow(true)
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            mode = hit(ev.x, ev.y)
            if (mode != 0) {
                sx0 = ev.rawX
                sy0 = ev.rawY
                x0 = lp.x
                y0 = lp.y
                w0 = lp.width
                h0 = lp.height
                return true
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (mode == 0) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> doResize(ev.rawX - sx0, ev.rawY - sy0)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mode = 0
                onChanged()
            }
        }
        return true
    }

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        if (ev.keyCode == KeyEvent.KEYCODE_BACK) {
            if (ev.action == KeyEvent.ACTION_UP) setFocusableWindow(false)
            return true
        }
        if (gallery.handleKey(ev)) return true
        return super.dispatchKeyEvent(ev)
    }
}

// ───────────────────────── main activity ─────────────────────────

class MainActivity : Activity(), Host {
    private lateinit var gallery: GalleryView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gallery = GalleryView(this, this, false)
        setContentView(gallery)
    }

    override fun onResume() {
        super.onResume()
        val ov = Bridge.overlay
        if (ov != null) {
            ov.saveState()
            stopService(Intent(this, OverlayService::class.java))
        }
        gallery.onHostResume()
    }

    override fun onPause() {
        gallery.saveState()
        super.onPause()
    }

    override fun onDestroy() {
        gallery.shutdown()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (gallery.handleKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri? = if (resultCode == RESULT_OK) data?.data else null
        if (requestCode == 101) {
            if (uri != null) persistUri(this, uri, false)
            gallery.onFolderPicked(uri)
        } else if (requestCode == 102) {
            if (uri != null) persistUri(this, uri, true)
            gallery.onNamesFilePicked(uri)
        }
    }

    override fun pickFolder() {
        try {
            startActivityForResult(buildPickIntent(0), 101)
        } catch (e: Exception) {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_LONG).show()
        }
    }

    override fun pickNamesFile(create: Boolean) {
        try {
            startActivityForResult(buildPickIntent(if (create) 1 else 2), 102)
        } catch (e: Exception) {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_LONG).show()
        }
    }

    override fun requestFloat() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Turn on 'Display over other apps' for this app, come back, then tap Float again",
                Toast.LENGTH_LONG
            ).show()
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
            }
            return
        }
        gallery.saveState()
        startService(Intent(this, OverlayService::class.java))
        moveTaskToBack(true)
    }

    override fun requestFull() {
    }

    override fun requestQuit() {
        gallery.saveState()
        finish()
    }
}

// ───────────────────────── picker helper (used by the floating window) ─────────────────────────

class PickerActivity : Activity() {
    private var kind = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kind = intent.getIntExtra("kind", 0)
        if (savedInstanceState == null) {
            try {
                startActivityForResult(buildPickIntent(kind), 77)
            } catch (e: Exception) {
                finish()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri? = if (resultCode == RESULT_OK) data?.data else null
        if (uri != null) persistUri(this, uri, kind != 0)
        val g = Bridge.overlay
        if (g != null) {
            if (kind == 0) g.onFolderPicked(uri) else g.onNamesFilePicked(uri)
        }
        finish()
    }
}

// ───────────────────────── always-on-top (floating) mode ─────────────────────────

class OverlayService : Service(), Host {
    private var wm: WindowManager? = null
    private var frame: FloatFrame? = null
    private var gallery: GalleryView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (frame == null) {
            if (!Settings.canDrawOverlays(this)) {
                stopSelf()
                return START_NOT_STICKY
            }
            createOverlay()
        }
        return START_STICKY
    }

    private fun createOverlay() {
        val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = manager
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dm = resources.displayMetrics
        val w = prefs.getInt("ov_w", (dm.widthPixels * 0.92f).toInt()).coerceIn(dp(240), maxOf(dp(240), dm.widthPixels))
        val h = prefs.getInt("ov_h", (dm.heightPixels * 0.55f).toInt()).coerceIn(dp(220), maxOf(dp(220), dm.heightPixels))
        val x = prefs.getInt("ov_x", dm.widthPixels / 25)
        val y = prefs.getInt("ov_y", dm.heightPixels / 12)

        val lp = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = x
        lp.y = y
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN

        val tctx = ContextThemeWrapper(this, android.R.style.Theme_Material_NoActionBar)
        val g = GalleryView(tctx, this, true)
        gallery = g
        Bridge.overlay = g
        val f = FloatFrame(tctx, manager, lp, g, { requestFull() }, { closeOverlay() }, { saveGeom(lp) })
        frame = f
        try {
            manager.addView(f, lp)
        } catch (e: Exception) {
            closeOverlay()
        }
    }

    private fun saveGeom(lp: WindowManager.LayoutParams) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("ov_x", lp.x)
            .putInt("ov_y", lp.y)
            .putInt("ov_w", lp.width)
            .putInt("ov_h", lp.height)
            .apply()
    }

    private fun cleanup() {
        val g = gallery
        if (g != null) {
            g.saveState()
        }
        val f = frame
        if (f != null) {
            try {
                wm?.removeView(f)
            } catch (e: Exception) {
            }
        }
        frame = null
        if (g != null) g.shutdown()
        gallery = null
        Bridge.overlay = null
    }

    private fun closeOverlay() {
        cleanup()
        stopSelf()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    override fun pickFolder() {
        val i = Intent(this, PickerActivity::class.java)
        i.putExtra("kind", 0)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    override fun pickNamesFile(create: Boolean) {
        val i = Intent(this, PickerActivity::class.java)
        i.putExtra("kind", if (create) 1 else 2)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    override fun requestFloat() {
    }

    override fun requestFull() {
        gallery?.saveState()
        val li = packageManager.getLaunchIntentForPackage(packageName)
        if (li != null) {
            li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(li)
        }
        closeOverlay()
    }

    override fun requestQuit() {
        closeOverlay()
    }
}

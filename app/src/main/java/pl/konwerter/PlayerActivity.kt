package pl.konwerter

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.TextUtils
import android.util.Rational
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File

class PlayerActivity : Ui() {
    private val REQ_SUB = 51
    private val ACT_PIP = "pl.konwerter.PIP"
    private val h = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var video: VLCVideoLayout
    private lateinit var controls: FrameLayout
    private lateinit var audioPane: LinearLayout
    private lateinit var cover: ImageView
    private lateinit var aTitle: TextView
    private lateinit var aSub: TextView
    private lateinit var info: TextView
    private lateinit var titleV: TextView
    private lateinit var timeV: TextView
    private lateinit var lenV: TextView
    private lateinit var seek: SeekBar
    private lateinit var playBtn: ImageView
    private lateinit var lockBtn: ImageView
    private lateinit var abBtn: TextView

    private var locked = false
    private var inPip = false
    private var dragging = false
    private var scaleIdx = 0
    private var rotMode = 0
    private var lastIndex = -2
    private val scales = listOf(
        MediaPlayer.ScaleType.SURFACE_BEST_FIT to "Dopasuj", MediaPlayer.ScaleType.SURFACE_FILL to "Wypełnij",
        MediaPlayer.ScaleType.SURFACE_FIT_SCREEN to "Przytnij do ekranu", MediaPlayer.ScaleType.SURFACE_16_9 to "16:9",
        MediaPlayer.ScaleType.SURFACE_4_3 to "4:3", MediaPlayer.ScaleType.SURFACE_ORIGINAL to "Oryginał"
    )
    private val listener: () -> Unit = { refresh() }
    private val hide = Runnable { if (Engine.playing && !dragging) showControls(false) }

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.getIntExtra("a", 0)) {
                1 -> Engine.prev()
                2 -> Engine.toggle()
                3 -> Engine.next()
            }
        }
    }

    // ciemny wygląd odtwarzacza niezależnie od motywu
    override val fg get() = Color.WHITE
    override val dim get() = 0xB3FFFFFF.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Theme.apply(this, Store.theme, Store.mode)
        Engine.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        build()
        immersive()
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(pipReceiver, IntentFilter(ACT_PIP), Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(pipReceiver, IntentFilter(ACT_PIP))
        Engine.listeners.add(listener)
        Engine.dialogHandler = { d -> showVlcDialog(d, true) }
        handleIntent(intent)
        refresh()
        showControls(true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        Engine.dialogHandler = { d -> showVlcDialog(d, true) }
    }

    override fun onStart() {
        super.onStart()
        Engine.attach(video)
        refresh()
    }

    override fun onStop() {
        super.onStop()
        val video = Engine.hasVideo
        if (inPip || !isChangingConfigurations) {
            if (video && !Store.bgAudio) Engine.pause()
            Engine.detach(Store.bgAudio)
        }
    }

    override fun onDestroy() {
        Engine.listeners.remove(listener)
        if (Engine.dialogHandler != null) Engine.dialogHandler = null
        try {
            unregisterReceiver(pipReceiver)
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && Store.autoPip && Engine.playing && Engine.hasVideo) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        showControls(!inPip)
        if (!inPip) immersive()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    private fun immersive() {
        if (Build.VERSION.SDK_INT < 30) return
        window.insetsController?.let {
            it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ------------------------------------------------------------ otwieranie
    private fun nameOf(uri: Uri): String {
        if (uri.scheme == "content") try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) return c.getString(0) ?: "" }
        } catch (e: Exception) {
        }
        return Uri.decode(uri.lastPathSegment ?: uri.toString()).substringAfterLast('/')
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        @Suppress("DEPRECATION")
        val uri: Uri? = i.data ?: i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            ?: i.getStringExtra(Intent.EXTRA_TEXT)?.let { t -> Regex("(https?|rtsp|rtmp|mms|udp)://\\S+").find(t)?.value?.let { Uri.parse(it) } }
        if (uri == null) return
        val audio = (i.type ?: contentResolver.getType(uri) ?: "").startsWith("audio")
        val raw = when (uri.scheme) {
            "file" -> uri.path ?: uri.toString()
            else -> uri.toString()
        }
        val t = Track(raw, nameOf(uri), "", !audio, if (raw.startsWith("content://")) raw else "")
        val sib = if (raw.startsWith("/")) Library.siblings(raw) else emptyList()
        val idx = sib.indexOfFirst { it.uri == raw }
        if (idx >= 0) Engine.play(sib, idx) else Engine.play(listOf(t), 0)
    }

    // ------------------------------------------------------------ budowa ekranu
    private fun ctrlIcon(res: Int, size: Int = 26, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(Color.WHITE)
        setPadding(dp(9), dp(9), dp(9), dp(9))
        setOnClickListener { onClick(); bump() }
        layoutParams = LinearLayout.LayoutParams(dp(size + 18), dp(size + 18))
    }

    private fun build() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = VLCVideoLayout(this)
        root.addView(video, FrameLayout.LayoutParams(MATCH, MATCH))

        audioPane = vbox().apply {
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Theme.blend(Theme.accent, Color.BLACK, 0.45f), Color.BLACK))
            setPadding(dp(30), dp(30), dp(30), dp(120))
        }
        cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = round(0x33FFFFFF, 24)
            clipToOutline = true
        }
        audioPane.addView(cover, LinearLayout.LayoutParams(dp(260), dp(260)))
        aTitle = tv("", 22f, true).apply { gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        audioPane.addView(aTitle, lp(top = 22))
        aSub = tv("", 15f, false, dim).apply { gravity = Gravity.CENTER }
        audioPane.addView(aSub, lp(top = 4))
        root.addView(audioPane, FrameLayout.LayoutParams(MATCH, MATCH))

        val gestures = View(this)
        root.addView(gestures, FrameLayout.LayoutParams(MATCH, MATCH))
        setupGestures(gestures)

        info = tv("", 20f, true).apply {
            background = round(0xCC000000.toInt(), 16)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(info, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))

        controls = FrameLayout(this)
        val top = hbox().apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), 0x00000000))
            setPadding(dp(8), dp(14), dp(8), dp(28))
        }
        top.addView(ctrlIcon(R.drawable.ic_back_arrow) { finish() })
        titleV = tv("", 16f, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        top.addView(titleV, lp(0, WRAP, 1f).apply { leftMargin = dp(4) })
        top.addView(ctrlIcon(R.drawable.ic_cast) { castSheet() })
        top.addView(ctrlIcon(R.drawable.ic_subs) { tracksSheet() })
        top.addView(ctrlIcon(R.drawable.ic_eq) { eqSheet() })
        top.addView(ctrlIcon(R.drawable.ic_more) { moreSheet() })
        controls.addView(top, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        val center = hbox().apply { gravity = Gravity.CENTER }
        center.addView(ctrlIcon(R.drawable.ic_prev, 30) { Engine.prev() })
        center.addView(ctrlIcon(R.drawable.ic_rew, 30) { Engine.seekBy(-Store.seekStep * 1000L) }, lp(WRAP, WRAP).apply { leftMargin = dp(14) })
        playBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_pause_fill)
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x55000000) }
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setOnClickListener { Engine.toggle(); bump() }
        }
        center.addView(playBtn, LinearLayout.LayoutParams(dp(78), dp(78)).apply { leftMargin = dp(18); rightMargin = dp(18) })
        center.addView(ctrlIcon(R.drawable.ic_fwd, 30) { Engine.seekBy(Store.seekStep * 1000L) }, lp(WRAP, WRAP).apply { rightMargin = dp(14) })
        center.addView(ctrlIcon(R.drawable.ic_next, 30) { Engine.next() })
        controls.addView(center, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))

        val bottom = vbox().apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xDD000000.toInt(), 0x00000000))
            setPadding(dp(12), dp(36), dp(12), dp(14))
        }
        val tr = hbox()
        timeV = tv("0:00", 13f, true)
        lenV = tv("0:00", 13f, true)
        seek = SeekBar(this).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(Theme.accent)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser && Engine.length > 0) timeV.text = Library.time(Engine.length * p / 1000)
                }

                override fun onStartTrackingTouch(s: SeekBar?) {
                    dragging = true; h.removeCallbacks(hide)
                }

                override fun onStopTrackingTouch(s: SeekBar?) {
                    dragging = false
                    if (Engine.length > 0) Engine.seek(Engine.length * (s?.progress ?: 0) / 1000)
                    bump()
                }
            })
        }
        tr.addView(timeV)
        tr.addView(seek, lp(0, WRAP, 1f))
        tr.addView(lenV)
        bottom.addView(tr)
        val br = hbox()
        br.addView(ctrlIcon(R.drawable.ic_lock, 22) { setLocked(true) })
        br.addView(ctrlIcon(R.drawable.ic_aspect, 22) { cycleScale() })
        br.addView(ctrlIcon(R.drawable.ic_speed, 22) { speedSheet() })
        abBtn = tv("A-B", 13f, true).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { abToggle(); bump() }
        }
        br.addView(abBtn)
        br.addView(ctrlIcon(R.drawable.ic_timer, 22) { sleepSheet() })
        br.addView(ctrlIcon(R.drawable.ic_queue, 22) { queueSheet() })
        br.addView(ctrlIcon(R.drawable.ic_pip, 22) { enterPip() })
        br.addView(ctrlIcon(R.drawable.ic_rotate, 22) { cycleRotation() })
        bottom.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(br) })
        controls.addView(bottom, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        root.addView(controls, FrameLayout.LayoutParams(MATCH, MATCH))

        lockBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_unlock)
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x88000000.toInt()) }
            setPadding(dp(14), dp(14), dp(14), dp(14))
            visibility = View.GONE
            setOnClickListener { setLocked(false) }
        }
        root.addView(lockBtn, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = dp(24) })
        setContentView(root)
    }

    private fun bump() {
        h.removeCallbacks(hide)
        h.postDelayed(hide, 3500)
    }

    private fun showControls(show: Boolean) {
        if (locked || inPip) {
            controls.visibility = View.GONE
            return
        }
        controls.visibility = if (show) View.VISIBLE else View.GONE
        if (show) bump()
    }

    private fun setLocked(l: Boolean) {
        locked = l
        lockBtn.visibility = if (l) View.VISIBLE else View.GONE
        controls.visibility = if (l) View.GONE else View.VISIBLE
        if (l) h.postDelayed({ if (locked) lockBtn.alpha = 0.3f }, 2500) else lockBtn.alpha = 1f
    }

    private fun flash(text: String) {
        info.text = text
        info.visibility = View.VISIBLE
        h.removeCallbacks(hideInfo)
        h.postDelayed(hideInfo, 900)
    }

    private val hideInfo = Runnable { info.visibility = View.GONE }

    // ------------------------------------------------------------ odświeżanie
    private fun refresh() {
        val t = Engine.current
        titleV.text = t?.title ?: ""
        playBtn.setImageResource(if (Engine.playing) R.drawable.ic_pause_fill else R.drawable.ic_play_fill)
        if (!dragging) {
            timeV.text = Library.time(Engine.time)
            seek.progress = if (Engine.length > 0) (Engine.time * 1000 / Engine.length).toInt() else 0
        }
        lenV.text = Library.time(Engine.length)
        val audio = !Engine.hasVideo
        audioPane.visibility = if (audio) View.VISIBLE else View.GONE
        if (audio) {
            aTitle.text = t?.title ?: ""
            aSub.text = t?.sub ?: ""
            val a = Engine.art
            if (a != null) cover.setImageBitmap(a) else cover.setImageResource(R.drawable.ic_music)
            if (a == null) cover.setColorFilter(0x88FFFFFF.toInt()) else cover.clearColorFilter()
        }
        abBtn.text = when {
            Engine.abA >= 0 && Engine.abB > 0 -> "A-B ✓"
            Engine.abA >= 0 -> "A-…"
            else -> "A-B"
        }
        abBtn.setTextColor(if (Engine.abA >= 0) Theme.accent else Color.WHITE)
        if (Engine.index != lastIndex) {
            lastIndex = Engine.index
            applyRotation()
        }
        if (Engine.videoW > 0 && rotMode == 0) applyRotation()
        if (Build.VERSION.SDK_INT >= 26) try {
            setPictureInPictureParams(pipParams())
        } catch (e: Exception) {
        }
    }

    private fun applyRotation() {
        requestedOrientation = when (rotMode) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> when {
                !Engine.hasVideo -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                Engine.videoW > 0 && Engine.videoW < Engine.videoH -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                Engine.videoW > 0 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            }
        }
    }

    private fun cycleRotation() {
        rotMode = (rotMode + 1) % 3
        flash(listOf("Obrót: automatyczny", "Obrót: poziomo", "Obrót: pionowo")[rotMode])
        applyRotation()
    }

    private fun cycleScale() {
        scaleIdx = (scaleIdx + 1) % scales.size
        Engine.mp?.setVideoScale(scales[scaleIdx].first)
        flash(scales[scaleIdx].second)
    }

    // ------------------------------------------------------------ pływające okienko
    private fun pipParams(): PictureInPictureParams {
        val w = if (Engine.videoW > 0) Engine.videoW else 16
        val hh = if (Engine.videoH > 0) Engine.videoH else 9
        val ratio = (w.toDouble() / hh).coerceIn(0.42, 2.38)
        fun act(icon: Int, title: String, code: Int) = RemoteAction(
            Icon.createWithResource(this, icon), title, title,
            PendingIntent.getBroadcast(this, code, Intent(ACT_PIP).setPackage(packageName).putExtra("a", code), PendingIntent.FLAG_IMMUTABLE)
        )
        val b = PictureInPictureParams.Builder()
            .setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
            .setActions(listOf(act(R.drawable.ic_prev, "Poprzedni", 1),
                act(if (Engine.playing) R.drawable.ic_pause_fill else R.drawable.ic_play_fill, "Odtwórz/Pauza", 2),
                act(R.drawable.ic_next, "Następny", 3)))
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(Store.autoPip && Engine.playing && Engine.hasVideo).setSeamlessResizeEnabled(true)
        return b.build()
    }

    private fun enterPip() {
        if (!Engine.hasVideo) {
            toast("Pływające okienko działa dla filmów. Muzyka gra w tle po wyjściu z ekranu.")
            return
        }
        try {
            enterPictureInPictureMode(pipParams())
        } catch (e: Exception) {
            toast("Włącz „Obraz w obrazie” dla aplikacji w ustawieniach telefonu")
        }
    }

    // ------------------------------------------------------------ gesty
    private fun setupGestures(v: View) {
        val am = getSystemService(AudioManager::class.java)
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        var mode = 0 // 0 brak, 1 przewijanie, 2 jasność, 3 głośność
        var sx = 0f
        var sy = 0f
        var startVol = 0
        var startBright = 0.5f
        var startTime = 0L
        var target = 0L
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (locked) {
                    lockBtn.alpha = 1f; h.postDelayed({ if (locked) lockBtn.alpha = 0.3f }, 2000)
                    return true
                }
                showControls(controls.visibility != View.VISIBLE)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (locked) return true
                val w = v.width
                when {
                    e.x < w / 3f -> {
                        Engine.seekBy(-Store.seekStep * 1000L); flash("−${Store.seekStep} s")
                    }
                    e.x > w * 2 / 3f -> {
                        Engine.seekBy(Store.seekStep * 1000L); flash("+${Store.seekStep} s")
                    }
                    else -> Engine.toggle()
                }
                return true
            }
        })
        val sg = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (locked || !Engine.hasVideo) return
                scaleIdx = if (detector.scaleFactor > 1f) 1 else 0
                Engine.mp?.setVideoScale(scales[scaleIdx].first)
                flash(scales[scaleIdx].second)
            }
        })
        v.setOnTouchListener { _, e ->
            sg.onTouchEvent(e)
            gd.onTouchEvent(e)
            if (locked || e.pointerCount > 1) return@setOnTouchListener true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    mode = 0; sx = e.x; sy = e.y
                    startVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    startBright = window.attributes.screenBrightness.let { if (it < 0) 0.5f else it }
                    startTime = Engine.time
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - sx
                    val dy = e.y - sy
                    if (mode == 0) {
                        if (Math.abs(dx) > dp(24) && Math.abs(dx) > Math.abs(dy) && Engine.length > 0) mode = 1
                        else if (Math.abs(dy) > dp(24)) mode = if (sx < v.width / 2f) 2 else 3
                    }
                    when (mode) {
                        1 -> {
                            val span = minOf(Engine.length, 180_000L).coerceAtLeast(60_000L)
                            target = (startTime + (dx / v.width * span).toLong()).coerceIn(0, Engine.length)
                            val d = (target - startTime) / 1000
                            flash((if (d >= 0) "+" else "−") + Library.time(Math.abs(d) * 1000) + "\n" + Library.time(target))
                        }
                        2 -> {
                            val b = (startBright - dy / v.height * 1.3f).coerceIn(0.01f, 1f)
                            window.attributes = window.attributes.apply { screenBrightness = b }
                            flash("☀ ${(b * 100).toInt()}%")
                        }
                        3 -> {
                            val nv = (startVol - dy / v.height * maxVol * 1.3f).toInt().coerceIn(0, maxVol)
                            am.setStreamVolume(AudioManager.STREAM_MUSIC, nv, 0)
                            flash("🔊 ${nv * 100 / maxVol}%")
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (mode == 1) Engine.seek(target)
                    mode = 0
                }
            }
            true
        }
    }

    // ------------------------------------------------------------ A-B, czas, prędkość
    private fun abToggle() {
        when {
            Engine.abA < 0 -> {
                Engine.abA = Engine.time; flash("Punkt A: " + Library.time(Engine.abA))
            }
            Engine.abB < 0 -> {
                if (Engine.time > Engine.abA + 500) {
                    Engine.abB = Engine.time; flash("Powtarzanie A-B"); Engine.seek(Engine.abA)
                }
            }
            else -> {
                Engine.abA = -1; Engine.abB = -1; flash("A-B wyłączone")
            }
        }
        refresh()
    }

    private fun speedSheet() {
        sheet("Prędkość odtwarzania", true) { col, d ->
            val speeds = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f, 4f)
            speeds.chunked(5).forEach { rowList ->
                val r = hbox()
                rowList.forEach { s ->
                    r.addView(darkBtn((if (s % 1f == 0f) s.toInt().toString() else s.toString().replace('.', ',')) + "×", Engine.rate == s) {
                        Engine.setSpeed(s); d.dismiss()
                    }, lp(0, WRAP, 1f).apply { leftMargin = dp(3); rightMargin = dp(3) })
                }
                col.addView(r, lp(top = 8))
            }
        }
    }

    private fun sleepSheet() {
        sheet("Wyłącznik czasowy", true) { col, d ->
            if (Engine.sleepAt > 0 || Engine.sleepEnd) col.addView(tv(if (Engine.sleepEnd) "Zatrzyma się po tym utworze/filmie" else
                "Pauza za " + Library.time(Engine.sleepAt - System.currentTimeMillis()), 14f, color = dim), lp(top = 6))
            listOf(10, 15, 30, 45, 60, 90).chunked(3).forEach { rowList ->
                val r = hbox()
                rowList.forEach { m ->
                    r.addView(darkBtn("$m min") { Engine.sleepAt = System.currentTimeMillis() + m * 60_000L; Engine.sleepEnd = false; d.dismiss(); flash("Pauza za $m min") },
                        lp(0, WRAP, 1f).apply { leftMargin = dp(3); rightMargin = dp(3) })
                }
                col.addView(r, lp(top = 8))
            }
            col.addView(darkBtn("Po tym utworze / filmie") { Engine.sleepEnd = true; Engine.sleepAt = 0; d.dismiss() }, lp(top = 10))
            col.addView(darkBtn("Wyłącz") { Engine.sleepEnd = false; Engine.sleepAt = 0; d.dismiss() }, lp(top = 8))
        }
    }

    // ------------------------------------------------------------ ścieżki i napisy
    private fun tracksSheet() {
        sheet("Dźwięk i napisy", true) { col, d ->
            col.addView(tv("Ścieżka dźwiękowa", 13f, true, dim), lp(top = 8))
            val at = Engine.audioTrack()
            Engine.audioTracks().forEach { (id, name) -> col.addView(darkBtn(name, id == at) { Engine.setAudio(id); d.dismiss() }, lp(top = 6)) }
            col.addView(tv("Napisy", 13f, true, dim), lp(top = 14))
            val st = Engine.spuTrack()
            val spu = Engine.spuTracks()
            if (spu.isEmpty()) col.addView(tv("Brak napisów w pliku", 14f, color = dim), lp(top = 4))
            spu.forEach { (id, name) -> col.addView(darkBtn(if (id == -1) "Wyłączone" else name, id == st) { Engine.setSpu(id); d.dismiss() }, lp(top = 6)) }
            val r = hbox()
            r.addView(darkBtn("Wczytaj z pliku") {
                d.dismiss()
                try {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_SUB)
                } catch (e: Exception) {
                }
            }, lp(0, WRAP, 1f).apply { rightMargin = dp(6) })
            r.addView(darkBtn("Pobierz z internetu") { d.dismiss(); subsSheet() }, lp(0, WRAP, 1f))
            col.addView(r, lp(top = 10))
            col.addView(tv("Przesunięcie napisów: ${Engine.spuDelay()} ms", 13f, true, dim), lp(top = 14))
            col.addView(delayRow { ms -> Engine.setSpuDelay(Engine.spuDelay() + ms); d.dismiss(); tracksSheet() }, lp(top = 6))
            col.addView(tv("Przesunięcie dźwięku: ${Engine.audioDelay()} ms", 13f, true, dim), lp(top = 12))
            col.addView(delayRow { ms -> Engine.setAudioDelay(Engine.audioDelay() + ms); d.dismiss(); tracksSheet() }, lp(top = 6))
        }
    }

    private fun delayRow(f: (Long) -> Unit): View {
        val r = hbox()
        listOf(-1000L, -100L, 100L, 1000L).forEach { ms ->
            r.addView(darkBtn((if (ms > 0) "+" else "") + if (Math.abs(ms) >= 1000) "${ms / 1000} s" else "$ms ms") { f(ms) }, lp(0, WRAP, 1f).apply { leftMargin = dp(3); rightMargin = dp(3) })
        }
        return r
    }

    private fun subsSheet() {
        val t = Engine.current ?: return
        if (Store.osKey.isBlank()) {
            sheet("Pobieranie napisów", true) { col, _ ->
                col.addView(tv("Załóż darmowe konto na opensubtitles.com, w profilu utwórz klucz API (API Consumers) i wpisz go razem z loginem w Ustawieniach odtwarzacza.", 14f), lp(top = 8))
            }
            return
        }
        val dlg = sheet("Szukam napisów…", true) { _, _ -> }
        Thread {
            val res = try {
                Subs.search(this, t)
            } catch (e: Exception) {
                h.post { dlg.dismiss(); toast(e.message ?: "Błąd wyszukiwania") }
                return@Thread
            }
            h.post {
                dlg.dismiss()
                sheet("Napisy (${res.size})", true) { col, d ->
                    if (res.isEmpty()) col.addView(tv("Nic nie znaleziono", 14f, color = dim), lp(top = 8))
                    res.take(30).forEach { hit ->
                        col.addView(darkBtn((if (hit.byHash) "✓ " else "") + "[${hit.lang}] ${hit.name}") {
                            d.dismiss()
                            Thread {
                                try {
                                    val f = Subs.download(this, hit)
                                    h.post { Engine.addSubtitle(Uri.fromFile(f)); toast("Napisy wczytane") }
                                } catch (e: Exception) {
                                    h.post { toast(e.message ?: "Nie udało się pobrać") }
                                }
                            }.start()
                        }.apply { gravity = Gravity.START }, lp(top = 6))
                    }
                    col.addView(tv("✓ = dopasowane dokładnie do tego pliku", 12f, color = dim), lp(top = 10))
                }
            }
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SUB && resultCode == RESULT_OK) {
            val u = data?.data ?: return
            Thread {
                try {
                    val f = File(File(cacheDir, "subs").apply { mkdirs() }, nameOf(u).ifEmpty { "napisy.srt" })
                    contentResolver.openInputStream(u)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                    h.post { Engine.addSubtitle(Uri.fromFile(f)); toast("Napisy wczytane") }
                } catch (e: Exception) {
                    h.post { toast("Nie mogę wczytać napisów") }
                }
            }.start()
        }
    }

    // ------------------------------------------------------------ korektor
    private fun eqSheet() {
        sheet("Korektor dźwięku", true) { col, d ->
            col.addView(darkBtn(if (Store.eqOn) "Włączony" else "Wyłączony", Store.eqOn) {
                Store.eqOn = !Store.eqOn; Engine.applyEq(); Store.save(); d.dismiss(); eqSheet()
            }, lp(top = 8))
            col.addView(tv("Gotowe ustawienia", 13f, true, dim), lp(top = 12))
            val r = hbox()
            Engine.eqPresets().forEachIndexed { k, name ->
                r.addView(darkBtn(name, Store.eqOn && Store.eqPreset == k) {
                    Engine.loadPreset(k); Store.eqOn = true; Engine.applyEq(); Store.save(); d.dismiss(); eqSheet()
                }, lp(WRAP, WRAP).apply { rightMargin = dp(6) })
            }
            col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(r) }, lp(top = 6))
            col.addView(tv("Pasma (dB)", 13f, true, dim), lp(top = 12))
            for (b in 0 until minOf(Engine.eqBandCount(), 10)) {
                val row = hbox()
                val f = Engine.eqFreq(b)
                row.addView(tv(if (f >= 1000) "${(f / 1000).toInt()}k" else "${f.toInt()}", 12f, color = dim), LinearLayout.LayoutParams(dp(40), WRAP))
                val sb = SeekBar(this).apply {
                    max = 40
                    progress = (Store.eqBands[b] + 20).toInt().coerceIn(0, 40)
                    progressTintList = ColorStateList.valueOf(Theme.accent)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                            if (!fromUser) return
                            Store.eqBands[b] = (p - 20).toFloat()
                            Store.eqOn = true
                            Engine.applyEq()
                        }

                        override fun onStartTrackingTouch(s: SeekBar?) {}
                        override fun onStopTrackingTouch(s: SeekBar?) = Store.save()
                    })
                }
                row.addView(sb, lp(0, WRAP, 1f))
                col.addView(row, lp(top = 2))
            }
        }
    }

    // ------------------------------------------------------------ telewizor
    private fun castSheet() {
        Engine.discoverRenderers()
        sheet("Wyślij na telewizor", true) { col, d ->
            col.addView(tv("Szukam urządzeń Chromecast / Google TV w tej sieci Wi-Fi…", 13f, color = dim), lp(top = 6))
            val list = vbox()
            col.addView(list, lp(top = 6))
            fun fill() {
                list.removeAllViews()
                list.addView(darkBtn("Ten telefon", Engine.renderer == null) { Engine.cast(null); d.dismiss() }, lp(top = 6))
                Engine.renderers.forEach { r ->
                    list.addView(darkBtn("📺 " + (r.displayName ?: r.name), Engine.renderer?.name == r.name) { Engine.cast(r); d.dismiss(); toast("Wysyłam na ${r.displayName}") }, lp(top = 6))
                }
            }
            fill()
            val l: () -> Unit = { fill() }
            Engine.listeners.add(l)
            d.setOnDismissListener { Engine.listeners.remove(l) }
            col.addView(tv("Filmy w formatach, których telewizor nie zna, są przerabiane w locie przez telefon.", 12f, color = dim), lp(top = 10))
        }
    }

    // ------------------------------------------------------------ kolejka
    private fun queueSheet() {
        sheet("Kolejka (${Engine.queue.size})", true) { col, d ->
            val r = hbox()
            r.addView(darkBtn(listOf("Bez powtarzania", "Powtarzaj wszystko", "Powtarzaj jeden")[Store.repeat], Store.repeat > 0) {
                Store.repeat = (Store.repeat + 1) % 3; Store.save(); d.dismiss(); queueSheet()
            }, lp(0, WRAP, 1f).apply { rightMargin = dp(6) })
            r.addView(darkBtn("Losowo", Store.shuffle) { Store.shuffle = !Store.shuffle; Store.save(); d.dismiss(); queueSheet() }, lp(0, WRAP, 1f))
            col.addView(r, lp(top = 8))
            Engine.queue.forEachIndexed { k, t ->
                col.addView(darkBtn((if (k == Engine.index) "▶ " else "") + t.title, k == Engine.index) { Engine.load(k); d.dismiss() }.apply {
                    gravity = Gravity.START; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                }, lp(top = 6))
            }
        }
    }

    // ------------------------------------------------------------ więcej
    private fun moreSheet() {
        val t = Engine.current ?: return
        sheet(t.title, true) { col, d ->
            val ch = Engine.chapters()
            if (ch.isNotEmpty()) col.addView(darkBtn("Rozdziały (${ch.size})") { d.dismiss(); chaptersSheet(ch) }, lp(top = 8))
            col.addView(darkBtn("✂ Wytnij fragment / zrób GIF") { d.dismiss(); cutSheet() }, lp(top = 8))
            if (Engine.hasVideo) col.addView(darkBtn("📷 Zapisz klatkę") { d.dismiss(); screenshot() }, lp(top = 8))
            col.addView(darkBtn("➕ Dodaj do playlisty") { d.dismiss(); addToPlaylist(t) }, lp(top = 8))
            col.addView(darkBtn("Udostępnij plik") {
                d.dismiss()
                val u = if (t.uri.startsWith("/")) androidx.core.content.FileProvider.getUriForFile(this, "pl.konwerter.files", File(t.uri)) else Uri.parse(t.uri)
                try {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(if (t.video) "video/*" else "audio/*")
                        .putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Udostępnij"))
                } catch (e: Exception) {
                    toast("Nie mogę udostępnić")
                }
            }, lp(top = 8))
            col.addView(tv(listOfNotNull(
                if (Engine.videoW > 0) "${Engine.videoW}×${Engine.videoH}" else null,
                Library.time(Engine.length), t.uri.substringAfterLast('/').take(60)
            ).joinToString(" · "), 12f, color = dim), lp(top = 12))
        }
    }

    private fun chaptersSheet(ch: List<Pair<Long, String>>) {
        sheet("Rozdziały", true) { col, d ->
            ch.forEach { (at, name) -> col.addView(darkBtn(Library.time(at) + "  " + name) { Engine.seek(at); d.dismiss() }.apply { gravity = Gravity.START }, lp(top = 6)) }
        }
    }

    private fun addToPlaylist(t: Track) {
        sheet("Dodaj do playlisty", true) { col, d ->
            Store.playlists.forEach { p ->
                col.addView(darkBtn(p.name + " (${p.items.size})") {
                    p.items.add(t); Store.save(); d.dismiss(); toast("Dodano do: ${p.name}")
                }, lp(top = 6))
            }
            col.addView(darkBtn("Nowa playlista", true) {
                val p = Playlist(if (t.video) "Moje filmy" else "Moja muzyka", mutableListOf(t))
                Store.playlists.add(p); Store.save(); d.dismiss(); toast("Utworzono „${p.name}” – nazwę zmienisz w zakładce Playlisty")
            }, lp(top = 10))
        }
    }

    private fun mediaUri(t: Track): Uri = if (t.uri.startsWith("/")) Uri.fromFile(File(t.uri)) else Uri.parse(t.uri)

    private fun screenshot() {
        val t = Engine.current ?: return
        val at = Engine.time
        Thread {
            var ok = false
            try {
                val r = MediaMetadataRetriever()
                if (t.uri.startsWith("/")) r.setDataSource(t.uri) else r.setDataSource(this, Uri.parse(t.uri))
                val bm = r.getFrameAtTime(at * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                r.release()
                if (bm != null) {
                    val cv = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, t.title.substringBeforeLast('.') + "_" + Library.time(at).replace(':', '-') + ".jpg")
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Player")
                    }
                    contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)?.let { u ->
                        contentResolver.openOutputStream(u)?.use { bm.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                        ok = true
                    }
                }
            } catch (e: Exception) {
            }
            h.post { toast(if (ok) "Zapisano w Obrazy/Player" else "Nie udało się zapisać klatki (strumień sieciowy?)") }
        }.start()
    }

    /** Wycięcie fragmentu (A-B albo okolica bieżącego miejsca) do Konwertera. */
    private fun cutSheet() {
        val t = Engine.current ?: return
        if (!(t.uri.startsWith("/") || t.uri.startsWith("content://"))) {
            toast("Wycinanie działa dla plików z telefonu"); return
        }
        var a = if (Engine.abA >= 0) Engine.abA else (Engine.time - 15_000).coerceAtLeast(0)
        var b = if (Engine.abB > 0) Engine.abB else (Engine.time + 15_000).coerceAtMost(Engine.length)
        sheet("Wytnij fragment", true) { col, d ->
            val label = tv("", 16f, true)
            fun upd() {
                label.text = "Od ${Library.time(a)} do ${Library.time(b)}  (${Library.time(b - a)})"
            }
            upd()
            col.addView(label, lp(top = 8))
            col.addView(tv("Wskazówka: ustaw punkty przyciskiem A-B na pasku odtwarzacza.", 12f, color = dim), lp(top = 4))
            val r1 = hbox()
            r1.addView(darkBtn("Początek = teraz") { a = Engine.time; if (b <= a) b = (a + 10_000).coerceAtMost(Engine.length); upd() }, lp(0, WRAP, 1f).apply { rightMargin = dp(6) })
            r1.addView(darkBtn("Koniec = teraz") { b = Engine.time; if (a >= b) a = (b - 10_000).coerceAtLeast(0); upd() }, lp(0, WRAP, 1f))
            col.addView(r1, lp(top = 10))
            col.addView(tv("Zapisz jako", 13f, true, dim), lp(top = 14))
            val r2 = hbox()
            listOf("mp4fast" to "MP4 szybko", "mp4" to "MP4", "gif" to "GIF").forEach { (k, label2) ->
                r2.addView(darkBtn(label2) {
                    d.dismiss()
                    val uri = mediaUri(t)
                    Thread {
                        val job = Queue.probe(this, uri)
                        job.target = if (k == "gif" || job.hasVideo) k else "m4a"
                        job.clipStart = a
                        job.clipEnd = b
                        job.gifStart = (a / 1000).toInt()
                        job.gifSeconds = ((b - a) / 1000).toInt().coerceIn(1, 60)
                        job.gifWidth = Store.gifWidth
                        job.gifFps = Store.gifFps
                        h.post {
                            Queue.jobs.add(job)
                            startForegroundService(Intent(this, ConvertService::class.java))
                            toast("Wycinam fragment – postęp w zakładce Konwerter")
                        }
                    }.start()
                }, lp(0, WRAP, 1f).apply { leftMargin = dp(3); rightMargin = dp(3) })
            }
            col.addView(r2, lp(top = 6))
            col.addView(tv("„MP4 szybko” tnie bez kodowania (początek może przesunąć się do najbliższej klatki kluczowej).", 12f, color = dim), lp(top = 8))
        }
    }
}

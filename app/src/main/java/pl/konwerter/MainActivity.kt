package pl.konwerter

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val REQ_PICK = 11
    private val ACTION_INSTALL = "pl.konwerter.INSTALL_RESULT"

    private lateinit var root: FrameLayout
    private val h = Handler(Looper.getMainLooper())
    private var screen = "main"
    private var lastView = ""
    private var lastState = ""
    private val bars = HashMap<Job, Pair<ProgressBar, TextView>>()
    private var update: Updater.Info? = null
    private var updMsg = ""
    private var updStatus: TextView? = null

    // ------------------------------------------------------------ kolory
    private val inv get() = Store.eink && Store.invert
    private val bg get() = if (Store.eink) (if (inv) Color.BLACK else Color.WHITE) else Theme.bg
    private val surface get() = if (Store.eink) bg else Theme.surface
    private val fg get() = if (Store.eink) (if (inv) Color.WHITE else Color.BLACK) else Theme.fg
    private val dim get() = if (Store.eink) (if (inv) 0xFFAAAAAA.toInt() else 0xFF555555.toInt()) else Theme.dim
    private val lineC get() = if (Store.eink) fg else Theme.line
    private val accent get() = if (Store.eink) fg else Theme.accent
    private val onAccent get() = if (Store.eink) bg else Color.WHITE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        root = FrameLayout(this)
        root.fitsSystemWindows = true
        setContentView(root)
        handleIntent(intent)
        render()
        checkUpdate(false)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 5)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        render()
    }

    override fun onResume() {
        super.onResume()
        h.post(tick)
    }

    override fun onPause() {
        h.removeCallbacks(tick)
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (screen) {
            "help" -> go("settings")
            "settings" -> go("main")
            else -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    /** Odświeżanie postępu (pełne przerysowanie tylko przy zmianie stanu). */
    private val tick = object : Runnable {
        override fun run() {
            val state = Queue.jobs.joinToString { it.status } + Queue.running
            if (state != lastState) {
                lastState = state
                if (screen == "main") render()
            } else {
                for ((job, p) in bars) {
                    p.first.progress = job.progress
                    p.second.text = statusText(job)
                }
            }
            h.postDelayed(this, 500)
        }
    }

    private fun go(s: String) {
        screen = s
        render()
    }

    // ------------------------------------------------------------ pomocnicze widoki
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(text: String, size: Float = 16f, bold: Boolean = false, color: Int = fg) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    private fun round(fill: Int, radius: Int, stroke: Int = 0, strokeColor: Int = lineC) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke > 0) setStroke(dp(stroke), strokeColor)
    }

    private fun btn(text: String, selected: Boolean = false, size: Float = 15f, onClick: () -> Unit) =
        tv(text, size, true, if (selected) onAccent else fg).apply {
            background = if (Store.eink) round(if (selected) fg else bg, 10, 2, fg)
            else round(if (selected) accent else surface, 14, if (selected) 0 else 1)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }

    private fun primary(text: String, onClick: () -> Unit) = tv(text, 17f, true, onAccent).apply {
        background = if (Store.eink) round(accent, 10) else Theme.gradient(dp(18).toFloat())
        setPadding(dp(16), dp(16), dp(16), dp(16))
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun icon(res: Int, sizeDp: Int = 24, color: Int = fg, onClick: (() -> Unit)? = null) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(color)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        if (onClick != null) setOnClickListener { onClick() }
    }

    private fun roundIcon(res: Int, onClick: () -> Unit): View {
        val f = FrameLayout(this)
        f.background = if (Store.eink) round(bg, 22, 2, fg) else round(surface, 22, 1)
        f.addView(icon(res, 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        f.setOnClickListener { onClick() }
        f.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        return f
    }

    private fun vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun hbox() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun lp(w: Int = MATCH, hh: Int = WRAP, weight: Float = 0f, top: Int = 0) =
        LinearLayout.LayoutParams(w, hh, weight).apply { topMargin = dp(top) }

    private fun page(): LinearLayout {
        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        val col = vbox()
        col.setPadding(dp(16), dp(12), dp(16), dp(40))
        sv.addView(col)
        root.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
        return col
    }

    private fun panel(col: LinearLayout, top: Int = 8): LinearLayout {
        val p = vbox().apply {
            background = if (Store.eink) round(bg, 12, 2, fg) else round(surface, 18)
            setPadding(dp(16), dp(10), dp(16), dp(14))
        }
        col.addView(p, lp(top = top))
        return p
    }

    private fun section(col: LinearLayout, title: String, top: Int = 22) {
        col.addView(tv(title.uppercase(), 12f, true, if (Store.eink) dim else accent).apply { letterSpacing = 0.08f }, lp(top = top))
    }

    private fun seg(col: LinearLayout, label: String, opts: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
        if (label.isNotEmpty()) col.addView(tv(label, 15f), lp(top = 12))
        val r = hbox()
        opts.forEachIndexed { n, (text, sel) ->
            r.addView(btn(text, sel, 14f) { onPick(n) }, lp(WRAP, WRAP, top = 6).apply { rightMargin = dp(6) })
        }
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(r) })
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun applyWindow() {
        if (!Store.eink) Theme.apply(this, Store.theme, Store.mode)
        root.setBackgroundColor(bg)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (inv || (!Store.eink && Theme.dark)) 0 else
            (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
    }

    private fun render() {
        val oldY = if (screen == lastView) (root.getChildAt(0) as? ScrollView)?.scrollY ?: 0 else 0
        lastView = screen
        applyWindow()
        root.removeAllViews()
        bars.clear()
        updStatus = null
        when (screen) {
            "settings" -> renderSettings()
            "help" -> renderHelp()
            else -> renderMain()
        }
        if (oldY > 0) (root.getChildAt(0) as? ScrollView)?.let { sv -> sv.post { sv.scrollTo(0, oldY) } }
    }

    // ------------------------------------------------------------ pliki
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        handleInstall(i)
        val uris = ArrayList<Uri>()
        when (i.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
            }
            Intent.ACTION_VIEW -> i.data?.let { uris.add(it) }
        }
        if (uris.isNotEmpty()) addFiles(uris)
    }

    private fun addFiles(uris: List<Uri>) {
        Thread {
            val jobs = uris.map { u ->
                try {
                    contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                }
                Queue.probe(this, u)
            }
            h.post {
                Queue.jobs.removeAll { it.status == "done" || it.status == "error" || it.status == "cancel" }
                Queue.jobs.addAll(jobs)
                // podpowiedź: same pliki audio → format audio
                if (jobs.isNotEmpty() && jobs.none { it.hasVideo } && !Targets.isAudio(Store.target)) {
                    Store.target = "m4a"; Store.save()
                }
                render()
            }
        }.start()
    }

    private fun pick() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "audio/*"))
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            startActivityForResult(i, REQ_PICK)
        } catch (e: Exception) {
            toast("Nie mogę otworzyć wyboru plików")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.clipData?.let { c -> for (k in 0 until c.itemCount) uris.add(c.getItemAt(k).uri) }
        if (uris.isEmpty()) data.data?.let { uris.add(it) }
        if (uris.isNotEmpty()) addFiles(uris)
    }

    private fun start() {
        val waiting = Queue.jobs.filter { it.status == "wait" }
        if (waiting.isEmpty()) return
        for (j in waiting) {
            j.target = if (!j.hasVideo && !Targets.isAudio(Store.target)) "m4a" else Store.target
            j.height = Store.height
            j.quality = Store.quality
            j.removeAudio = Store.removeAudio
            j.gifSeconds = Store.gifSeconds
            j.gifWidth = Store.gifWidth
            j.gifFps = Store.gifFps
            j.gifStart = Store.gifStart
        }
        startForegroundService(Intent(this, ConvertService::class.java))
        h.postDelayed({ render() }, 200)
    }

    private fun statusText(j: Job): String = when (j.status) {
        "run" -> "Konwertuję… ${j.progress}%" + eta(j)
        "done" -> "Gotowe · " + Queue.sizeText(j.outSize) + " · " + Queue.time(j.finished - j.started)
        "error" -> "Błąd: " + j.error
        "cancel" -> "Anulowano"
        else -> "Czeka"
    }

    private fun eta(j: Job): String {
        if (j.progress < 3) return ""
        val el = System.currentTimeMillis() - j.started
        val left = el * (100 - j.progress) / j.progress
        return " · zostało ok. " + Queue.time(left)
    }

    private fun kindMime(j: Job): String = when {
        j.target == "gif" -> "image/gif"
        Targets.isAudio(j.target) || !j.hasVideo -> "audio/*"
        else -> "video/*"
    }

    private fun share(uri: Uri, mime: String) {
        val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "Udostępnij"))
    }

    private fun open(uri: Uri, mime: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            toast("Brak aplikacji do odtworzenia")
        }
    }

    // ------------------------------------------------------------ ekran główny
    private fun renderMain() {
        val col = page()
        if (Store.eink) {
            val head = hbox()
            head.addView(tv("Konwerter", 32f, true), lp(0, WRAP, 1f))
            head.addView(roundIcon(R.drawable.ic_settings) { go("settings") })
            col.addView(head)
            col.addView(tv("Wideo i audio – bez internetu", 14f, color = dim))
        } else {
            val hero = vbox().apply {
                background = Theme.gradient(dp(24).toFloat())
                elevation = dp(4).toFloat()
                setPadding(dp(20), dp(16), dp(14), dp(20))
                setOnLongClickListener { Store.mode = if (Theme.dark) "light" else "dark"; Store.save(); render(); true }
            }
            val top = hbox()
            top.addView(tv("Wideo i audio – bez internetu", 13f, true, Theme.withAlpha(Color.WHITE, 0.85f)), lp(0, WRAP, 1f))
            val gear = FrameLayout(this).apply { background = round(Theme.withAlpha(Color.WHITE, 0.22f), 20); setOnClickListener { go("settings") } }
            gear.addView(icon(R.drawable.ic_settings, 20, Color.WHITE), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            top.addView(gear, LinearLayout.LayoutParams(dp(40), dp(40)))
            hero.addView(top)
            hero.addView(tv("Konwerter", 34f, true, Color.WHITE), lp(top = 2))
            val done = Queue.jobs.count { it.status == "done" }
            hero.addView(tv(if (Queue.jobs.isEmpty()) "MP4 · HEVC · MOV · 3GP · GIF · M4A · WAV" else "${Queue.jobs.size} plików · gotowe: $done", 13f, false, Theme.withAlpha(Color.WHITE, 0.85f)))
            col.addView(hero, lp(top = 4))
        }

        val upd = update
        if (upd != null) {
            val box = hbox().apply { background = if (Store.eink) round(bg, 12, 2, fg) else round(surface, 18); setPadding(dp(16), dp(12), dp(12), dp(12)) }
            val st = tv(if (updMsg.startsWith("Pobieram") || updMsg.startsWith("Błąd")) updMsg else "Nowa wersja 1.${upd.number}", 15f, true)
            updStatus = st
            box.addView(st, lp(0, WRAP, 1f))
            box.addView(btn("Zainstaluj", true, 14f) { installUpdate() })
            col.addView(box, lp(top = 12))
        }

        col.addView(primary("＋  Wybierz pliki wideo lub audio") { pick() }, lp(top = 14))
        col.addView(tv("Możesz też w Galerii lub Moich plikach wybrać plik → Udostępnij → Konwerter.", 12f, color = dim).apply { gravity = Gravity.CENTER }, lp(top = 6))

        // format
        section(col, "Zamień na")
        val p = panel(col)
        val anyVideo = Queue.jobs.any { it.status == "wait" && it.hasVideo } || Queue.jobs.none { it.status == "wait" }
        fun group(title: String, list: List<Pair<String, String>>) {
            p.addView(tv(title, 13f, true, dim), lp(top = 10))
            list.chunked(3).forEach { row ->
                val r = hbox()
                row.forEach { (k, label) ->
                    r.addView(btn(label, Store.target == k, 14f) { Store.target = k; Store.save(); render() }, lp(0, WRAP, 1f).apply { leftMargin = dp(3); rightMargin = dp(3) })
                }
                repeat(3 - row.size) { r.addView(View(this), lp(0, 1, 1f)) }
                p.addView(r, lp(top = 6))
            }
        }
        if (anyVideo) group("Wideo", Targets.VIDEO)
        group("Audio", Targets.AUDIO)
        p.addView(tv(when (Store.target) {
            "mp4" -> "H.264 + AAC. Otworzy się wszędzie: WhatsApp, telewizor, komputer, iPhone."
            "mp4hevc" -> "H.265 – ok. 40% mniejszy plik przy tej samej jakości. Nowsze telefony, TV i komputery (Windows może wymagać dodatku HEVC)."
            "mp4fast" -> "Tylko zmiana „opakowania” na MP4, bez kodowania. Kilka sekund, zero straty jakości. Dla wideo w H.264/HEVC (np. MOV z iPhone'a)."
            "mov" -> "Format Apple QuickTime (H.264 + AAC) – do iPhone'a, Maca, iMovie i Final Cut."
            "3gp" -> "Bardzo mały plik na stare telefony i MMS. Najlepiej z 480p i jakością „Mały plik”."
            "gif" -> "Animacja bez dźwięku z wybranego fragmentu – do komunikatorów, prezentacji i stron."
            "m4a" -> "Sam dźwięk w AAC – mały plik, dobra jakość (z filmu albo z MP3, FLAC, OGG…)."
            else -> "Dźwięk bez kompresji – duży plik, idealny do edycji."
        }, 13f, color = dim), lp(top = 10))
        if (Targets.encodes(Store.target)) {
            seg(p, "Rozdzielczość", listOf("Oryginał" to (Store.height == 0), "1080p" to (Store.height == 1080), "720p" to (Store.height == 720), "480p" to (Store.height == 480), "360p" to (Store.height == 360))) {
                Store.height = listOf(0, 1080, 720, 480, 360)[it]; Store.save(); render()
            }
            seg(p, "Jakość", listOf("Wysoka" to (Store.quality == "high"), "Średnia" to (Store.quality == "medium"), "Mały plik" to (Store.quality == "small"))) {
                Store.quality = listOf("high", "medium", "small")[it]; Store.save(); render()
            }
        }
        if (Targets.encodes(Store.target) || Store.target == "mp4fast") {
            seg(p, "Dźwięk", listOf("Zostaw" to !Store.removeAudio, "Usuń (wyciszony film)" to Store.removeAudio)) {
                Store.removeAudio = it == 1; Store.save(); render()
            }
        }
        if (Store.target == "gif") {
            p.addView(tv("Początek fragmentu (sekunda filmu)", 15f), lp(top = 12))
            val e = EditText(this).apply {
                setText(Store.gifStart.toString())
                inputType = InputType.TYPE_CLASS_NUMBER
                setTextColor(fg)
                background = if (Store.eink) round(bg, 10, 2, fg) else round(bg, 14, 1)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(x: CharSequence?, a1: Int, a2: Int, a3: Int) {}
                    override fun onTextChanged(x: CharSequence?, a1: Int, a2: Int, a3: Int) {}
                    override fun afterTextChanged(x: android.text.Editable?) {
                        Store.gifStart = x?.toString()?.toIntOrNull() ?: 0
                    }
                })
            }
            p.addView(e, LinearLayout.LayoutParams(dp(120), WRAP).apply { topMargin = dp(6) })
            seg(p, "Długość", listOf(3, 5, 10, 20, 30).map { "$it s" to (Store.gifSeconds == it) }) {
                Store.gifSeconds = listOf(3, 5, 10, 20, 30)[it]; Store.save(); render()
            }
            seg(p, "Szerokość", listOf(320, 480, 640, 800).map { "$it px" to (Store.gifWidth == it) }) {
                Store.gifWidth = listOf(320, 480, 640, 800)[it]; Store.save(); render()
            }
            seg(p, "Płynność", listOf(8, 10, 15).map { "$it kl/s" to (Store.gifFps == it) }) {
                Store.gifFps = listOf(8, 10, 15)[it]; Store.save(); render()
            }
            p.addView(tv("Większa szerokość, płynność i długość = większy plik. Do komunikatorów zwykle wystarcza 480 px, 10 kl/s, do 10 s.", 12f, color = dim), lp(top = 8))
        }
        p.addView(tv("Otwiera też MKV, AVI, WebM, TS, FLV i inne – zapis do tych formatów nie jest możliwy bez dodatkowych bibliotek, ale MP4 odtworzy się wszędzie tam, gdzie one.", 12f, color = dim), lp(top = 12))

        // kolejka
        if (Queue.jobs.isNotEmpty()) {
            section(col, "Pliki (${Queue.jobs.size})")
            for (j in Queue.jobs) {
                val c = vbox().apply {
                    background = if (Store.eink) round(bg, 12, 2, fg) else round(surface, 18)
                    setPadding(dp(16), dp(12), dp(12), dp(12))
                }
                val r = hbox()
                val left = vbox()
                left.addView(tv(j.name, 15f, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.MIDDLE })
                left.addView(tv(j.info, 12f, color = dim))
                r.addView(left, lp(0, WRAP, 1f))
                if (j.status == "wait" && !Queue.running) r.addView(icon(R.drawable.ic_close, 22, dim) { Queue.jobs.remove(j); render() })
                c.addView(r)
                if (j.status != "wait" || Queue.running) {
                    val target = tv("→ " + Targets.label(j.target) + if (Targets.encodes(j.target) && j.height > 0) " ${j.height}p" else "", 12f, true, accent)
                    if (j.status != "wait") c.addView(target, lp(top = 6))
                    val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 100
                        progress = j.progress
                        progressTintList = ColorStateList.valueOf(if (j.status == "error") 0xFFDC2626.toInt() else accent)
                    }
                    if (j.status == "run" || j.status == "done") c.addView(pb, lp(top = 6))
                    val st = tv(statusText(j), 13f, j.status == "done", if (j.status == "error") 0xFFDC2626.toInt() else dim)
                    c.addView(st, lp(top = 4))
                    bars[j] = Pair(pb, st)
                }
                val out = j.outUri
                if (j.status == "done" && out != null) {
                    val mime = kindMime(j)
                    val br = hbox()
                    br.addView(btn("Otwórz", true, 13f) { open(out, mime) }, lp(WRAP, WRAP).apply { rightMargin = dp(6) })
                    br.addView(btn("Udostępnij", size = 13f) { share(out, mime) })
                    c.addView(br, lp(top = 8))
                    c.addView(tv("Zapisano w " + when (mime) {
                        "image/gif" -> "Obrazy/Konwerter"
                        "audio/*" -> "Muzyka/Konwerter"
                        else -> "Filmy/Konwerter"
                    }, 12f, color = dim), lp(top = 6))
                }
                col.addView(c, lp(top = 10))
            }
            val waiting = Queue.pending()
            when {
                Queue.running -> col.addView(btn("Anuluj konwersję") {
                    startService(Intent(this, ConvertService::class.java).setAction(ConvertService.ACT_CANCEL))
                }, lp(top = 14))
                waiting > 0 -> col.addView(primary("Konwertuj ($waiting) → " + Targets.label(Store.target)) { start() }, lp(top = 14))
            }
            if (!Queue.running) col.addView(btn("Wyczyść listę", size = 14f) { Queue.jobs.clear(); render() }, lp(top = 8))
        } else {
            section(col, "Co potrafi")
            val i = panel(col)
            listOf(
                "MOV (iPhone), MKV, AVI, WebM, 3GP, TS → MP4, MP4 HEVC, MOV, 3GP",
                "Fragment filmu → animowany GIF",
                "Zmniejszanie filmów: 1080p / 720p / 480p, mniejszy plik do WhatsAppa",
                "Wyciąganie dźwięku z filmu do M4A albo WAV",
                "MP3, FLAC, OGG, OPUS, WAV, AMR → M4A albo WAV",
                "Wiele plików naraz, działa w tle i bez internetu"
            ).forEach { i.addView(tv("•  $it", 14f), lp(top = 6)) }
        }
    }

    // ------------------------------------------------------------ ustawienia
    private fun swatch(pr: Theme.Preset, selected: Boolean, onClick: () -> Unit): View {
        val (a, b) = Theme.colorsOf(this, pr)
        val v = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(6), 0, dp(4)); setOnClickListener { onClick() } }
        val ring = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); if (selected) setStroke(dp(3), fg) }
        }
        ring.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(a, b)).apply { shape = GradientDrawable.OVAL }
        }, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        v.addView(ring, LinearLayout.LayoutParams(dp(50), dp(50)))
        v.addView(tv(pr.name, 11f, selected, if (selected) fg else dim).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, lp(top = 2))
        return v
    }

    private fun renderSettings() {
        val col = page()
        col.addView(roundIcon(R.drawable.ic_back_arrow) { go("main") })
        col.addView(tv("Ustawienia", 30f, true), lp(top = 10))
        section(col, "Wygląd")
        val p = panel(col)
        seg(p, "Styl", listOf("Kolorowy" to !Store.eink, "E-ink (czarno-biały)" to Store.eink)) { Store.eink = it == 1; Store.save(); render() }
        if (Store.eink) seg(p, "Kolory e-ink", listOf("Czarne na białym" to !Store.invert, "Białe na czarnym" to Store.invert)) { Store.invert = it == 1; Store.save(); render() }
        else {
            p.addView(tv("Motyw kolorów", 15f), lp(top = 12))
            val items = Theme.PRESETS.filter { it.id != "auto" || Theme.hasWallpaperColors() }.map { pr ->
                swatch(pr, Store.theme == pr.id) { Store.theme = pr.id; Store.save(); render() }
            }
            for (start in items.indices step 4) {
                val row = hbox()
                for (k in 0 until 4) row.addView(items.getOrNull(start + k) ?: View(this), lp(0, WRAP, 1f))
                p.addView(row, lp(top = 4))
            }
            seg(p, "Tryb", Theme.MODES.map { (id, label) -> label to (Store.mode == id) }) { Store.mode = Theme.MODES[it].first; Store.save(); render() }
        }

        section(col, "Gdzie są pliki")
        val f = panel(col)
        f.addView(tv("Filmy: Filmy/Konwerter (widać w Galerii)\nGIF: Obrazy/Konwerter\nDźwięk: Muzyka/Konwerter\nOryginały zostają nietknięte.", 14f), lp(top = 6))

        section(col, "Aktualizacje")
        val u = panel(col)
        u.addView(tv("Zainstalowana wersja: " + versionName(), 15f), lp(top = 6))
        if (Store.ghToken.isBlank()) {
            val e = EditText(this).apply {
                hint = "Token GitHub (ghp_…)"
                setTextColor(fg); setHintTextColor(dim)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                background = if (Store.eink) round(bg, 10, 2, fg) else round(bg, 14, 1)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            u.addView(e, lp(top = 8))
            u.addView(btn("Zapisz token", size = 14f) { Store.ghToken = e.text.toString().trim(); Store.save(); checkUpdate(true) }, lp(top = 8))
        } else {
            val r = hbox()
            r.addView(tv("Token zapisany", 14f, color = dim), lp(0, WRAP, 1f))
            r.addView(btn("Usuń", size = 13f) { Store.ghToken = ""; Store.save(); render() })
            u.addView(r, lp(top = 8))
        }
        val st = tv(updMsg, 14f, color = dim)
        updStatus = st
        if (updMsg.isNotEmpty()) u.addView(st, lp(top = 6))
        val upd = update
        if (upd != null) u.addView(btn("Pobierz i zainstaluj 1.${upd.number}", true, 14f) { installUpdate() }, lp(top = 8))
        else u.addView(btn("Sprawdź aktualizacje", size = 14f) { checkUpdate(true) }, lp(top = 8))
        u.addView(btn("Instrukcja: jak zaktualizować", size = 14f) { go("help") }, lp(top = 8))
    }

    private val UPDATE_CMD =
        "cd ~ && unzip -o \"\$(ls -t ~/storage/downloads/Konwerter*.zip | head -1)\" && cd Konwerter && git add -A && " +
            "git -c user.name=ja -c user.email=ja@x.pl commit -m aktualizacja && git push"

    private fun renderHelp() {
        val col = page()
        col.addView(roundIcon(R.drawable.ic_back_arrow) { go("settings") })
        col.addView(tv("Jak zaktualizować", 26f, true), lp(top = 10))
        val steps = listOf(
            "Pobierz Konwerter.zip z rozmowy z Claude do folderu Pobrane.",
            "Otwórz Termux, skopiuj polecenie poniżej, wklej i stuknij Enter.",
            "Po „main -> main” GitHub buduje aplikację ok. 3–5 minut.",
            "Przy zielonym ptaszku otwórz Konwerter – pojawi się „Nowa wersja”. Przy czerwonym wyślij Claude zrzut linijek z „e:”."
        )
        steps.forEachIndexed { k, s -> col.addView(tv("${k + 1}. $s", 15f), lp(top = 10)) }
        col.addView(tv(UPDATE_CMD, 13f).apply {
            typeface = Typeface.MONOSPACE
            background = if (Store.eink) round(bg, 8, 1, fg) else round(surface, 12, 1)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTextIsSelectable(true)
        }, lp(top = 12))
        col.addView(btn("Kopiuj polecenie", size = 14f) {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("cmd", UPDATE_CMD))
            toast("Skopiowano – wklej w Termuksie")
        }, lp(WRAP, WRAP, top = 6))
        col.addView(btn("Otwórz Termux", size = 14f) {
            val i = packageManager.getLaunchIntentForPackage("com.termux")
            if (i != null) startActivity(i) else toast("Termux nie jest zainstalowany")
        }, lp(WRAP, WRAP, top = 8))
        col.addView(btn("Otwórz budowanie na GitHubie", size = 14f) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${Updater.REPO}/actions"))) } catch (e: Exception) {}
        }, lp(WRAP, WRAP, top = 8))
    }

    // ------------------------------------------------------------ aktualizacje
    private fun installedVersion(): Long = try {
        packageManager.getPackageInfo(packageName, 0).longVersionCode
    } catch (e: Exception) {
        0L
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    private fun checkUpdate(manual: Boolean) {
        val token = Store.ghToken
        if (token.isBlank()) {
            if (manual) toast("Najpierw wklej token GitHub")
            return
        }
        if (manual) { updMsg = "Sprawdzam…"; render() }
        Thread {
            val res: Pair<Updater.Info?, String> = try {
                val i = Updater.check(token)
                if (i != null && i.number > installedVersion()) Pair(i, "Dostępna wersja 1.${i.number}") else Pair(null, "Masz najnowszą wersję")
            } catch (e: Exception) {
                Pair(null, "Błąd: " + (e.message ?: "brak połączenia"))
            }
            h.post {
                update = res.first
                if (manual || res.first != null) { updMsg = res.second; render() }
            }
        }.start()
    }

    private fun installUpdate() {
        val info = update ?: return
        if (!packageManager.canRequestPackageInstalls()) {
            toast("Zezwól Konwerterowi na instalowanie aplikacji, potem wróć i stuknij ponownie")
            try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) } catch (e: Exception) {}
            return
        }
        updMsg = "Pobieram… 0%"
        render()
        val file = File(cacheDir, "update.apk")
        Thread {
            try {
                Updater.download(Store.ghToken, info, file) { pr -> h.post { updMsg = "Pobieram… $pr%"; updStatus?.text = updMsg } }
                installApk(file)
                h.post { updMsg = "Potwierdź instalację"; updStatus?.text = updMsg }
            } catch (e: Exception) {
                h.post { updMsg = "Błąd: " + (e.message ?: "pobieranie nieudane"); render() }
            }
        }.start()
    }

    private fun installApk(file: File) {
        val pi = packageManager.packageInstaller
        val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        val session = pi.openSession(id)
        try {
            session.openWrite("konwerter", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val i = Intent(this, MainActivity::class.java).setAction(ACTION_INSTALL)
            val pend = PendingIntent.getActivity(this, 7, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pend.intentSender)
        } finally {
            session.close()
        }
    }

    private fun handleInstall(i: Intent) {
        if (i.action != ACTION_INSTALL) return
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> toast("Zaktualizowano")
            -999 -> {}
            else -> { updMsg = "Instalacja nieudana: " + (i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "nieznany błąd"); render() }
        }
    }
}

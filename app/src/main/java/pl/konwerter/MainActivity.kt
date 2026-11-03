package pl.konwerter

import android.Manifest
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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

class MainActivity : Ui() {
    private val REQ_PICK = 11
    private val REQ_IPTV = 12
    private val ACTION_INSTALL = "pl.konwerter.INSTALL_RESULT"

    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var mini: LinearLayout
    private lateinit var nav: LinearLayout
    private val h = Handler(Looper.getMainLooper())
    private var screen = "main"
    private var tab = "video"
    private var lastView = ""
    private var lastState = ""
    private val bars = HashMap<Job, Pair<ProgressBar, TextView>>()
    private var update: Updater.Info? = null
    private var updMsg = ""
    private var updStatus: TextView? = null

    // stan zakładek
    private var query = ""
    private var videoFolder: String? = null
    private var musicMode = "songs"
    private var musicGroup: Pair<String, List<Song>>? = null
    private var playlistOpen = -1
    private var iptvOpen = -1
    private var channels: List<Channel> = emptyList()
    private var channelGroup = ""
    private var netStack = mutableListOf<Pair<String, String>>()
    private var netItems: List<NetItem> = emptyList()
    private var netBusy = false
    private var netBrowser: NetBrowser? = null
    private var miniTitle: TextView? = null
    private var miniPlay: ImageView? = null
    private var miniBar: ProgressBar? = null
    private var miniIndex = -2

    private val engineListener: () -> Unit = {
        if (Engine.index != miniIndex || (Engine.current != null) != (miniTitle != null)) buildMini()
        else {
            miniPlay?.setImageResource(if (Engine.playing) R.drawable.ic_pause_fill else R.drawable.ic_play_fill)
            miniBar?.progress = if (Engine.length > 0) (Engine.time * 1000 / Engine.length).toInt() else 0
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Engine.init(this)
        tab = Store.lastTab
        root = vbox()
        root.fitsSystemWindows = true
        content = FrameLayout(this)
        mini = hbox()
        nav = hbox()
        root.addView(content, lp(MATCH, 0, 1f))
        root.addView(mini, lp())
        root.addView(nav, lp())
        setContentView(root)
        Engine.listeners.add(engineListener)
        askPermissions()
        handleIntent(intent)
        render()
        checkUpdate(false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        render()
    }

    override fun onResume() {
        super.onResume()
        Engine.dialogHandler = { d -> showVlcDialog(d) }
        h.post(tick)
        if (!Library.loaded && hasMediaPermission()) loadLibrary()
    }

    override fun onPause() {
        h.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        Engine.listeners.remove(engineListener)
        netBrowser?.release()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            screen == "help" -> go("settings")
            screen == "settings" -> go("main")
            tab == "video" && videoFolder != null -> { videoFolder = null; render() }
            tab == "music" && musicGroup != null -> { musicGroup = null; render() }
            tab == "lists" && playlistOpen >= 0 -> { playlistOpen = -1; render() }
            tab == "net" && iptvOpen >= 0 -> { iptvOpen = -1; render() }
            tab == "net" && netStack.isNotEmpty() -> netBack()
            else -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val state = Queue.jobs.joinToString { it.status } + Queue.running
            if (state != lastState) {
                lastState = state
                if (screen == "main" && tab == "convert") render()
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

    // ------------------------------------------------------------ uprawnienia i biblioteka
    private fun mediaPerms(): Array<String> = if (android.os.Build.VERSION.SDK_INT >= 33)
        arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun hasMediaPermission(): Boolean = if (android.os.Build.VERSION.SDK_INT >= 33)
        checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
    else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun askPermissions() {
        if (!hasMediaPermission()) requestPermissions(mediaPerms(), 5) else loadLibrary()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasMediaPermission()) loadLibrary() else render()
    }

    private fun loadLibrary() {
        Thread {
            Library.load(this)
            h.post { if (screen == "main") render() }
        }.start()
    }

    // ------------------------------------------------------------ wygląd ramy
    private fun applyWindow() {
        if (!Store.eink) Theme.apply(this, Store.theme, Store.mode)
        root.setBackgroundColor(bg)
        window.statusBarColor = bg
        window.navigationBarColor = surface
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (inv || (!Store.eink && Theme.dark)) 0 else
            (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
    }

    private fun page(): LinearLayout {
        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        val col = vbox()
        col.setPadding(dp(16), dp(12), dp(16), dp(24))
        sv.addView(col)
        content.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
        return col
    }

    private fun render() {
        val key = "$screen|$tab|$videoFolder|${musicGroup?.first}|$musicMode|$playlistOpen|$iptvOpen|${netStack.size}"
        val oldY = if (key == lastView) (content.getChildAt(0) as? ScrollView)?.scrollY ?: 0 else 0
        lastView = key
        applyWindow()
        content.removeAllViews()
        bars.clear()
        updStatus = null
        when (screen) {
            "settings" -> renderSettings()
            "help" -> renderHelp()
            else -> {
                val col = page()
                header(col)
                when (tab) {
                    "music" -> renderMusic(col)
                    "net" -> renderNet(col)
                    "lists" -> renderLists(col)
                    "convert" -> renderConvert(col)
                    else -> renderVideo(col)
                }
            }
        }
        buildMini()
        buildNav()
        if (oldY > 0) (content.getChildAt(0) as? ScrollView)?.let { sv -> sv.post { sv.scrollTo(0, oldY) } }
    }

    private fun header(col: LinearLayout) {
        val title = when (tab) {
            "music" -> "Muzyka"; "net" -> "Sieć"; "lists" -> "Playlisty"; "convert" -> "Konwerter"; else -> "Wideo"
        }
        if (Store.eink) {
            val r = hbox()
            r.addView(tv(title, 30f, true), lp(0, WRAP, 1f))
            r.addView(roundIcon(R.drawable.ic_settings) { go("settings") })
            col.addView(r)
        } else {
            val hero = hbox().apply {
                background = Theme.gradient(dp(22).toFloat())
                setPadding(dp(18), dp(14), dp(12), dp(14))
                setOnLongClickListener { Store.mode = if (Theme.dark) "light" else "dark"; Store.save(); render(); true }
            }
            val l = vbox()
            l.addView(tv("Player", 12f, true, Theme.withAlpha(Color.WHITE, 0.8f)))
            l.addView(tv(title, 28f, true, Color.WHITE))
            hero.addView(l, lp(0, WRAP, 1f))
            val gear = FrameLayout(this).apply { background = round(Theme.withAlpha(Color.WHITE, 0.22f), 20); setOnClickListener { go("settings") } }
            gear.addView(icon(R.drawable.ic_settings, 20, Color.WHITE), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            hero.addView(gear, LinearLayout.LayoutParams(dp(40), dp(40)))
            col.addView(hero, lp(top = 4))
        }
        val upd = update
        if (upd != null) {
            val box = hbox().apply { background = if (Store.eink) round(bg, 12, 2, fg) else round(surface, 18); setPadding(dp(16), dp(12), dp(12), dp(12)) }
            val st = tv(if (updMsg.startsWith("Pobieram") || updMsg.startsWith("Błąd")) updMsg else "Nowa wersja 1.${upd.number}", 15f, true)
            updStatus = st
            box.addView(st, lp(0, WRAP, 1f))
            box.addView(btn("Zainstaluj", true, 14f) { installUpdate() })
            col.addView(box, lp(top = 10))
        }
    }

    private fun buildNav() {
        nav.removeAllViews()
        nav.setBackgroundColor(surface)
        nav.setPadding(0, dp(6), 0, dp(6))
        if (screen != "main") {
            nav.visibility = View.GONE; return
        }
        nav.visibility = View.VISIBLE
        listOf(Triple("video", R.drawable.ic_video, "Wideo"), Triple("music", R.drawable.ic_music, "Muzyka"), Triple("net", R.drawable.ic_net, "Sieć"),
            Triple("lists", R.drawable.ic_playlist, "Playlisty"), Triple("convert", R.drawable.ic_convert, "Konwerter")).forEach { (k, res, label) ->
            val sel = tab == k
            val v = vbox().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(4), 0, dp(4))
                setOnClickListener {
                    if (tab == k) {
                        videoFolder = null; musicGroup = null; playlistOpen = -1; iptvOpen = -1
                    }
                    tab = k; Store.lastTab = k; Store.save(); query = ""; render()
                }
            }
            val ic = FrameLayout(this).apply { if (sel) background = round(if (Store.eink) fg else Theme.soft, 14) }
            ic.addView(icon(res, 22, if (sel) (if (Store.eink) bg else accent) else dim), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            v.addView(ic, LinearLayout.LayoutParams(dp(56), dp(30)))
            v.addView(tv(label, 11f, sel, if (sel) fg else dim), lp(WRAP, WRAP, top = 2))
            nav.addView(v, lp(0, WRAP, 1f))
        }
    }

    private fun buildMini() {
        mini.removeAllViews()
        miniTitle = null; miniPlay = null; miniBar = null
        val t = Engine.current
        miniIndex = Engine.index
        if (t == null || screen != "main") {
            mini.visibility = View.GONE; return
        }
        mini.visibility = View.VISIBLE
        mini.orientation = LinearLayout.VERTICAL
        mini.background = if (Store.eink) round(bg, 0, 2, fg) else GradientDrawable().apply { setColor(Theme.soft) }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = if (Engine.length > 0) (Engine.time * 1000 / Engine.length).toInt() else 0
            progressTintList = ColorStateList.valueOf(accent)
        }
        mini.addView(bar, LinearLayout.LayoutParams(MATCH, dp(3)))
        miniBar = bar
        val r = hbox().apply { setPadding(dp(12), dp(8), dp(8), dp(8)); setOnClickListener { Engine.openPlayer(this@MainActivity) } }
        val art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = round(surface, 10); clipToOutline = true }
        art.setImageResource(if (t.video) R.drawable.ic_video else R.drawable.ic_music)
        art.setColorFilter(dim)
        Library.thumb(this, t.art, 120, 120) { bm -> if (bm != null) { art.clearColorFilter(); art.setImageBitmap(bm) } }
        r.addView(art, LinearLayout.LayoutParams(dp(44), dp(44)))
        val l = vbox()
        val title = tv(t.title, 14f, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        miniTitle = title
        l.addView(title)
        l.addView(tv(t.sub.ifEmpty { if (t.video) "Wideo" else "Audio" }, 12f, color = dim).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        r.addView(l, lp(0, WRAP, 1f).apply { leftMargin = dp(10) })
        val play = icon(if (Engine.playing) R.drawable.ic_pause_fill else R.drawable.ic_play_fill, 26, fg) { Engine.toggle() }
        play.setPadding(dp(4), dp(4), dp(4), dp(4))
        miniPlay = play
        r.addView(play, LinearLayout.LayoutParams(dp(42), dp(42)))
        r.addView(icon(R.drawable.ic_next, 24, fg) { Engine.next() }.apply { setPadding(dp(4), dp(4), dp(4), dp(4)) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        r.addView(icon(R.drawable.ic_close, 22, dim) { Engine.stop(); Engine.queue.clear(); Engine.index = -1; buildMini() }.apply { setPadding(dp(4), dp(4), dp(4), dp(4)) }, LinearLayout.LayoutParams(dp(36), dp(36)))
        mini.addView(r)
    }

    private fun searchBox(col: LinearLayout, hint: String, onChange: () -> Unit) {
        val e = EditText(this).apply {
            setText(query)
            this.hint = hint
            setSingleLine(true)
            setTextColor(fg); setHintTextColor(dim)
            background = if (Store.eink) round(bg, 10, 2, fg) else round(surface, 14, 1)
            setPadding(dp(14), dp(11), dp(14), dp(11))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    query = s?.toString() ?: ""; onChange()
                }
            })
        }
        col.addView(e, lp(top = 12))
    }

    private fun needPermission(col: LinearLayout): Boolean {
        if (hasMediaPermission()) return false
        val p = panel(col, 14)
        p.addView(tv("Żeby pokazać filmy i muzykę z telefonu, zezwól na dostęp do multimediów.", 14f), lp(top = 6))
        p.addView(primary("Zezwól") { requestPermissions(mediaPerms(), 5) }, lp(top = 10))
        p.addView(btn("Otwórz ustawienia aplikacji", size = 13f) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }, lp(top = 8))
        return true
    }

    private fun thumbView(content: String, w: Int, hh: Int, fallback: Int): ImageView {
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = round(if (Store.eink) bg else Theme.soft, 10, if (Store.eink) 1 else 0, fg)
            clipToOutline = true
            setImageResource(fallback)
            setColorFilter(dim)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        Library.thumb(this, content, w * 2, hh * 2) { bm -> if (bm != null) { iv.clearColorFilter(); iv.setPadding(0, 0, 0, 0); iv.setImageBitmap(bm) } }
        return iv
    }

    private fun playAndOpen(list: List<Track>, i: Int, at: Long = -1) {
        Engine.play(list, i, at)
        if (list.getOrNull(i)?.video == true) Engine.openPlayer(this)
    }

    // ------------------------------------------------------------ Wideo
    private fun videoRow(col: LinearLayout, v: Vid, list: List<Track>, i: Int) {
        val r = hbox().apply {
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { playAndOpen(list, i) }
            setOnLongClickListener { itemSheet(v.track(), v.content); true }
        }
        val box = FrameLayout(this)
        box.addView(thumbView(v.content.toString(), 128, 72, R.drawable.ic_video), FrameLayout.LayoutParams(dp(128), dp(72)))
        val pos = Store.positions[v.track().uri] ?: 0L
        if (pos > 0 && v.duration > 0) {
            val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000; progress = (pos * 1000 / v.duration).toInt(); progressTintList = ColorStateList.valueOf(accent)
            }
            box.addView(pb, FrameLayout.LayoutParams(dp(128), dp(4), Gravity.BOTTOM))
        }
        box.addView(tv(Library.time(v.duration), 11f, true, Color.WHITE).apply {
            background = round(0xAA000000.toInt(), 6); setPadding(dp(5), dp(1), dp(5), dp(1))
        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.BOTTOM).apply { rightMargin = dp(4); bottomMargin = dp(6) })
        r.addView(box, LinearLayout.LayoutParams(dp(128), dp(72)))
        val l = vbox()
        l.addView(tv(v.title, 14f, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        l.addView(tv(listOfNotNull(if (v.h > 0) "${minOf(v.w, v.h)}p" else null, Queue.sizeText(v.size)).joinToString(" · "), 12f, color = dim))
        r.addView(l, lp(0, WRAP, 1f).apply { leftMargin = dp(12) })
        col.addView(r)
    }

    private fun renderVideo(col: LinearLayout) {
        if (needPermission(col)) return
        val cont = Store.history.filter { it.track.video && it.pos > 0 && it.len > 0 && it.pos < it.len - 15_000 }.take(12)
        if (cont.isNotEmpty() && videoFolder == null && query.isEmpty()) {
            section(col, "Kontynuuj oglądanie", 16)
            val r = hbox().apply { gravity = Gravity.TOP }
            cont.forEach { hi ->
                val c = vbox().apply { setOnClickListener { playAndOpen(listOf(hi.track), 0, hi.pos) } }
                val box = FrameLayout(this)
                box.addView(thumbView(hi.track.art, 160, 90, R.drawable.ic_video), FrameLayout.LayoutParams(dp(160), dp(90)))
                box.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1000; progress = (hi.pos * 1000 / hi.len).toInt(); progressTintList = ColorStateList.valueOf(accent)
                }, FrameLayout.LayoutParams(dp(160), dp(4), Gravity.BOTTOM))
                c.addView(box, LinearLayout.LayoutParams(dp(160), dp(90)))
                c.addView(tv(hi.track.title, 12f, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(dp(160), WRAP).apply { topMargin = dp(4) })
                c.addView(tv("zostało " + Library.time(hi.len - hi.pos), 11f, color = dim))
                r.addView(c, lp(WRAP, WRAP).apply { rightMargin = dp(10) })
            }
            col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(r) }, lp(top = 8))
        }
        val folder = videoFolder
        if (folder == null) {
            val list = vbox()
            searchBox(col, "Szukaj filmu") { fillVideos(list) }
            col.addView(list)
            fillVideos(list)
        } else {
            val back = hbox().apply { setPadding(0, dp(12), 0, dp(4)); setOnClickListener { videoFolder = null; render() } }
            back.addView(icon(R.drawable.ic_back_arrow, 22, accent))
            back.addView(tv(folder, 18f, true), lp(0, WRAP, 1f).apply { leftMargin = dp(8) })
            col.addView(back)
            val vids = Library.videos.filter { it.folder == folder }.let { if (Store.videoSort == "name") it.sortedBy { v -> v.title.lowercase() } else it }
            seg(col, "", listOf("Najnowsze" to (Store.videoSort == "date"), "Nazwa" to (Store.videoSort == "name"))) {
                Store.videoSort = if (it == 0) "date" else "name"; Store.save(); render()
            }
            val tracks = vids.map { it.track() }
            col.addView(btn("▶  Odtwórz wszystkie (${vids.size})", size = 14f) { playAndOpen(tracks, 0) }, lp(top = 8))
            vids.forEachIndexed { i, v -> videoRow(col, v, tracks, i) }
        }
    }

    private fun fillVideos(list: LinearLayout) {
        list.removeAllViews()
        if (!Library.loaded) {
            list.addView(tv("Wczytuję bibliotekę…", 14f, color = dim), lp(top = 16)); return
        }
        if (query.isNotBlank()) {
            val q = query.lowercase()
            val hits = Library.videos.filter { q in it.title.lowercase() }.take(100)
            val tracks = hits.map { it.track() }
            hits.forEachIndexed { i, v -> videoRow(list, v, tracks, i) }
            if (hits.isEmpty()) list.addView(tv("Nic nie znaleziono", 14f, color = dim), lp(top = 16))
            return
        }
        section(list, "Foldery (${Library.videos.size} filmów)", 18)
        if (Library.videos.isEmpty()) list.addView(tv("Brak filmów w telefonie.", 14f, color = dim), lp(top = 10))
        Library.videoFolders().forEach { (name, vids) ->
            val r = hbox().apply { setPadding(0, dp(8), 0, dp(8)); setOnClickListener { videoFolder = name; render() } }
            r.addView(thumbView(vids.first().content.toString(), 96, 60, R.drawable.ic_folder), LinearLayout.LayoutParams(dp(96), dp(60)))
            val l = vbox()
            l.addView(tv(name, 15f, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            l.addView(tv("${vids.size} " + if (vids.size == 1) "film" else "filmów", 12f, color = dim))
            r.addView(l, lp(0, WRAP, 1f).apply { leftMargin = dp(12) })
            list.addView(r)
        }
    }

    // ------------------------------------------------------------ Muzyka
    private fun songRow(col: LinearLayout, s: Song, list: List<Track>, i: Int) {
        val r = hbox().apply {
            setPadding(0, dp(7), 0, dp(7))
            setOnClickListener { Engine.play(list, i) }
            setOnLongClickListener { itemSheet(s.track(), s.content); true }
        }
        r.addView(thumbView(s.content.toString(), 46, 46, R.drawable.ic_music), LinearLayout.LayoutParams(dp(46), dp(46)))
        val l = vbox()
        val cur = Engine.current?.uri == s.track().uri
        l.addView(tv(s.title, 15f, true, if (cur) accent else fg).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        l.addView(tv(s.artist + " · " + Library.time(s.duration), 12f, color = dim).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        r.addView(l, lp(0, WRAP, 1f).apply { leftMargin = dp(12) })
        col.addView(r)
    }

    private fun renderMusic(col: LinearLayout) {
        if (needPermission(col)) return
        val group = musicGroup
        if (group != null) {
            val back = hbox().apply { setPadding(0, dp(12), 0, dp(4)); setOnClickListener { musicGroup = null; render() } }
            back.addView(icon(R.drawable.ic_back_arrow, 22, accent))
            back.addView(tv(group.first, 18f, true).apply { maxLines = 2 }, lp(0, WRAP, 1f).apply { leftMargin = dp(8) })
            col.addView(back)
            val tracks = group.second.map { it.track() }
            playButtons(col, tracks)
            group.second.forEachIndexed { i, s -> songRow(col, s, tracks, i) }
            return
        }
        seg(col, "", listOf("Utwory" to (musicMode == "songs"), "Wykonawcy" to (musicMode == "artists"), "Albumy" to (musicMode == "albums"), "Foldery" to (musicMode == "folders"))) {
            musicMode = listOf("songs", "artists", "albums", "folders")[it]; query = ""; render()
        }
        val list = vbox()
        searchBox(col, "Szukaj utworu, wykonawcy, albumu") { fillMusic(list) }
        col.addView(list)
        fillMusic(list)
    }

    private fun playButtons(col: LinearLayout, tracks: List<Track>) {
        val r = hbox()
        r.addView(btn("▶  Odtwórz", true, 14f) { Store.shuffle = false; Engine.play(tracks, 0) }, lp(0, WRAP, 1f).apply { rightMargin = dp(6) })
        r.addView(btn("Losowo", size = 14f) { Store.shuffle = true; Store.save(); Engine.play(tracks, (tracks.indices).random()) }, lp(0, WRAP, 1f))
        col.addView(r, lp(top = 10))
    }

    private fun fillMusic(list: LinearLayout) {
        list.removeAllViews()
        if (!Library.loaded) {
            list.addView(tv("Wczytuję bibliotekę…", 14f, color = dim), lp(top = 16)); return
        }
        val q = query.lowercase().trim()
        when (musicMode) {
            "songs" -> {
                val songs = Library.songs.filter { q.isEmpty() || q in it.title.lowercase() || q in it.artist.lowercase() || q in it.album.lowercase() }
                val tracks = songs.map { it.track() }
                if (songs.isNotEmpty()) playButtons(list, tracks)
                list.addView(tv("${songs.size} utworów", 12f, color = dim), lp(top = 8))
                songs.take(400).forEachIndexed { i, s -> songRow(list, s, tracks, i) }
                if (songs.size > 400) list.addView(tv("…i więcej – zawęź wyszukiwanie", 12f, color = dim), lp(top = 8))
            }
            else -> {
                val groups = when (musicMode) {
                    "artists" -> Library.artists()
                    "albums" -> Library.albums()
                    else -> Library.songFolders()
                }.filter { q.isEmpty() || q in it.first.lowercase() }
                groups.forEach { g ->
                    val r = hbox().apply { setPadding(0, dp(8), 0, dp(8)); setOnClickListener { musicGroup = g; render() } }
                    r.addView(thumbView(g.second.first().content.toString(), 52, 52, if (musicMode == "folders") R.drawable.ic_folder else R.drawable.ic_music), LinearLayout.LayoutParams(dp(52), dp(52)))
                    val l = vbox()
                    l.addView(tv(g.first, 15f, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                    l.addView(tv("${g.second.size} utworów" + if (musicMode == "albums") " · " + g.second.first().artist else "", 12f, color = dim).apply { maxLines = 1 })
                    r.addView(l, lp(0, WRAP, 1f).apply { leftMargin = dp(12) })
                    list.addView(r)
                }
            }
        }
    }

    /** Menu pozycji: odtwórz, dodaj do kolejki/playlisty, konwertuj. */
    private fun itemSheet(t: Track, content: Uri) {
        sheet(t.title) { col, d ->
            col.addView(btn("▶  Odtwórz") { d.dismiss(); playAndOpen(listOf(t), 0) }, lp(top = 8))
            col.addView(btn("Dodaj do kolejki") { d.dismiss(); Engine.enqueue(t); toast("Dodano do kolejki") }, lp(top = 8))
            col.addView(btn("Dodaj do playlisty") { d.dismiss(); playlistPicker(t) }, lp(top = 8))
            col.addView(btn("Konwertuj") {
                d.dismiss(); tab = "convert"; Store.lastTab = tab; addFiles(listOf(content))
            }, lp(top = 8))
        }
    }

    private fun playlistPicker(t: Track) {
        sheet("Dodaj do playlisty") { col, d ->
            Store.playlists.forEach { p -> col.addView(btn(p.name + " (${p.items.size})") { p.items.add(t); Store.save(); d.dismiss(); toast("Dodano") }, lp(top = 6)) }
            val e = EditText(this).apply { hint = "Nazwa nowej playlisty"; setTextColor(fg); setHintTextColor(dim) }
            col.addView(e, lp(top = 10))
            col.addView(primary("Utwórz i dodaj") {
                val name = e.text.toString().trim().ifEmpty { "Playlista ${Store.playlists.size + 1}" }
                Store.playlists.add(Playlist(name, mutableListOf(t))); Store.save(); d.dismiss(); toast("Utworzono „$name”")
            }, lp(top = 8))
        }
    }

    // ------------------------------------------------------------ Sieć
    private fun videoLike(u: String) = !Regex("(?i)\\.(mp3|flac|m4a|aac|ogg|opus|wav|wma|ape|alac)(\\?|$)").containsMatchIn(u)

    private fun netBack() {
        if (netStack.isNotEmpty()) netStack.removeAt(netStack.size - 1)
        val top = netStack.lastOrNull()
        netBusy = true
        netItems = emptyList()
        if (top == null) netBrowser?.discover() else netBrowser?.browse(top.second)
        render()
    }

    private fun netBrowserInst(): NetBrowser = netBrowser ?: NetBrowser { items, done ->
        netItems = items; if (done) netBusy = false
        if (screen == "main" && tab == "net") render()
    }.also { netBrowser = it }

    private fun renderNet(col: LinearLayout) {
        val io = iptvOpen
        if (io >= 0) {
            renderIptv(col, io); return
        }
        section(col, "Otwórz adres", 16)
        val p = panel(col)
        val e = EditText(this).apply {
            hint = "https://…, rtsp://…, smb://…"
            setSingleLine(true); setTextColor(fg); setHintTextColor(dim)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        p.addView(e, lp(top = 4))
        p.addView(primary("▶  Odtwórz") {
            val u = e.text.toString().trim()
            if (u.isEmpty()) return@primary
            playAndOpen(listOf(Track(u, u.substringAfterLast('/').ifEmpty { u }, "Strumień", videoLike(u))), 0)
        }, lp(top = 8))
        p.addView(tv("Filmy i radio z internetu, kamery (rtsp), YouTube nie.", 12f, color = dim), lp(top = 6))

        section(col, "Telewizja IPTV")
        val ip = panel(col)
        if (Store.iptv.isEmpty()) ip.addView(tv("Dodaj listę kanałów M3U od swojego operatora IPTV (adres albo plik).", 13f, color = dim), lp(top = 6))
        Store.iptv.forEachIndexed { k, l ->
            row(ip, l.name, l.url.take(50), icon(R.drawable.ic_trash, 20, dim) { Store.iptv.removeAt(k); Store.save(); render() }) {
                iptvOpen = k; channels = emptyList(); channelGroup = ""; loadIptv(l); render()
            }
        }
        val r = hbox()
        r.addView(btn("Dodaj z adresu", size = 13f) { addIptvSheet() }, lp(0, WRAP, 1f).apply { rightMargin = dp(6) })
        r.addView(btn("Z pliku", size = 13f) {
            try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_IPTV) } catch (x: Exception) {}
        }, lp(0, WRAP, 1f))
        ip.addView(r, lp(top = 8))

        section(col, "Udziały sieciowe (NAS, komputer, DLNA)")
        val np = panel(col)
        val nb = netBrowserInst()
        if (netStack.isEmpty() && netItems.isEmpty() && !netBusy) {
            np.addView(tv("Wyszukuje serwery multimediów (DLNA/UPnP) i udostępnione foldery (SMB) w Twojej sieci Wi-Fi.", 13f, color = dim), lp(top = 6))
            np.addView(primary("Szukaj w sieci") { netBusy = true; nb.discover(); render() }, lp(top = 8))
        } else {
            val head = hbox()
            if (netStack.isNotEmpty()) head.addView(icon(R.drawable.ic_back_arrow, 22, accent) { netBack() })
            head.addView(tv(netStack.lastOrNull()?.first ?: "Znalezione w sieci", 15f, true), lp(0, WRAP, 1f).apply { leftMargin = dp(8) })
            if (netStack.isNotEmpty()) head.addView(btn("☆ Zapisz", size = 12f) {
                netStack.lastOrNull()?.let { Store.places.add(NetPlace(it.first, it.second)); Store.save(); toast("Zapisano") }
            })
            np.addView(head, lp(top = 4))
            if (netBusy) np.addView(tv("Szukam…", 13f, color = dim), lp(top = 6))
            val files = netItems.filter { !it.dir }.map { Track(it.uri, it.title, netStack.lastOrNull()?.first ?: "", videoLike(it.uri)) }
            netItems.forEach { it2 ->
                row(np, (if (it2.dir) "📁 " else "") + it2.title, if (it2.dir) "" else it2.uri.substringAfterLast('.').take(5).uppercase()) {
                    if (it2.dir) {
                        netStack.add(Pair(it2.title, it2.uri)); netBusy = true; netItems = emptyList(); nb.browse(it2.uri); render()
                    } else {
                        val i = files.indexOfFirst { f -> f.uri == it2.uri }
                        playAndOpen(files, i.coerceAtLeast(0))
                    }
                }
            }
            if (!netBusy && netItems.isEmpty()) np.addView(tv("Pusto. Jeśli to NAS z hasłem, pojawi się okno logowania.", 13f, color = dim), lp(top = 6))
            np.addView(btn("Odśwież", size = 13f) { netBusy = true; netItems = emptyList(); netStack.lastOrNull()?.let { nb.browse(it.second) } ?: nb.discover(); render() }, lp(top = 8))
        }
        if (Store.places.isNotEmpty()) {
            np.addView(tv("Zapisane miejsca", 13f, true, dim), lp(top = 12))
            Store.places.forEachIndexed { k, pl ->
                row(np, "★ " + pl.name, pl.uri, icon(R.drawable.ic_trash, 20, dim) { Store.places.removeAt(k); Store.save(); render() }) {
                    netStack.clear(); netStack.add(Pair(pl.name, pl.uri)); netBusy = true; netItems = emptyList(); nb.browse(pl.uri); render()
                }
            }
        }
        np.addView(btn("Dodaj udział ręcznie (smb://…)", size = 13f) { addPlaceSheet() }, lp(top = 8))

        section(col, "Telewizor")
        val tp = panel(col)
        tp.addView(tv("Podczas oglądania stuknij ikonę 📺 u góry odtwarzacza i wybierz Chromecast lub Google TV w tej samej sieci Wi-Fi.", 13f, color = dim), lp(top = 6))
    }

    private fun addPlaceSheet() {
        sheet("Udział sieciowy") { col, d ->
            val n = EditText(this).apply { hint = "Nazwa, np. NAS"; setTextColor(fg); setHintTextColor(dim) }
            val u = EditText(this).apply { setText("smb://"); hint = "smb://192.168.1.10/filmy"; setTextColor(fg); setHintTextColor(dim) }
            col.addView(n, lp(top = 8)); col.addView(u, lp(top = 6))
            col.addView(tv("Login i hasło aplikacja zapyta przy pierwszym wejściu.", 12f, color = dim), lp(top = 6))
            col.addView(primary("Zapisz") {
                val uri = u.text.toString().trim()
                if (uri.length > 6) {
                    Store.places.add(NetPlace(n.text.toString().ifBlank { uri.substringAfter("://") }, uri)); Store.save()
                }
                d.dismiss(); render()
            }, lp(top = 10))
        }
    }

    private fun addIptvSheet() {
        sheet("Lista IPTV") { col, d ->
            val n = EditText(this).apply { hint = "Nazwa, np. Telewizja"; setTextColor(fg); setHintTextColor(dim) }
            val u = EditText(this).apply { hint = "Adres listy M3U (http…)"; setTextColor(fg); setHintTextColor(dim); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
            col.addView(n, lp(top = 8)); col.addView(u, lp(top = 6))
            col.addView(primary("Dodaj") {
                val url = u.text.toString().trim()
                if (url.startsWith("http")) {
                    Store.iptv.add(Iptv(n.text.toString().ifBlank { "IPTV" }, url)); Store.save()
                }
                d.dismiss(); render()
            }, lp(top = 10))
        }
    }

    private fun loadIptv(l: Iptv) {
        Thread {
            val list = try {
                IptvParser.parse(IptvParser.fetch(this, l.url))
            } catch (e: Exception) {
                h.post { toast("Nie mogę pobrać listy: ${e.message ?: ""}") }
                emptyList()
            }
            h.post { channels = list; if (tab == "net") render() }
        }.start()
    }

    private fun renderIptv(col: LinearLayout, k: Int) {
        val l = Store.iptv.getOrNull(k) ?: run { iptvOpen = -1; return }
        val back = hbox().apply { setPadding(0, dp(12), 0, dp(4)); setOnClickListener { iptvOpen = -1; render() } }
        back.addView(icon(R.drawable.ic_back_arrow, 22, accent))
        back.addView(tv(l.name, 18f, true), lp(0, WRAP, 1f).apply { leftMargin = dp(8) })
        col.addView(back)
        if (channels.isEmpty()) {
            col.addView(tv("Wczytuję kanały…", 14f, color = dim), lp(top = 12)); return
        }
        val groups = channels.map { it.group }.distinct()
        if (groups.size > 1) {
            val r = hbox()
            (listOf("") + groups).forEach { g -> r.addView(btn(g.ifEmpty { "Wszystkie" }, channelGroup == g, 13f) { channelGroup = g; render() }, lp(WRAP, WRAP).apply { rightMargin = dp(6) }) }
            col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(r) }, lp(top = 8))
        }
        val list = vbox()
        searchBox(col, "Szukaj kanału") {
            list.removeAllViews(); fillChannels(list)
        }
        col.addView(list)
        fillChannels(list)
    }

    private fun fillChannels(list: LinearLayout) {
        val q = query.lowercase()
        val shown = channels.filter { (channelGroup.isEmpty() || it.group == channelGroup) && (q.isEmpty() || q in it.name.lowercase()) }
        val tracks = shown.map { Track(it.url, it.name, it.group, true, it.logo) }
        shown.take(500).forEachIndexed { i, c -> row(list, c.name, c.group) { playAndOpen(tracks, i) } }
    }

    // ------------------------------------------------------------ Playlisty
    private fun renderLists(col: LinearLayout) {
        val po = playlistOpen
        if (po >= 0) {
            val items: MutableList<Track>
            val name: String
            if (po == 9999) {
                items = Store.history.map { it.track }.toMutableList(); name = "Historia"
            } else {
                val p = Store.playlists.getOrNull(po) ?: run { playlistOpen = -1; render(); return }
                items = p.items; name = p.name
            }
            val back = hbox().apply { setPadding(0, dp(12), 0, dp(4)); setOnClickListener { playlistOpen = -1; render() } }
            back.addView(icon(R.drawable.ic_back_arrow, 22, accent))
            back.addView(tv(name, 18f, true), lp(0, WRAP, 1f).apply { leftMargin = dp(8) })
            col.addView(back)
            if (items.isNotEmpty()) playButtons(col, items.toList())
            if (po != 9999) {
                val r = hbox()
                r.addView(btn("Zmień nazwę", size = 13f) { renameSheet(po) }, lp(0, WRAP, 1f).apply { rightMargin = dp(6) })
                r.addView(btn("Usuń playlistę", size = 13f) { Store.playlists.removeAt(po); Store.save(); playlistOpen = -1; render() }, lp(0, WRAP, 1f))
                col.addView(r, lp(top = 8))
            } else col.addView(btn("Wyczyść historię", size = 13f) { Store.history.clear(); Store.save(); render() }, lp(top = 8))
            val list = items.toList()
            list.forEachIndexed { i, t ->
                row(col, t.title, t.sub.ifEmpty { if (t.video) "Wideo" else "Audio" },
                    if (po == 9999) null else icon(R.drawable.ic_close, 20, dim) { items.remove(t); Store.save(); render() }) { playAndOpen(list, i) }
            }
            if (list.isEmpty()) col.addView(tv("Pusto. Dodawaj z menu (przytrzymaj film lub utwór) albo z odtwarzacza.", 14f, color = dim), lp(top = 12))
            return
        }
        val p = panel(col, 14)
        row(p, "🕘 Historia", "${Store.history.size} pozycji") { playlistOpen = 9999; render() }
        Store.playlists.forEachIndexed { k, pl -> row(p, pl.name, "${pl.items.size} pozycji") { playlistOpen = k; render() } }
        col.addView(primary("＋  Nowa playlista") {
            sheet("Nowa playlista") { c, d ->
                val e = EditText(this).apply { hint = "Nazwa"; setTextColor(fg); setHintTextColor(dim) }
                c.addView(e, lp(top = 8))
                c.addView(primary("Utwórz") {
                    Store.playlists.add(Playlist(e.text.toString().ifBlank { "Playlista ${Store.playlists.size + 1}" })); Store.save(); d.dismiss(); render()
                }, lp(top = 10))
            }
        }, lp(top = 12))
        col.addView(tv("Utwory i filmy dodasz, przytrzymując je w bibliotece albo z menu ⋯ w odtwarzaczu.", 12f, color = dim), lp(top = 8))
    }

    private fun renameSheet(k: Int) {
        val p = Store.playlists.getOrNull(k) ?: return
        sheet("Nazwa playlisty") { c, d ->
            val e = EditText(this).apply { setText(p.name); setTextColor(fg) }
            c.addView(e, lp(top = 8))
            c.addView(primary("Zapisz") { p.name = e.text.toString().ifBlank { p.name }; Store.save(); d.dismiss(); render() }, lp(top = 10))
        }
    }

    // ------------------------------------------------------------ Konwerter
    private fun pick() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            startActivityForResult(i, REQ_PICK)
        } catch (e: Exception) {
            toast("Nie mogę otworzyć wyboru plików")
        }
    }

    private fun renderConvert(col: LinearLayout) {
        col.addView(primary("＋  Wybierz pliki wideo lub audio") { pick() }, lp(top = 14))
        col.addView(tv("Możesz też w Galerii lub Moich plikach wybrać plik → Udostępnij → Konwertuj.", 12f, color = dim).apply { gravity = Gravity.CENTER }, lp(top = 6))

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
            "mp4aac" -> "Obraz bez zmian, dźwięk zamieniony na AAC (silnik VLC). Dla MKV/AVI z dźwiękiem AC3, DTS – wtedy grają wszędzie, także w Galerii i na TV."
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
        p.addView(tv("Otwiera też MKV, AVI, WebM, TS, FLV, WMV i inne. Zapis do MKV/AVI/WebM nie jest dostępny – MP4 odtworzy się wszędzie tam, gdzie one.", 12f, color = dim), lp(top = 12))

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

    // ------------------------------------------------------------ pliki przychodzące
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
        }
        if (uris.isNotEmpty()) {
            tab = "convert"; Store.lastTab = tab
            addFiles(uris)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_PICK -> {
                val uris = ArrayList<Uri>()
                data.clipData?.let { c -> for (k in 0 until c.itemCount) uris.add(c.getItemAt(k).uri) }
                if (uris.isEmpty()) data.data?.let { uris.add(it) }
                if (uris.isNotEmpty()) addFiles(uris)
            }
            REQ_IPTV -> {
                val u = data.data ?: return
                try {
                    contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                }
                Store.iptv.add(Iptv("Lista z pliku", u.toString()))
                Store.save()
                render()
            }
        }
    }

    // ------------------------------------------------------------ ustawienia
    private fun renderSettings() {
        val col = page()
        col.addView(roundIcon(R.drawable.ic_back_arrow) { go("main") })
        col.addView(tv("Ustawienia", 30f, true), lp(top = 10))

        section(col, "Wygląd")
        var p = panel(col)
        seg(p, "Styl", listOf("Kolorowy" to !Store.eink, "E-ink (czarno-biały)" to Store.eink)) { Store.eink = it == 1; Store.save(); render() }
        if (Store.eink) seg(p, "Kolory e-ink", listOf("Czarne na białym" to !Store.invert, "Białe na czarnym" to Store.invert)) { Store.invert = it == 1; Store.save(); render() }
        else {
            p.addView(tv("Motyw kolorów", 15f), lp(top = 12))
            val items = Theme.PRESETS.filter { it.id != "auto" || Theme.hasWallpaperColors() }.map { pr -> swatch(pr, Store.theme == pr.id) { Store.theme = pr.id; Store.save(); render() } }
            for (start in items.indices step 4) {
                val r = hbox()
                for (k in 0 until 4) r.addView(items.getOrNull(start + k) ?: View(this), lp(0, WRAP, 1f))
                p.addView(r, lp(top = 4))
            }
            seg(p, "Tryb", Theme.MODES.map { (id, label) -> label to (Store.mode == id) }) { Store.mode = Theme.MODES[it].first; Store.save(); render() }
        }

        section(col, "Odtwarzanie")
        p = panel(col)
        seg(p, "Wznawiaj od miejsca, w którym skończyłeś", listOf("Tak" to Store.resume, "Nie" to !Store.resume)) { Store.resume = it == 0; Store.save(); render() }
        seg(p, "Pływające okienko po wyjściu z filmu", listOf("Tak" to Store.autoPip, "Nie" to !Store.autoPip)) { Store.autoPip = it == 0; Store.save(); render() }
        seg(p, "Dźwięk filmu gra w tle", listOf("Tak" to Store.bgAudio, "Nie" to !Store.bgAudio)) { Store.bgAudio = it == 0; Store.save(); render() }
        seg(p, "Skok (podwójne stuknięcie)", listOf(5, 10, 15, 30).map { "$it s" to (Store.seekStep == it) }) { Store.seekStep = listOf(5, 10, 15, 30)[it]; Store.save(); render() }
        seg(p, "Dekodowanie sprzętowe", listOf("Tak (oszczędza baterię)" to Store.hw, "Nie (gdy obraz się psuje)" to !Store.hw)) { Store.hw = it == 0; Store.save(); render() }

        section(col, "Napisy")
        p = panel(col)
        seg(p, "Kodowanie znaków (polskie litery)", listOf("Windows-1250" to (Store.subEnc == "Windows-1250"), "UTF-8" to (Store.subEnc == "UTF-8"), "ISO-8859-2" to (Store.subEnc == "ISO-8859-2"))) {
            Store.subEnc = listOf("Windows-1250", "UTF-8", "ISO-8859-2")[it]; Store.save(); render()
        }
        seg(p, "Wielkość", listOf("Mała" to (Store.subSize == 20), "Normalna" to (Store.subSize == 16), "Duża" to (Store.subSize == 12), "Bardzo duża" to (Store.subSize == 8))) {
            Store.subSize = listOf(20, 16, 12, 8)[it]; Store.save(); render()
        }
        p.addView(tv("Zmiana kodowania i wielkości działa po ponownym uruchomieniu aplikacji.", 12f, color = dim), lp(top = 6))
        p.addView(tv("Pobieranie napisów (OpenSubtitles.com)", 15f, true), lp(top = 14))
        p.addView(tv("Załóż darmowe konto, w profilu → API Consumers utwórz klucz. Wpisz klucz, login i hasło.", 12f, color = dim), lp(top = 4))
        fun field(hint: String, value: String, pass: Boolean = false, set: (String) -> Unit) {
            val e = EditText(this).apply {
                setText(value); this.hint = hint; setSingleLine(true); setTextColor(fg); setHintTextColor(dim)
                if (pass) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setOnFocusChangeListener { _, has -> if (!has) { set(text.toString().trim()); Store.save() } }
            }
            p.addView(e, lp(top = 6))
        }
        field("Klucz API", Store.osKey) { Store.osKey = it }
        field("Login", Store.osUser) { Store.osUser = it }
        field("Hasło", Store.osPass, true) { Store.osPass = it }
        field("Języki (np. pl,en)", Store.subLang) { Store.subLang = it.ifBlank { "pl,en" } }

        section(col, "Android Auto")
        p = panel(col)
        p.addView(tv("Muzyka z telefonu jest dostępna w Android Auto: ostatnio słuchane, utwory, albumy, wykonawcy i playlisty. Aplikacje spoza Sklepu Play trzeba raz odblokować: Android Auto → Ustawienia → stuknij 10× „Wersja” → menu ⋮ → Ustawienia programisty → zaznacz „Nieznane źródła”.", 13f, color = dim), lp(top = 6))
        p.addView(btn("Otwórz ustawienia Android Auto", size = 13f) {
            try {
                startActivity(Intent().setClassName("com.google.android.projection.gearhead", "com.google.android.projection.gearhead.companion.settings.SettingsActivity"))
            } catch (e: Exception) {
                toast("Otwórz Android Auto w ustawieniach telefonu")
            }
        }, lp(top = 8))

        section(col, "Gdzie są pliki")
        p = panel(col)
        p.addView(tv("Konwerter: Filmy/Konwerter, Muzyka/Konwerter, Obrazy/Konwerter\nZapisane klatki: Obrazy/Player\nOryginały zostają nietknięte.", 14f), lp(top = 6))

        section(col, "Aktualizacje")
        val u = panel(col)
        u.addView(tv("Zainstalowana wersja: " + versionName(), 15f), lp(top = 6))
        if (Store.ghToken.isBlank()) {
            val e = EditText(this).apply {
                hint = "Token GitHub (ghp_…)"
                setTextColor(fg); setHintTextColor(dim)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
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
        col.addView(tv("Player · silnik VLC (LGPL) · " + versionName(), 12f, color = dim).apply { gravity = Gravity.CENTER }, lp(top = 18))
    }

    // ------------------------------------------------------------ przeniesione z Konwertera
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

    private fun installedVersion(): Long = try {
        packageManager.getPackageInfo(packageName, 0).longVersionCode
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
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
            toast("Zezwól aplikacji Player na instalowanie aplikacji, potem wróć i stuknij ponownie")
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

    private val UPDATE_CMD =
        "cd ~ && unzip -o \"\$(ls -t ~/storage/downloads/Konwerter*.zip | head -1)\" && cd Konwerter && git add -A && " +
            "git -c user.name=ja -c user.email=ja@x.pl commit -m aktualizacja && git push"

    private fun renderHelp() {
        val col = page()
        col.addView(roundIcon(R.drawable.ic_back_arrow) { go("settings") })
        col.addView(tv("Jak zaktualizować", 26f, true), lp(top = 10))
        val steps = listOf(
            "Pobierz Konwerter.zip (aplikacja Player) z rozmowy z Claude do folderu Pobrane.",
            "Otwórz Termux, skopiuj polecenie poniżej, wklej i stuknij Enter.",
            "Po „main -> main” GitHub buduje aplikację ok. 3–5 minut.",
            "Przy zielonym ptaszku otwórz Player – pojawi się „Nowa wersja”. Przy czerwonym wyślij Claude zrzut linijek z „e:”."
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
}

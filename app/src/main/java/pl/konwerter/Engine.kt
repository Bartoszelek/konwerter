package pl.konwerter

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.widget.Toast
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.RendererDiscoverer
import org.videolan.libvlc.RendererItem
import org.videolan.libvlc.util.VLCVideoLayout
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

/** Silnik odtwarzania (VLC) – jeden na całą aplikację, działa też w tle. */
object Engine {
    lateinit var app: Context
    var vlc: LibVLC? = null
    var mp: MediaPlayer? = null
    var session: MediaSession? = null

    val queue = mutableListOf<Track>()
    var index = -1
    var playing = false
    var time = 0L
    var length = 0L
    var hasVideo = false
    var videoW = 0
    var videoH = 0
    var abA = -1L
    var abB = -1L
    var sleepAt = 0L
    var sleepEnd = false
    var rate = 1f
    var art: Bitmap? = null
    var error = ""

    private var pfd: ParcelFileDescriptor? = null
    private var resumeTo = -1L
    private var lastSave = 0L
    private var focusLost = false
    private val h = Handler(Looper.getMainLooper())
    val listeners = CopyOnWriteArrayList<() -> Unit>()
    val renderers = mutableListOf<RendererItem>()
    var renderer: RendererItem? = null
    private val discoverers = mutableListOf<RendererDiscoverer>()
    var dialogHandler: ((Any) -> Unit)? = null

    val current: Track? get() = queue.getOrNull(index)

    fun init(ctx: Context) {
        if (vlc != null) return
        app = ctx.applicationContext
        Store.init(app)
        val opts = arrayListOf(
            "--audio-time-stretch",
            "--subsdec-encoding=${Store.subEnc}",
            "--freetype-rel-fontsize=${Store.subSize}",
            "--http-reconnect",
            "--avcodec-skiploopfilter=1"
        )
        val lib = LibVLC(app, opts)
        vlc = lib
        val p = MediaPlayer(lib)
        mp = p
        p.setEventListener { e -> onEvent(e) }
        applyEq()
        installDialogs(lib)
        val s = MediaSession(app, "Player")
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = resume()
            override fun onPause() = pause()
            override fun onSkipToNext() = next()
            override fun onSkipToPrevious() = prev()
            override fun onSeekTo(pos: Long) = seek(pos)
            override fun onStop() = stop()
            override fun onPlayFromMediaId(mediaId: String?, extras: android.os.Bundle?) {
                if (mediaId != null) AutoService.play(app, mediaId)
            }

            override fun onPlayFromSearch(query: String?, extras: android.os.Bundle?) = AutoService.search(app, query ?: "")
        })
        s.isActive = true
        session = s
        h.post(ticker)
    }

    /** Okienka VLC (logowanie do udziału sieciowego, pytania, błędy) – przekazywane do ekranu. */
    private fun installDialogs(lib: LibVLC) {
        try {
            val cls = Class.forName("org.videolan.libvlc.Dialog")
            val cb = Class.forName("org.videolan.libvlc.Dialog\$Callbacks")
            val proxy = Proxy.newProxyInstance(cb.classLoader, arrayOf(cb)) { _, m, args ->
                if (m.name == "onDisplay" && args != null && args.isNotEmpty()) {
                    val d = args[0]
                    h.post { dialogHandler?.invoke(d) ?: run { if (d.javaClass.simpleName == "ErrorMessage") toastDialog(d) } }
                }
                null
            }
            val set = cls.methods.first { it.name == "setCallbacks" && it.parameterTypes.size == 2 }
            set.invoke(null, lib, proxy)
        } catch (e: Exception) {
        }
    }

    private fun toastDialog(d: Any) {
        try {
            val text = d.javaClass.getMethod("getText").invoke(d)?.toString() ?: return
            Toast.makeText(app, text, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (sleepAt > 0 && System.currentTimeMillis() >= sleepAt) {
                sleepAt = 0
                pause()
                Toast.makeText(app, "Wyłącznik czasowy – pauza", Toast.LENGTH_SHORT).show()
            }
            h.postDelayed(this, 1000)
        }
    }

    private fun changed() {
        for (l in listeners) l()
        updateSession()
        PlayerService.update(app)
    }

    // ------------------------------------------------------------ odtwarzanie
    fun play(list: List<Track>, start: Int, startMs: Long = -1) {
        if (list.isEmpty()) return
        queue.clear()
        queue.addAll(list)
        if (Store.shuffle && list.size > 1) {
            val first = queue.removeAt(start.coerceIn(0, queue.size - 1))
            queue.shuffle()
            queue.add(0, first)
            load(0, startMs)
        } else load(start.coerceIn(0, list.size - 1), startMs)
    }

    fun enqueue(t: Track) {
        queue.add(t)
        if (index < 0) load(queue.size - 1) else changed()
    }

    private fun makeMedia(lib: LibVLC, uri: String): Media {
        try {
            pfd?.close()
        } catch (e: Exception) {
        }
        pfd = null
        return when {
            uri.startsWith("/") -> Media(lib, uri)
            uri.startsWith("content://") -> {
                val fd = app.contentResolver.openFileDescriptor(Uri.parse(uri), "r") ?: throw IllegalStateException("Brak dostępu do pliku")
                pfd = fd
                Media(lib, fd.fileDescriptor)
            }
            else -> Media(lib, Uri.parse(uri))
        }
    }

    fun load(i: Int, startMs: Long = -1) {
        val lib = vlc ?: return
        val p = mp ?: return
        saveNow()
        val t = queue.getOrNull(i) ?: return
        index = i
        error = ""
        time = 0
        length = 0
        hasVideo = t.video
        videoW = 0
        videoH = 0
        abA = -1
        abB = -1
        art = null
        resumeTo = when {
            startMs >= 0 -> startMs
            Store.resume -> Store.positions[t.uri] ?: -1L
            else -> -1L
        }
        try {
            val m = makeMedia(lib, t.uri)
            m.setHWDecoderEnabled(Store.hw, false)
            m.addOption(":network-caching=1500")
            p.media = m
            m.release()
            p.setRate(rate)
            p.play()
            requestFocus()
            PlayerService.start(app)
        } catch (e: Exception) {
            error = e.message ?: "Nie mogę otworzyć"
            Toast.makeText(app, "Nie mogę otworzyć: ${t.title}", Toast.LENGTH_SHORT).show()
        }
        if (!t.video && t.art.startsWith("content://")) Library.thumb(app, t.art, 512, 512) { bm -> art = bm; changed() }
        changed()
    }

    private fun onEvent(e: MediaPlayer.Event) {
        val p = mp ?: return
        when (e.type) {
            MediaPlayer.Event.Playing -> {
                playing = true
                if (resumeTo > 5_000) {
                    val to = resumeTo
                    resumeTo = -1
                    h.postDelayed({ p.setTime(to) }, 150)
                } else resumeTo = -1
                h.postDelayed({ if (playing && (mp?.videoTracksCount ?: 0) == 0 && hasVideo) { hasVideo = false; changed() } }, 1500)
                changed()
            }
            MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> {
                playing = false
                saveNow()
                changed()
            }
            MediaPlayer.Event.TimeChanged -> {
                time = e.timeChanged
                if (abA >= 0 && abB > abA && time >= abB) p.setTime(abA)
                if (System.currentTimeMillis() - lastSave > 5000) saveNow()
                for (l in listeners) l()
            }
            MediaPlayer.Event.LengthChanged -> {
                length = e.lengthChanged
                changed()
            }
            MediaPlayer.Event.Vout -> {
                hasVideo = e.voutCount > 0 || hasVideo && current?.video == true
                try {
                    val vt = p.currentVideoTrack
                    if (vt != null) {
                        videoW = vt.width; videoH = vt.height
                        if (vt.orientation == 5 || vt.orientation == 6) {
                            videoW = vt.height; videoH = vt.width
                        }
                    }
                } catch (x: Exception) {
                }
                changed()
            }
            MediaPlayer.Event.EndReached -> {
                current?.let { Store.remember(it, length, length) }
                Store.save()
                if (sleepEnd) {
                    sleepEnd = false; playing = false; changed(); return
                }
                when {
                    Store.repeat == 2 -> load(index, 0)
                    index < queue.size - 1 -> load(index + 1, 0)
                    Store.repeat == 1 && queue.isNotEmpty() -> load(0, 0)
                    else -> {
                        playing = false; changed()
                    }
                }
            }
            MediaPlayer.Event.EncounteredError -> {
                error = "Błąd odtwarzania"
                Toast.makeText(app, "Nie można odtworzyć: ${current?.title ?: ""}", Toast.LENGTH_SHORT).show()
                if (index < queue.size - 1) load(index + 1) else {
                    playing = false; changed()
                }
            }
        }
    }

    fun saveNow() {
        val t = current ?: return
        if (length > 0 && time > 0) {
            Store.remember(t, time, length)
            Store.save()
        }
        lastSave = System.currentTimeMillis()
    }

    fun toggle() = if (playing) pause() else resume()

    fun pause() {
        mp?.pause()
    }

    fun resume() {
        val p = mp ?: return
        if (index < 0) return
        requestFocus()
        if (!p.isPlaying) {
            if (p.media == null) load(index) else p.play()
        }
    }

    fun stop() {
        saveNow()
        mp?.stop()
        playing = false
        changed()
        PlayerService.stop(app)
    }

    fun next() {
        if (index < queue.size - 1) load(index + 1, 0) else if (Store.repeat == 1 && queue.isNotEmpty()) load(0, 0)
    }

    fun prev() {
        if (time > 3000 || index <= 0) seek(0) else load(index - 1, 0)
    }

    fun seek(ms: Long) {
        val p = mp ?: return
        val to = ms.coerceIn(0, if (length > 0) length - 500 else ms)
        p.setTime(to)
        time = to
        changed()
    }

    fun seekBy(d: Long) = seek(time + d)

    fun setSpeed(r: Float) {
        rate = r
        mp?.setRate(r)
        changed()
    }

    // ------------------------------------------------------------ ścieżki, napisy, korektor
    fun audioTracks(): List<Pair<Int, String>> = mp?.audioTracks?.map { Pair(it.id, it.name) } ?: emptyList()
    fun spuTracks(): List<Pair<Int, String>> = mp?.spuTracks?.map { Pair(it.id, it.name) } ?: emptyList()
    fun audioTrack() = mp?.audioTrack ?: -1
    fun spuTrack() = mp?.spuTrack ?: -1
    fun setAudio(id: Int) { mp?.setAudioTrack(id) }
    fun setSpu(id: Int) { mp?.setSpuTrack(id) }
    fun addSubtitle(uri: Uri) { mp?.addSlave(0, uri, true) }
    fun spuDelay() = (mp?.spuDelay ?: 0L) / 1000
    fun audioDelay() = (mp?.audioDelay ?: 0L) / 1000
    fun setSpuDelay(ms: Long) { mp?.setSpuDelay(ms * 1000) }
    fun setAudioDelay(ms: Long) { mp?.setAudioDelay(ms * 1000) }

    fun chapters(): List<Pair<Long, String>> = try {
        mp?.getChapters(-1)?.mapIndexed { k, c -> Pair(c.timeOffset, c.name ?: "Rozdział ${k + 1}") } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    fun eqPresets(): List<String> = (0 until MediaPlayer.Equalizer.getPresetCount()).map { MediaPlayer.Equalizer.getPresetName(it) }
    fun eqBandCount() = MediaPlayer.Equalizer.getBandCount()
    fun eqFreq(i: Int) = MediaPlayer.Equalizer.getBandFrequency(i)

    fun loadPreset(i: Int) {
        val eq = MediaPlayer.Equalizer.createFromPreset(i)
        Store.eqPreset = i
        Store.eqPre = eq.preAmp
        for (b in 0 until minOf(eqBandCount(), 10)) Store.eqBands[b] = eq.getAmp(b)
    }

    fun applyEq() {
        val p = mp ?: return
        if (!Store.eqOn) {
            p.setEqualizer(null)
            return
        }
        val eq = MediaPlayer.Equalizer.create()
        eq.setPreAmp(Store.eqPre)
        for (b in 0 until minOf(eqBandCount(), 10)) eq.setAmp(b, Store.eqBands[b])
        p.setEqualizer(eq)
    }

    // ------------------------------------------------------------ obraz
    fun attach(layout: VLCVideoLayout) {
        val p = mp ?: return
        try {
            p.detachViews()
        } catch (e: Exception) {
        }
        p.attachViews(layout, null, true, false)
        p.setVideoTrackEnabled(true)
    }

    fun detach(keepAudio: Boolean) {
        val p = mp ?: return
        try {
            p.detachViews()
        } catch (e: Exception) {
        }
        if (keepAudio) p.setVideoTrackEnabled(false)
    }

    // ------------------------------------------------------------ telewizor (Chromecast / renderery VLC)
    fun discoverRenderers() {
        val lib = vlc ?: return
        if (discoverers.isNotEmpty()) return
        for (d in RendererDiscoverer.list(lib)) {
            val rd = RendererDiscoverer(lib, d.name)
            rd.setEventListener { ev ->
                when (ev.type) {
                    RendererDiscoverer.Event.ItemAdded -> ev.item?.let { if (renderers.none { r -> r.name == it.name }) renderers.add(it) }
                    RendererDiscoverer.Event.ItemDeleted -> ev.item?.let { renderers.removeAll { r -> r.name == it.name } }
                }
                for (l in listeners) l()
            }
            rd.start()
            discoverers.add(rd)
        }
    }

    fun stopDiscovery() {
        for (d in discoverers) try {
            d.stop()
        } catch (e: Exception) {
        }
        discoverers.clear()
    }

    fun cast(item: RendererItem?) {
        val p = mp ?: return
        val at = time
        renderer = item
        p.stop()
        p.setRenderer(item)
        if (index >= 0) load(index, at)
    }

    // ------------------------------------------------------------ fokus dźwięku i sesja (ekran blokady, słuchawki, Android Auto)
    private val focusListener = AudioManager.OnAudioFocusChangeListener { f ->
        when (f) {
            AudioManager.AUDIOFOCUS_LOSS -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (playing) {
                focusLost = true; pause()
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (focusLost) {
                focusLost = false; resume()
            }
        }
    }
    private var focusReq: AudioFocusRequest? = null

    private fun requestFocus() {
        val am = app.getSystemService(AudioManager::class.java) ?: return
        val r = focusReq ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setOnAudioFocusChangeListener(focusListener).build().also { focusReq = it }
        am.requestAudioFocus(r)
    }

    private fun updateSession() {
        val s = session ?: return
        val t = current
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_STOP or PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_PLAY_FROM_SEARCH
        s.setPlaybackState(PlaybackState.Builder().setActions(actions)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, time, if (playing) rate else 0f).build())
        if (t != null) {
            val mb = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, t.sub)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, length)
            art?.let { mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
            s.setMetadata(mb.build())
        }
    }

    fun openPlayer(ctx: Context) {
        ctx.startActivity(Intent(ctx, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }
}

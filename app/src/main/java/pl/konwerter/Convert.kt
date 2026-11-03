package pl.konwerter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** Formaty docelowe. */
object Targets {
    val VIDEO = listOf(
        "mp4" to "MP4",
        "mp4aac" to "MP4 · dźwięk AAC",
        "mp4hevc" to "MP4 HEVC",
        "mp4fast" to "MP4 szybko",
        "mov" to "MOV",
        "3gp" to "3GP",
        "gif" to "GIF"
    )
    val AUDIO = listOf("m4a" to "M4A", "wav" to "WAV")
    val ALL = VIDEO + AUDIO
    /** Dźwięk, którego telefon zwykle nie dekoduje (VLC tak). */
    val HARD_AUDIO = setOf("AC-3", "E-AC-3", "DTS", "TrueHD", "MP2")

    fun label(t: String) = ALL.firstOrNull { it.first == t }?.second ?: t
    fun ext(t: String) = when (t) {
        "mp4aac" -> "mp4"
        "m4a" -> "m4a"
        "wav" -> "wav"
        "mov" -> "mov"
        "3gp" -> "3gp"
        "gif" -> "gif"
        else -> "mp4"
    }

    fun isAudio(t: String) = t == "m4a" || t == "wav"

    /** Formaty, w których wideo jest kodowane od nowa (można zmienić rozdzielczość i jakość). */
    fun encodes(t: String) = t == "mp4" || t == "mp4hevc" || t == "mov" || t == "3gp"

    fun mime(t: String, audioOnly: Boolean) = when {
        t == "wav" -> "audio/x-wav"
        t == "gif" -> "image/gif"
        t == "m4a" || audioOnly -> "audio/mp4"
        t == "mov" -> "video/quicktime"
        t == "3gp" -> "video/3gpp"
        else -> "video/mp4"
    }
}

/** Jeden plik w kolejce. */
class Job(val uri: Uri, val name: String, val info: String, val hasVideo: Boolean, val w: Int, val h: Int, val size: Long, val rot: Int = 0,
          val codecs: List<String> = emptyList()) {
    var clipStart = -1L
    var clipEnd = -1L
    var viaVlc = false
    var mimes: List<String> = emptyList()
    var wav: File? = null
    var status = "wait"      // wait, run, done, error, cancel
    var progress = 0
    var target = "mp4"
    var height = 0
    var quality = "high"
    var removeAudio = false
    var gifSeconds = 10
    var gifWidth = 480
    var gifFps = 10
    var gifStart = 0
    var outUri: Uri? = null
    var outSize = 0L
    var error = ""
    var started = 0L
    var finished = 0L
}

object Queue {
    val jobs = mutableListOf<Job>()
    @Volatile var running = false
    @Volatile var cancel = false

    fun pending() = jobs.count { it.status == "wait" }
    fun current() = jobs.firstOrNull { it.status == "run" }

    /** Odczyt informacji o pliku: nazwa, czas, rozdzielczość, kodeki, rozmiar. */
    fun probe(ctx: Context, uri: Uri): Job {
        var name = "plik"
        var size = 0L
        if (uri.scheme == "file") uri.path?.let { name = java.io.File(it).name; size = java.io.File(it).length() }
        if (uri.scheme != "file") try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0) ?: name
                    size = if (c.isNull(1)) 0L else c.getLong(1)
                }
            }
        } catch (e: Exception) {
        }
        var w = 0
        var h = 0
        var hasVideo = false
        var rotation = 0
        val parts = ArrayList<String>()
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            hasVideo = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (hasVideo) {
                w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                rotation = rot
                if (rot == 90 || rot == 270) {
                    val t = w; w = h; h = t
                }
                if (w > 0) parts.add("${w}×$h")
            }
            if (dur > 0) parts.add(time(dur))
        } catch (e: Exception) {
        } finally {
            try {
                r.release()
            } catch (e: Exception) {
            }
        }
        var codecs: List<String> = emptyList()
        var mimes: List<String> = emptyList()
        try {
            val ex = MediaExtractor()
            ex.setDataSource(ctx, uri, null)
            mimes = (0 until ex.trackCount).mapNotNull { k -> ex.getTrackFormat(k).getString(MediaFormat.KEY_MIME) }.distinct()
            codecs = mimes.map { codecName(it) }.distinct()
            if (codecs.isNotEmpty()) parts.add(codecs.joinToString(" + "))
            ex.release()
        } catch (e: Exception) {
            parts.add("otworzy go silnik VLC")
        }
        val audioMimes = mimes.filter { it.startsWith("audio/") }
        if (audioMimes.isNotEmpty() && audioMimes.none { canDecode(it) }) parts.add("dźwięk ${codecs.filter { it in Targets.HARD_AUDIO }.joinToString()} – przerobi go silnik VLC")
        if (size > 0) parts.add(sizeText(size))
        return Job(uri, name, parts.joinToString(" · "), hasVideo || name.lowercase().let { n -> listOf(".mkv", ".avi", ".wmv", ".flv", ".ts", ".m2ts", ".rmvb", ".vob", ".mpg", ".mpeg", ".mov", ".webm").any { n.endsWith(it) } },
            w, h, size, rotation, codecs).also { it.mimes = mimes }
    }

    /** Czy telefon ma własny dekoder tego formatu. */
    fun canDecode(mime: String): Boolean = try {
        android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).codecInfos
            .any { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
    } catch (e: Exception) {
        false
    }

    /** Ścieżka pliku (gdy da się ją odczytać) – VLC czyta wtedy szybciej i pewniej. */
    fun pathOf(ctx: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        try {
            ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val p = c.getString(0)
                    if (p != null && File(p).canRead()) return p
                }
            }
        } catch (e: Exception) {
        }
        return null
    }

    fun codecName(mime: String) = when (mime) {
        "video/avc" -> "H.264"
        "video/hevc" -> "HEVC"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/av01" -> "AV1"
        "video/mp4v-es" -> "MPEG-4"
        "video/3gpp" -> "H.263"
        "audio/mp4a-latm" -> "AAC"
        "audio/mpeg" -> "MP3"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/flac" -> "FLAC"
        "audio/raw" -> "PCM"
        "audio/3gpp", "audio/amr-wb" -> "AMR"
        "audio/ac3" -> "AC-3"
        "audio/eac3" -> "E-AC-3"
        "audio/vnd.dts", "audio/vnd.dts.hd" -> "DTS"
        "audio/true-hd" -> "TrueHD"
        "audio/mpeg-L2" -> "MP2"
        else -> mime.substringAfter("/").uppercase(Locale.US)
    }

    fun time(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    fun sizeText(b: Long): String = when {
        b >= 1L shl 30 -> String.format(Locale("pl"), "%.2f GB", b / 1073741824.0)
        b >= 1L shl 20 -> String.format(Locale("pl"), "%.1f MB", b / 1048576.0)
        else -> String.format(Locale("pl"), "%d kB", b / 1024)
    }
}

/** Zamiana na WAV: dekodowanie ścieżki dźwiękowej do PCM 16-bit. */
object Wav {
    fun convert(ctx: Context, uri: Uri, out: File, progress: (Int) -> Unit) {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: throw IllegalStateException("Plik nie ma ścieżki dźwiękowej")
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val mime = fmt.getString(MediaFormat.KEY_MIME) ?: throw IllegalStateException("Nieznany format dźwięku")
        val dur = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var enc = 2
        val dec = MediaCodec.createDecoderByType(mime)
        dec.configure(fmt, null, null, 0)
        dec.start()
        val raf = RandomAccessFile(out, "rw")
        raf.setLength(0)
        raf.write(ByteArray(44))
        var data = 0L
        val info = MediaCodec.BufferInfo()
        var inDone = false
        var outDone = false
        try {
            while (!outDone) {
                if (Queue.cancel) throw InterruptedException("Anulowano")
                if (!inDone) {
                    val i = dec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = dec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = dec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    val ob = dec.getOutputBuffer(o)!!
                    ob.position(info.offset)
                    ob.limit(info.offset + info.size)
                    val bytes = if (enc == 4) floatTo16(ob) else ByteArray(info.size).also { ob.get(it) }
                    raf.write(bytes)
                    data += bytes.size
                    if (dur > 0) progress((info.presentationTimeUs * 100 / dur).toInt().coerceIn(0, 99))
                    dec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = dec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    enc = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else 2
                }
            }
            raf.seek(0)
            raf.write(header(data, rate, ch))
        } finally {
            raf.close()
            try {
                dec.stop()
            } catch (e: Exception) {
            }
            dec.release()
            ex.release()
        }
    }

    private fun floatTo16(b: ByteBuffer): ByteArray {
        val fb = b.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val out = ByteBuffer.allocate(fb.remaining() * 2).order(ByteOrder.LITTLE_ENDIAN)
        while (fb.hasRemaining()) out.putShort((fb.get().coerceIn(-1f, 1f) * 32767).toInt().toShort())
        return out.array()
    }

    private fun header(data: Long, rate: Int, ch: Int): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt((36 + data).toInt()).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(ch.toShort()).putInt(rate)
            .putInt(rate * ch * 2).putShort((ch * 2).toShort()).putShort(16)
        b.put("data".toByteArray()).putInt(data.toInt())
        return b.array()
    }
}

/** Usługa w tle: przerabia kolejkę plik po pliku (działa też po wyjściu z aplikacji). */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class ConvertService : Service() {
    companion object {
        const val ACT_CANCEL = "pl.konwerter.CANCEL"
    }

    private val h = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    private var temp: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.init(this)
        if (intent?.action == ACT_CANCEL) {
            Queue.cancel = true
            transformer?.cancel()
            Queue.jobs.filter { it.status == "run" || it.status == "wait" }.forEach { it.status = "cancel" }
            finish()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel("conv", "Konwersja", NotificationManager.IMPORTANCE_LOW))
        val n = notification("Przygotowuję…", 0)
        if (Build.VERSION.SDK_INT >= 30) startForeground(9, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(9, n)
        if (!Queue.running) {
            Queue.running = true
            Queue.cancel = false
            next()
        }
        return START_NOT_STICKY
    }

    private fun notification(text: String, progress: Int): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 2, Intent(this, ConvertService::class.java).setAction(ACT_CANCEL), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "conv")
            .setSmallIcon(Icon.createWithResource(this, R.drawable.ic_play))
            .setContentTitle("Konwertuję")
            .setContentText(text)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stop), "Anuluj", cancel).build())
            .build()
    }

    private fun update(job: Job) {
        val left = Queue.pending()
        val t = job.name + if (left > 0) " (jeszcze $left)" else ""
        getSystemService(NotificationManager::class.java)?.notify(9, notification(t, job.progress))
    }

    private fun finish() {
        Queue.running = false
        transformer = null
        temp?.delete()
        temp = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        val done = Queue.jobs.count { it.status == "done" }
        if (done > 0) {
            val nm = getSystemService(NotificationManager::class.java)
            val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            nm?.notify(10, Notification.Builder(this, "conv").setSmallIcon(Icon.createWithResource(this, R.drawable.ic_play))
                .setContentTitle("Gotowe").setContentText("Przekonwertowano plików: $done").setContentIntent(open).setAutoCancel(true).build())
        }
        stopSelf()
    }

    private fun next() {
        if (Queue.cancel) {
            finish(); return
        }
        val job = Queue.jobs.firstOrNull { it.status == "wait" }
        if (job == null) {
            finish(); return
        }
        job.status = "run"
        job.progress = 0
        job.started = System.currentTimeMillis()
        // stare pliki robocze (np. po przerwanej konwersji)
        work().listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 60_000) it.delete() }
        val need = (job.size * 1.15).toLong() + 700L * 1024 * 1024
        val free = work().usableSpace
        if (job.size > 0 && free < need && job.target != "gif") {
            job.status = "error"
            job.error = "Za mało miejsca w telefonie: potrzeba ok. ${gb(need)}, wolne ${gb(free)}. Usuń coś albo wybierz mniejszą rozdzielczość."
            next()
            return
        }
        val out = File(work(), "out_${System.currentTimeMillis()}.${Targets.ext(job.target)}")
        temp = out
        update(job)
        val audioMimes = job.mimes.filter { it.startsWith("audio/") }
        val hardAudio = audioMimes.isNotEmpty() && audioMimes.none { Queue.canDecode(it) }
        if (hardAudio && !job.removeAudio && job.target != "gif") {
            hybrid(job, out)
            return
        }
        if (job.target == "wav" || job.target == "gif") {
            Thread {
                try {
                    if (job.target == "gif") Gif.convert(this, job, out) { p -> job.progress = p; h.post { update(job) } }
                    else Wav.convert(this, job.uri, out) { p -> job.progress = p; h.post { update(job) } }
                    h.post { done(job, out) }
                } catch (e: Exception) {
                    h.post { fail(job, e.message ?: "błąd dekodowania") }
                }
            }.start()
            return
        }
        try {
            startTransformer(job, out)
        } catch (e: Exception) {
            fail(job, e.message ?: "nieobsługiwany plik")
        }
    }

    /** Folder roboczy, którego Android nie czyści przy braku miejsca (w przeciwieństwie do pamięci podręcznej). */
    private fun work(): File = (getExternalFilesDir("praca") ?: filesDir).apply { mkdirs() }

    private fun gb(b: Long) = String.format(Locale("pl"), "%.1f GB", b / 1073741824.0)

    private fun bitrate(job: Job, outH: Int): Int {
        val base = when {
            outH >= 2000 -> 20_000_000
            outH >= 1400 -> 12_000_000
            outH >= 1000 -> 8_000_000
            outH >= 700 -> 5_000_000
            else -> 2_500_000
        }
        return when (job.quality) {
            "medium" -> base / 2
            "small" -> base / 4
            else -> base
        }
    }

    /**
     * Dźwięk, którego telefon nie dekoduje (AC3, DTS…): 1) VLC → WAV stereo, 2) Android: obraz z oryginału + ten dźwięk jako AAC.
     */
    private fun hybrid(job: Job, out: File) {
        job.viaVlc = true
        job.progress = 0
        val wav = File(work(), "pcm_${System.currentTimeMillis()}.wav")
        job.wav = wav
        val share = if (job.target == "wav") 100 else 45
        VlcConvert.toWav(this, job, wav, { p -> job.progress = p * share / 100; update(job) }, {
            if (job.target == "wav") done(job, wav)
            else try {
                startTransformer(job, out, wav, share)
            } catch (e: Exception) {
                wav.delete()
                fail(job, e.message ?: "błąd składania pliku")
            }
        }, { m ->
            wav.delete()
            fail(job, m)
        })
    }

    private fun startTransformer(job: Job, out: File, wav: File? = null, base: Int = 0) {
        val audioOnly = job.target == "m4a" || !job.hasVideo
        val fast = job.target == "mp4fast" || job.target == "mp4aac"
        val b = Transformer.Builder(this)
        if (!audioOnly && !fast) b.setVideoMimeType(if (job.target == "mp4hevc") MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
        if (!job.removeAudio || audioOnly) b.setAudioMimeType(MimeTypes.AUDIO_AAC)
        // rozdzielczość: krótszy bok = wybrana wysokość (pion i poziom)
        val effects = ArrayList<androidx.media3.common.Effect>()
        var outShort = minOf(job.w, job.h)
        if (!audioOnly && !fast && job.height > 0 && job.w > 0 && job.h > 0 && minOf(job.w, job.h) > job.height) {
            val portrait = job.h > job.w
            val sc = job.height.toDouble() / minOf(job.w, job.h)
            val nw = (Math.round(job.w * sc / 2) * 2).toInt()
            val nh = (Math.round(job.h * sc / 2) * 2).toInt()
            effects.add(Presentation.createForWidthAndHeight(nw, nh, Presentation.LAYOUT_SCALE_TO_FIT))
            outShort = if (portrait) nw else nh
        }
        if (!audioOnly && !fast && (job.quality != "high" || effects.isNotEmpty())) {
            b.setEncoderFactory(
                DefaultEncoderFactory.Builder(this)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate(job, outShort)).build())
                    .build()
            )
        }
        b.addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                done(job, out)
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                fail(job, exportException.errorCodeName + (exportException.message?.let { ": $it" } ?: ""))
            }
        })
        val mi = if (job.clipStart >= 0 && job.clipEnd > job.clipStart) MediaItem.Builder().setUri(job.uri)
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(job.clipStart).setEndPositionMs(job.clipEnd).build())
            .build() else MediaItem.fromUri(job.uri)
        val item = EditedMediaItem.Builder(mi)
            .setRemoveVideo(audioOnly)
            .setRemoveAudio((job.removeAudio || wav != null) && !audioOnly)
            .setEffects(Effects(emptyList(), effects))
            .build()
        val t = b.build()
        transformer = t
        when {
            wav != null && audioOnly -> t.start(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(wav))).build(), out.path)
            wav != null -> {
                val audioItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(wav))).build()
                t.start(Composition.Builder(EditedMediaItemSequence(item), EditedMediaItemSequence(audioItem)).build(), out.path)
            }
            else -> t.start(item, out.path)
        }
        val holder = ProgressHolder()
        h.post(object : Runnable {
            override fun run() {
                if (transformer !== t || job.status != "run") return
                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    job.progress = base + holder.progress * (100 - base) / 100
                    update(job)
                }
                h.postDelayed(this, 500)
            }
        })
    }

    private fun done(job: Job, out: File) {
        transformer = null
        if (Queue.cancel) {
            out.delete(); job.status = "cancel"; next(); return
        }
        try {
            if (!out.exists() || out.length() == 0L) throw IllegalStateException("Plik wynikowy zniknął przed zapisem – prawdopodobnie zabrakło miejsca w telefonie")
            val base = job.name.substringBeforeLast('.')
            val gif = job.target == "gif"
            val audio = !gif && (Targets.isAudio(job.target) || !job.hasVideo)
            val ext = if (audio && !Targets.isAudio(job.target)) "m4a" else Targets.ext(job.target)
            val mime = Targets.mime(job.target, audio)
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$base.$ext")
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, when {
                    gif -> "Pictures/Konwerter"
                    audio -> "Music/Konwerter"
                    else -> "Movies/Konwerter"
                })
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val coll = when {
                gif -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                audio -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                else -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            val uri = contentResolver.insert(coll, cv) ?: throw IllegalStateException("Nie mogę zapisać pliku")
            contentResolver.openOutputStream(uri)?.use { o -> out.inputStream().use { it.copyTo(o) } }
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            job.outUri = uri
            job.outSize = out.length()
            job.status = "done"
            job.progress = 100
            job.finished = System.currentTimeMillis()
        } catch (e: Exception) {
            job.status = "error"
            job.error = e.message ?: "zapis nieudany"
        }
        out.delete()
        job.wav?.delete()
        job.wav = null
        temp = null
        next()
    }

    private fun fail(job: Job, msg: String) {
        transformer = null
        val decoderProblem = msg.contains("DECOD", true) || msg.contains("UNSUPPORTED", true) || msg.contains("FORMAT", true)
        if (!job.viaVlc && !Queue.cancel && decoderProblem && !job.removeAudio && job.target != "gif") {
            // druga próba: dźwięk przez VLC, obraz z oryginału
            hybrid(job, temp ?: File(work(), "out_${System.currentTimeMillis()}.${Targets.ext(job.target)}"))
            return
        }
        job.wav?.delete()
        job.wav = null
        temp?.delete()
        temp = null
        job.status = if (Queue.cancel) "cancel" else "error"
        job.error = when {
            msg.contains("DECODER", true) || msg.contains("UNSUPPORTED", true) -> "Telefon nie obsługuje tego formatu lub kodeka ($msg)"
            msg.contains("ENCODER", true) -> "Kodowanie nie powiodło się – spróbuj mniejszej rozdzielczości ($msg)"
            job.viaVlc -> "Nie udało się ($msg)"
            else -> msg
        }
        next()
    }
}


/** VLC: dźwięk z dowolnego pliku (AC3, DTS, TrueHD…) → WAV stereo 48 kHz (bez potrzeby koderów). */
object VlcConvert {
    fun toWav(ctx: Context, job: Job, wav: File, progress: (Int) -> Unit, ok: () -> Unit, err: (String) -> Unit) {
        val h = Handler(Looper.getMainLooper())
        try {
            val lib = org.videolan.libvlc.LibVLC(ctx, arrayListOf("--no-spu", "--no-osd"))
            val path = Queue.pathOf(ctx, job.uri)
            val pfd = if (path == null) ctx.contentResolver.openFileDescriptor(job.uri, "r") ?: throw IllegalStateException("Brak dostępu do pliku") else null
            val m = if (path != null) org.videolan.libvlc.Media(lib, path) else org.videolan.libvlc.Media(lib, pfd!!.fileDescriptor)
            m.addOption(":sout=#transcode{acodec=s16l,channels=2,samplerate=48000}:std{access=file,mux=wav,dst='${wav.path}'}")
            m.addOption(":no-sout-video")
            m.addOption(":no-sout-spu")
            m.addOption(":sout-keep")
            if (job.clipStart >= 0 && job.clipEnd > job.clipStart) {
                m.addOption(":start-time=${job.clipStart / 1000.0}")
                m.addOption(":stop-time=${job.clipEnd / 1000.0}")
            }
            val p = org.videolan.libvlc.MediaPlayer(lib)
            p.media = m
            m.release()
            var finished = false
            fun close() {
                try { p.release() } catch (e: Exception) {}
                try { lib.release() } catch (e: Exception) {}
                try { pfd?.close() } catch (e: Exception) {}
            }
            p.setEventListener { e ->
                if (finished) return@setEventListener
                when (e.type) {
                    org.videolan.libvlc.MediaPlayer.Event.PositionChanged -> progress((e.positionChanged * 100).toInt().coerceIn(0, 99))
                    org.videolan.libvlc.MediaPlayer.Event.EndReached, org.videolan.libvlc.MediaPlayer.Event.Stopped -> {
                        finished = true
                        h.postDelayed({
                            close()
                            if (wav.exists() && wav.length() > 10_000) ok() else err("VLC nie zdekodował dźwięku")
                        }, 300)
                    }
                    org.videolan.libvlc.MediaPlayer.Event.EncounteredError -> {
                        finished = true
                        h.post { close(); err("VLC nie otworzył pliku") }
                    }
                }
                if (Queue.cancel && !finished) {
                    finished = true
                    h.post { try { p.stop() } catch (x: Exception) {}; close(); err("Anulowano") }
                }
            }
            p.play()
        } catch (e: Exception) {
            err(e.message ?: "silnik VLC niedostępny")
        }
    }
}

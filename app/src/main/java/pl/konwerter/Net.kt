package pl.konwerter

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Channel(val name: String, val url: String, val group: String, val logo: String)

/** IPTV: listy M3U/M3U8 (z adresu albo pliku). */
object IptvParser {
    fun fetch(ctx: Context, src: String): String = when {
        src.startsWith("content://") -> ctx.contentResolver.openInputStream(Uri.parse(src))?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
        else -> {
            val c = URL(src).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "Player/1.0 (Android)")
            c.instanceFollowRedirects = true
            c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        }
    }

    fun parse(text: String): List<Channel> {
        val out = ArrayList<Channel>()
        var name = ""
        var group = ""
        var logo = ""
        for (raw in text.lines()) {
            val l = raw.trim()
            when {
                l.startsWith("#EXTINF", true) -> {
                    name = l.substringAfterLast(",").trim()
                    group = Regex("group-title=\"([^\"]*)\"").find(l)?.groupValues?.get(1) ?: ""
                    logo = Regex("tvg-logo=\"([^\"]*)\"").find(l)?.groupValues?.get(1) ?: ""
                }
                l.startsWith("#EXTGRP:", true) -> group = l.substringAfter(":").trim()
                l.isEmpty() || l.startsWith("#") -> {}
                else -> {
                    out.add(Channel(name.ifEmpty { l.substringAfterLast('/') }, l, group.ifEmpty { "Inne" }, logo))
                    name = ""; group = ""; logo = ""
                }
            }
        }
        return out
    }
}

class NetItem(val title: String, val uri: String, val dir: Boolean)

/**
 * Udziały sieciowe (SMB, UPnP/DLNA) przez przeglądarkę VLC.
 * Połączenie przez Proxy – niezależne od drobnych różnic między wersjami biblioteki.
 */
class NetBrowser(private val onChange: (List<NetItem>, Boolean) -> Unit) {
    private var browser: Any? = null
    private val items = ArrayList<NetItem>()
    private val h = Handler(Looper.getMainLooper())

    private fun call(o: Any, name: String, vararg args: Any?): Any? {
        val m = o.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == args.size } ?: return null
        return m.invoke(o, *args)
    }

    private fun create(): Any? {
        val lib = Engine.vlc ?: return null
        val cls = Class.forName("org.videolan.libvlc.util.MediaBrowser")
        val lcls = Class.forName("org.videolan.libvlc.util.MediaBrowser\$EventListener")
        val listener = java.lang.reflect.Proxy.newProxyInstance(lcls.classLoader, arrayOf(lcls)) { _, m, args ->
            when (m.name) {
                "onMediaAdded" -> {
                    val media = args?.getOrNull(1)
                    if (media != null) {
                        val uri = (call(media, "getUri") as? Uri)?.toString() ?: ""
                        val type = (call(media, "getType") as? Int) ?: 0
                        var title = (call(media, "getMeta", 0) as? String) ?: ""
                        if (title.isEmpty()) title = Uri.decode(uri.trimEnd('/').substringAfterLast('/'))
                        val dir = type == 2 || type == 5 || uri.endsWith("/")
                        h.post {
                            if (items.none { it.uri == uri }) {
                                items.add(NetItem(title, uri, dir))
                                onChange(items.sortedWith(compareByDescending<NetItem> { it.dir }.thenBy { it.title.lowercase() }), false)
                            }
                        }
                    }
                }
                "onBrowseEnd" -> h.post { onChange(items.sortedWith(compareByDescending<NetItem> { it.dir }.thenBy { it.title.lowercase() }), true) }
            }
            null
        }
        val ctor = cls.constructors.first { it.parameterTypes.size == 2 }
        return ctor.newInstance(lib, listener)
    }

    fun discover() {
        release()
        items.clear()
        try {
            val b = create() ?: return
            browser = b
            call(b, "discoverNetworkShares")
        } catch (e: Exception) {
            onChange(emptyList(), true)
        }
    }

    fun browse(uri: String) {
        release()
        items.clear()
        try {
            val b = create() ?: return
            browser = b
            val m = b.javaClass.methods.firstOrNull { it.name == "browse" && it.parameterTypes.size == 2 && it.parameterTypes[0] == Uri::class.java }
            if (m != null) m.invoke(b, Uri.parse(uri), 1) else call(b, "browse", Uri.parse(uri))
        } catch (e: Exception) {
            onChange(emptyList(), true)
        }
    }

    fun release() {
        val b = browser ?: return
        browser = null
        try {
            call(b, "release")
        } catch (e: Exception) {
        }
    }
}

/** Napisy z OpenSubtitles.com (wymaga darmowego konta i klucza API). */
object Subs {
    private const val API = "https://api.opensubtitles.com/api/v1"
    private var token = ""

    class Hit(val name: String, val lang: String, val fileId: Long, val downloads: Int, val byHash: Boolean)

    private fun req(method: String, path: String, body: String? = null, auth: Boolean = false): JSONObject {
        val c = URL(API + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("Api-Key", Store.osKey)
        c.setRequestProperty("User-Agent", "Player v1.0")
        c.setRequestProperty("Accept", "application/json")
        if (auth && token.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.use { String(it.readBytes()) } ?: ""
        if (code >= 400) throw IllegalStateException("OpenSubtitles: kod $code " + text.take(120))
        return JSONObject(text)
    }

    /** Skrót pliku wg OpenSubtitles: rozmiar + suma 64 kB z początku i końca. */
    fun hash(ctx: Context, uri: String): Pair<String, Long>? = try {
        val pfd = if (uri.startsWith("/")) android.os.ParcelFileDescriptor.open(File(uri), android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        else ctx.contentResolver.openFileDescriptor(Uri.parse(uri), "r")
        pfd?.use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { ch ->
                val size = ch.size()
                val chunk = 65536L
                if (size < chunk * 2) return@use null
                var sum = size
                for (start in listOf(0L, size - chunk)) {
                    val buf = ByteBuffer.allocate(chunk.toInt()).order(ByteOrder.LITTLE_ENDIAN)
                    ch.position(start)
                    while (buf.hasRemaining() && ch.read(buf) > 0) {
                    }
                    buf.flip()
                    val lb = buf.asLongBuffer()
                    while (lb.hasRemaining()) sum += lb.get()
                }
                Pair(String.format("%016x", sum), size)
            }
        }
    } catch (e: Exception) {
        null
    }

    fun search(ctx: Context, t: Track): List<Hit> {
        if (Store.osKey.isBlank()) throw IllegalStateException("Wpisz klucz API OpenSubtitles w Ustawieniach")
        val hs = hash(ctx, t.uri)
        val q = Uri.encode(t.title.substringBeforeLast('.').replace(Regex("[._]"), " ").trim())
        val langs = Uri.encode(Store.subLang.replace(" ", ""))
        val path = "/subtitles?languages=$langs" + (if (hs != null) "&moviehash=${hs.first}" else "") + "&query=$q"
        val data = req("GET", path).optJSONArray("data") ?: return emptyList()
        val out = ArrayList<Hit>()
        for (k in 0 until data.length()) {
            val a = data.getJSONObject(k).optJSONObject("attributes") ?: continue
            val f = a.optJSONArray("files")?.optJSONObject(0) ?: continue
            out.add(Hit(a.optString("release").ifEmpty { f.optString("file_name") }, a.optString("language"), f.optLong("file_id"),
                a.optInt("download_count"), a.optBoolean("moviehash_match")))
        }
        return out.sortedWith(compareByDescending<Hit> { it.byHash }.thenByDescending { it.downloads })
    }

    fun download(ctx: Context, hit: Hit): File {
        if (token.isEmpty() && Store.osUser.isNotBlank()) {
            token = req("POST", "/login", JSONObject().put("username", Store.osUser).put("password", Store.osPass).toString()).optString("token")
        }
        val link = req("POST", "/download", JSONObject().put("file_id", hit.fileId).toString(), true).optString("link")
        if (link.isEmpty()) throw IllegalStateException("Brak linku do pobrania (limit konta?)")
        val dir = File(ctx.cacheDir, "subs").apply { mkdirs() }
        val f = File(dir, "${hit.fileId}.srt")
        (URL(link).openConnection() as HttpURLConnection).inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        return f
    }
}

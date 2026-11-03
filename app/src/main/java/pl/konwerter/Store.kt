package pl.konwerter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Utwór/film w kolejce: adres (ścieżka, content://, http…), tytuł, podpis, czy wideo. */
class Track(val uri: String, val title: String, val sub: String = "", val video: Boolean = true, val art: String = "") {
    fun toJson(): JSONObject = JSONObject().put("u", uri).put("t", title).put("s", sub).put("v", video).put("a", art)

    companion object {
        fun fromJson(o: JSONObject) = Track(o.optString("u"), o.optString("t"), o.optString("s"), o.optBoolean("v", true), o.optString("a"))
    }
}

class Playlist(var name: String, val items: MutableList<Track> = mutableListOf())
class History(val track: Track, var time: Long, var pos: Long, var len: Long)
class Iptv(var name: String, var url: String)
class NetPlace(var name: String, var uri: String)

/** Ustawienia i dane aplikacji. */
object Store {
    private var file: File? = null

    // wygląd
    var eink = false
    var invert = false
    var theme = "indigo"
    var mode = "system"
    var ghToken = ""

    // konwerter
    var target = "mp4"
    var height = 0
    var quality = "high"
    var removeAudio = false
    var gifSeconds = 10
    var gifWidth = 480
    var gifFps = 10
    var gifStart = 0

    // odtwarzacz
    var hw = true
    var resume = true
    var autoPip = true
    var bgAudio = true
    var seekStep = 10
    var subEnc = "Windows-1250"
    var subSize = 16
    var subLang = "pl,en"
    var osKey = ""
    var osUser = ""
    var osPass = ""
    var eqOn = false
    var eqPreset = 0
    var eqBands = FloatArray(10)
    var eqPre = 0f
    var repeat = 0
    var shuffle = false
    var lastTab = "video"
    var videoSort = "date"

    val positions = LinkedHashMap<String, Long>()
    val history = mutableListOf<History>()
    val playlists = mutableListOf<Playlist>()
    val iptv = mutableListOf<Iptv>()
    val places = mutableListOf<NetPlace>()

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.applicationContext.filesDir, "settings.json")
        try {
            val f = file ?: return
            if (!f.exists()) return
            val o = JSONObject(f.readText())
            eink = o.optBoolean("eink")
            invert = o.optBoolean("invert")
            theme = o.optString("theme", "indigo")
            mode = o.optString("mode", "system")
            ghToken = o.optString("ghToken")
            target = o.optString("target", "mp4")
            height = o.optInt("height")
            quality = o.optString("quality", "high")
            removeAudio = o.optBoolean("removeAudio")
            gifSeconds = o.optInt("gifSeconds", 10)
            gifWidth = o.optInt("gifWidth", 480)
            gifFps = o.optInt("gifFps", 10)
            hw = o.optBoolean("hw", true)
            resume = o.optBoolean("resume", true)
            autoPip = o.optBoolean("autoPip", true)
            bgAudio = o.optBoolean("bgAudio", true)
            seekStep = o.optInt("seekStep", 10)
            subEnc = o.optString("subEnc", "Windows-1250")
            subSize = o.optInt("subSize", 16)
            subLang = o.optString("subLang", "pl,en")
            osKey = o.optString("osKey")
            osUser = o.optString("osUser")
            osPass = o.optString("osPass")
            eqOn = o.optBoolean("eqOn")
            eqPreset = o.optInt("eqPreset")
            eqPre = o.optDouble("eqPre", 0.0).toFloat()
            o.optJSONArray("eqBands")?.let { a -> for (k in 0 until minOf(a.length(), 10)) eqBands[k] = a.optDouble(k).toFloat() }
            repeat = o.optInt("repeat")
            shuffle = o.optBoolean("shuffle")
            lastTab = o.optString("lastTab", "video")
            videoSort = o.optString("videoSort", "date")
            o.optJSONObject("positions")?.let { p -> for (k in p.keys()) positions[k] = p.optLong(k) }
            o.optJSONArray("history")?.let { a ->
                for (k in 0 until a.length()) {
                    val h = a.getJSONObject(k)
                    history.add(History(Track.fromJson(h.getJSONObject("t")), h.optLong("time"), h.optLong("pos"), h.optLong("len")))
                }
            }
            o.optJSONArray("playlists")?.let { a ->
                for (k in 0 until a.length()) {
                    val p = a.getJSONObject(k)
                    val pl = Playlist(p.optString("name"))
                    p.optJSONArray("items")?.let { it2 -> for (j in 0 until it2.length()) pl.items.add(Track.fromJson(it2.getJSONObject(j))) }
                    playlists.add(pl)
                }
            }
            o.optJSONArray("iptv")?.let { a -> for (k in 0 until a.length()) a.getJSONObject(k).let { iptv.add(Iptv(it.optString("n"), it.optString("u"))) } }
            o.optJSONArray("places")?.let { a -> for (k in 0 until a.length()) a.getJSONObject(k).let { places.add(NetPlace(it.optString("n"), it.optString("u"))) } }
        } catch (e: Exception) {
        }
    }

    @Synchronized
    fun save() {
        try {
            val o = JSONObject()
                .put("eink", eink).put("invert", invert).put("theme", theme).put("mode", mode).put("ghToken", ghToken)
                .put("target", target).put("height", height).put("quality", quality).put("removeAudio", removeAudio)
                .put("gifSeconds", gifSeconds).put("gifWidth", gifWidth).put("gifFps", gifFps)
                .put("hw", hw).put("resume", resume).put("autoPip", autoPip).put("bgAudio", bgAudio).put("seekStep", seekStep)
                .put("subEnc", subEnc).put("subSize", subSize).put("subLang", subLang)
                .put("osKey", osKey).put("osUser", osUser).put("osPass", osPass)
                .put("eqOn", eqOn).put("eqPreset", eqPreset).put("eqPre", eqPre.toDouble())
                .put("eqBands", JSONArray(eqBands.map { it.toDouble() }))
                .put("repeat", repeat).put("shuffle", shuffle).put("lastTab", lastTab).put("videoSort", videoSort)
            val p = JSONObject()
            for ((k, v) in positions) p.put(k, v)
            o.put("positions", p)
            o.put("history", JSONArray(history.map { JSONObject().put("t", it.track.toJson()).put("time", it.time).put("pos", it.pos).put("len", it.len) }))
            o.put("playlists", JSONArray(playlists.map { pl -> JSONObject().put("name", pl.name).put("items", JSONArray(pl.items.map { it.toJson() })) }))
            o.put("iptv", JSONArray(iptv.map { JSONObject().put("n", it.name).put("u", it.url) }))
            o.put("places", JSONArray(places.map { JSONObject().put("n", it.name).put("u", it.uri) }))
            file?.writeText(o.toString())
        } catch (e: Exception) {
        }
    }

    /** Zapamiętanie miejsca w filmie/utworze (do wznawiania i „Kontynuuj”). */
    fun remember(t: Track, pos: Long, len: Long) {
        if (len <= 0) return
        val done = pos > len - 15_000 || pos < 5_000
        if (done) positions.remove(t.uri) else positions[t.uri] = pos
        while (positions.size > 400) positions.remove(positions.keys.first())
        history.removeAll { it.track.uri == t.uri }
        history.add(0, History(t, System.currentTimeMillis(), if (done && pos > len - 15_000) len else pos, len))
        while (history.size > 150) history.removeAt(history.size - 1)
    }
}

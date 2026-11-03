package pl.konwerter

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Ustawienia aplikacji. */
object Store {
    private var file: File? = null
    var eink = false
    var invert = false
    var theme = "indigo"
    var mode = "system"
    var ghToken = ""
    var target = "mp4"
    var height = 0
    var quality = "high"
    var removeAudio = false
    var gifSeconds = 10
    var gifWidth = 480
    var gifFps = 10
    var gifStart = 0

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
        } catch (e: Exception) {
        }
    }

    fun save() {
        try {
            file?.writeText(
                JSONObject().put("eink", eink).put("invert", invert).put("theme", theme).put("mode", mode)
                    .put("ghToken", ghToken).put("target", target).put("height", height).put("quality", quality)
                    .put("removeAudio", removeAudio).put("gifSeconds", gifSeconds).put("gifWidth", gifWidth)
                    .put("gifFps", gifFps).toString()
            )
        } catch (e: Exception) {
        }
    }
}

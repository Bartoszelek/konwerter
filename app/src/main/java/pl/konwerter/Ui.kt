package pl.konwerter

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Wspólne elementy wyglądu (kolory, przyciski, okienka) dla ekranów aplikacji. */
open class Ui : Activity() {
    val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    val inv get() = Store.eink && Store.invert
    open val bg get() = if (Store.eink) (if (inv) Color.BLACK else Color.WHITE) else Theme.bg
    open val surface get() = if (Store.eink) bg else Theme.surface
    open val fg get() = if (Store.eink) (if (inv) Color.WHITE else Color.BLACK) else Theme.fg
    open val dim get() = if (Store.eink) (if (inv) 0xFFAAAAAA.toInt() else 0xFF555555.toInt()) else Theme.dim
    open val lineC get() = if (Store.eink) fg else Theme.line
    open val accent get() = if (Store.eink) fg else Theme.accent
    open val onAccent get() = if (Store.eink) bg else Color.WHITE

    fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    fun tv(text: String, size: Float = 16f, bold: Boolean = false, color: Int = fg) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    fun round(fill: Int, radius: Int, stroke: Int = 0, strokeColor: Int = lineC) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke > 0) setStroke(dp(stroke), strokeColor)
    }

    fun btn(text: String, selected: Boolean = false, size: Float = 15f, onClick: () -> Unit) =
        tv(text, size, true, if (selected) onAccent else fg).apply {
            background = if (Store.eink) round(if (selected) fg else bg, 10, 2, fg)
            else round(if (selected) accent else surface, 14, if (selected) 0 else 1)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }

    fun primary(text: String, onClick: () -> Unit) = tv(text, 16f, true, onAccent).apply {
        background = if (Store.eink) round(accent, 10) else Theme.gradient(dp(18).toFloat())
        setPadding(dp(16), dp(15), dp(16), dp(15))
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    fun icon(res: Int, sizeDp: Int = 24, color: Int = fg, onClick: (() -> Unit)? = null) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(color)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        if (onClick != null) setOnClickListener { onClick() }
    }

    fun roundIcon(res: Int, onClick: () -> Unit): View {
        val f = FrameLayout(this)
        f.background = if (Store.eink) round(bg, 22, 2, fg) else round(surface, 22, 1)
        f.addView(icon(res, 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        f.setOnClickListener { onClick() }
        f.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        return f
    }

    fun vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    fun hbox() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun lp(w: Int = MATCH, hh: Int = WRAP, weight: Float = 0f, top: Int = 0) =
        LinearLayout.LayoutParams(w, hh, weight).apply { topMargin = dp(top) }

    fun panel(col: LinearLayout, top: Int = 8): LinearLayout {
        val p = vbox().apply {
            background = if (Store.eink) round(bg, 12, 2, fg) else round(surface, 18)
            setPadding(dp(16), dp(10), dp(16), dp(14))
        }
        col.addView(p, lp(top = top))
        return p
    }

    fun section(col: LinearLayout, title: String, top: Int = 22) {
        col.addView(tv(title.uppercase(), 12f, true, if (Store.eink) dim else accent).apply { letterSpacing = 0.08f }, lp(top = top))
    }

    fun seg(col: LinearLayout, label: String, opts: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
        if (label.isNotEmpty()) col.addView(tv(label, 15f), lp(top = 12))
        val r = hbox()
        opts.forEachIndexed { n, (text, sel) ->
            r.addView(btn(text, sel, 14f) { onPick(n) }, lp(WRAP, WRAP, top = 6).apply { rightMargin = dp(6) })
        }
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(r) })
    }

    fun row(col: LinearLayout, title: String, sub: String = "", right: View? = null, onLong: (() -> Unit)? = null, onClick: (() -> Unit)? = null) {
        val r = hbox().apply { setPadding(0, dp(10), 0, dp(10)) }
        val l = vbox()
        l.addView(tv(title, 15f, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        if (sub.isNotEmpty()) l.addView(tv(sub, 12f, color = dim).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        r.addView(l, lp(0, WRAP, 1f))
        if (right != null) r.addView(right)
        if (onClick != null) r.setOnClickListener { onClick() }
        if (onLong != null) r.setOnLongClickListener { onLong(); true }
        col.addView(r)
    }

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    /** Okno wysuwane od dołu. dark = ciemne (np. nad filmem). */
    fun sheet(title: String, dark: Boolean = false, build: (LinearLayout, Dialog) -> Unit): Dialog {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val bgc = if (dark) 0xFF1C1C1E.toInt() else bg
        val col = vbox().apply {
            setPadding(dp(18), dp(10), dp(18), dp(24))
            background = GradientDrawable().apply {
                setColor(bgc)
                cornerRadii = floatArrayOf(dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat(), 0f, 0f, 0f, 0f)
                if (Store.eink && !dark) setStroke(dp(3), fg)
            }
        }
        val head = hbox()
        head.addView(tv(title, 19f, true, if (dark) Color.WHITE else fg).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, lp(0, WRAP, 1f))
        head.addView(icon(R.drawable.ic_close, 26, if (dark) Color.WHITE else fg) { d.dismiss() })
        col.addView(head)
        build(col, d)
        val sv = ScrollView(this)
        sv.addView(col)
        d.setContentView(sv)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(MATCH, WRAP)
            setGravity(Gravity.BOTTOM)
            setWindowAnimations(0)
        }
        d.show()
        return d
    }

    /** Przycisk w ciemnym okienku nad filmem. */
    fun darkBtn(text: String, selected: Boolean = false, onClick: () -> Unit) = tv(text, 14f, true, if (selected) Color.BLACK else Color.WHITE).apply {
        background = round(if (selected) Color.WHITE else 0xFF2C2C2E.toInt(), 14)
        setPadding(dp(14), dp(11), dp(14), dp(11))
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }
    /** Okienka VLC: logowanie do udziału sieciowego, pytania, błędy. */
    fun showVlcDialog(d: Any, dark: Boolean = false) {
        fun get(name: String): String = try {
            d.javaClass.getMethod(name).invoke(d)?.toString() ?: ""
        } catch (e: Exception) {
            ""
        }
        fun b(text: String, sel: Boolean = false, f: () -> Unit): View = if (dark) darkBtn(text, sel, f) else btn(text, sel, 15f, f)
        val textColor = if (dark) Color.WHITE else fg
        when (d.javaClass.simpleName) {
            "ErrorMessage" -> toast(get("getTitle") + ": " + get("getText"))
            "LoginDialog" -> sheet(get("getTitle").ifEmpty { "Logowanie" }, dark) { col, dlg ->
                col.addView(tv(get("getText"), 14f, color = dim), lp(top = 6))
                val u = android.widget.EditText(this).apply {
                    setText(get("getDefaultUsername")); hint = "Użytkownik"; setTextColor(textColor); setHintTextColor(dim)
                }
                val p = android.widget.EditText(this).apply {
                    hint = "Hasło"; setTextColor(textColor); setHintTextColor(dim)
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                col.addView(u, lp(top = 8))
                col.addView(p, lp(top = 6))
                col.addView(b("Zaloguj", true) {
                    try {
                        d.javaClass.getMethod("postLogin", String::class.java, String::class.java, Boolean::class.javaPrimitiveType)
                            .invoke(d, u.text.toString(), p.text.toString(), true)
                    } catch (e: Exception) {
                    }
                    dlg.dismiss()
                }, lp(top = 10))
            }
            "QuestionDialog" -> sheet(get("getTitle"), dark) { col, dlg ->
                col.addView(tv(get("getText"), 14f, color = textColor), lp(top = 6))
                listOf(1 to get("getAction1Text"), 2 to get("getAction2Text")).filter { it.second.isNotEmpty() }.forEach { (k, label) ->
                    col.addView(b(label) {
                        try {
                            d.javaClass.getMethod("postAction", Int::class.javaPrimitiveType).invoke(d, k)
                        } catch (e: Exception) {
                        }
                        dlg.dismiss()
                    }, lp(top = 8))
                }
            }
        }
    }
}

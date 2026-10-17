package tech.drakar.wallpaper

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.text.NumberFormat

/**
 * The app's only screen: set the wallpaper, choose when to compute, see the witness's sigil and what it did, open
 * the privacy and terms pages.
 * Styled after the drakar design system (Charte): 4 dp grid, captions in uppercase, figures in mono, one primary
 * button in marine, brass only for the wordmark dot.
 */
class SettingsActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var units: TextView
    private val refresh = object : Runnable {
        override fun run() {
            val w = Hub.world
            status.text = if (Hub.link.ready) getString(R.string.status_connected, NumberFormat.getIntegerInstance().format(w.year), w.online) else getString(R.string.status_idle)
            units.text = NumberFormat.getIntegerInstance().format(Hub.prefs.units)
            ui.postDelayed(this, 1000)
        }
    }

    /** the wordmark's dot: a brass disc on the baseline, 3 dp after the r (sizes from the charte's 22 px wordmark) */
    private class DotSpan(private val d: Float, private val gapBefore: Float, private val gapAfter: Float) : ReplacementSpan() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Charte.BRASS }
        override fun getSize(p: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?) = (gapBefore + d + gapAfter).toInt()
        override fun draw(c: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, p: Paint) {
            c.drawCircle(x + gapBefore + d / 2, y - d / 2, d / 2, paint)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.init(this)
        val d = resources.displayMetrics.density
        fun px(dp: Int) = (dp * d).toInt()
        fun text(s: CharSequence, sp: Float, lineSp: Float, color: Int, face: android.graphics.Typeface, top: Int = 0) = TextView(this).apply {
            text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color); typeface = face
            lineHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, lineSp, resources.displayMetrics).toInt(); includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(top) }
        }
        // caption: 12/16, 600, uppercase, +0.08 em, ink-muted
        fun caption(s: String, top: Int) = text(s.uppercase(), 12f, 16f, Charte.INK_MUTED, Charte.sansSemibold, top).apply { letterSpacing = 0.08f }
        // a hairline above a block, as the charte's Stat rows
        fun rule(top: Int) = View(this).apply { setBackgroundColor(Charte.HAIRLINE); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { topMargin = px(top) } }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Charte.SURFACE_100)
            setPadding(px(24), px(64), px(24), px(32))
        }
        // wordmark as on drakar.tech: drakar + brass dot, a link back to it
        val mark = SpannableStringBuilder("drakar ").apply {
            setSpan(DotSpan(8 * d, 4 * d, 0f), 6, 7, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        col.addView(text(mark, 30f, 36f, Charte.INK, Charte.display).apply {
            letterSpacing = -0.035f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            isClickable = true; isFocusable = true; contentDescription = "drakar.tech"
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Config.HOME))) }
        })
        col.addView(text(getString(R.string.wallpaper_description), 16f, 26f, Charte.INK_MUTED, Charte.sans, top = 12))

        // primary action
        col.addView(TextView(this).apply {
            text = getString(R.string.set_wallpaper); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); typeface = Charte.sansSemibold; setTextColor(Charte.INK_INVERSE)
            gravity = Gravity.CENTER; setPadding(px(16), px(12), px(16), px(12)); isClickable = true; isFocusable = true
            val shape = GradientDrawable().apply { setColor(Charte.MARINE); cornerRadius = 8 * d }
            background = RippleDrawable(ColorStateList.valueOf(0x33ECEBE6), shape, null)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(24) }
            setOnClickListener {
                startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(this@SettingsActivity, DrakarWallpaperService::class.java)))
            }
        })

        // the witness
        col.addView(rule(32))
        col.addView(caption(getString(R.string.witness), 16))
        col.addView(text(Hub.prefs.sigil, 28f, 36f, Charte.INK, Charte.monoMedium, top = 4).apply { letterSpacing = 0.02f })
        status = text("", 14f, 22f, Charte.INK_MUTED, Charte.sans, top = 8); col.addView(status)
        col.addView(caption(getString(R.string.units_label), 16))
        units = text("", 20f, 28f, Charte.INK, Charte.monoMedium, top = 4); col.addView(units)

        // computing
        col.addView(rule(32))
        col.addView(caption(getString(R.string.compute_label), 16))
        if (!Hub.engine.supported) col.addView(text(getString(R.string.engine_unsupported), 14f, 22f, Charte.DANGER, Charte.sans, top = 8))
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        fun switch(label: String, on: Boolean, set: (Boolean) -> Unit) = Switch(this).apply {
            text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); typeface = Charte.sans; setTextColor(Charte.INK)
            thumbTintList = ColorStateList(states, intArrayOf(Charte.MARINE_TEXT, Charte.HAIRLINE_STRONG))
            trackTintList = ColorStateList(states, intArrayOf(Charte.MARINE, Charte.HAIRLINE))
            isChecked = on; switchPadding = px(16); setPadding(0, px(12), 0, px(12))
            setOnCheckedChangeListener { _, v -> set(v) }
        }
        col.addView(switch(getString(R.string.compute_visible), Hub.prefs.computeVisible) { Hub.prefs.computeVisible = it })
        col.addView(switch(getString(R.string.compute_charging), Hub.prefs.computeCharging) { Hub.prefs.computeCharging = it; ChargingWorker.schedule(this@SettingsActivity, it) })

        col.addView(rule(24))
        col.addView(text(getString(R.string.version, "${BuildConfig.VERSION_NAME} · ${BuildConfig.COMMIT}"), 12f, 16f, Charte.SLATE, Charte.mono, top = 16))

        // the legal pages on world.drakar.tech, in the browser, in the language of this screen
        val legal = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(4) }
        }
        fun legalLink(label: String, page: String) = TextView(this).apply {
            text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); typeface = Charte.sans; setTextColor(Charte.INK_MUTED)
            setPadding(0, px(12), px(16), px(12)); isClickable = true; isFocusable = true
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${Config.SITE}/$page?lang=${getString(R.string.legal_lang)}"))) }
        }
        legal.addView(legalLink(getString(R.string.privacy), "confidentialite"))
        legal.addView(legalLink(getString(R.string.terms), "conditions"))
        col.addView(legal)
        setContentView(ScrollView(this).apply { setBackgroundColor(Charte.SURFACE_100); addView(col) })
    }

    override fun onResume() { super.onResume(); ui.post(refresh) }
    override fun onPause() { ui.removeCallbacks(refresh); super.onPause() }
}

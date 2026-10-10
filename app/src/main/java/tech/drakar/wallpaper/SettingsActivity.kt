package tech.drakar.wallpaper

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.text.NumberFormat

/** The app's only screen: set the wallpaper, choose when to compute, see the witness's sigil and what it did. */
class SettingsActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var units: TextView
    private val refresh = object : Runnable {
        override fun run() {
            val w = Hub.world
            status.text = if (Hub.link.ready) getString(R.string.status_connected, NumberFormat.getIntegerInstance().format(w.year), w.online) else getString(R.string.status_idle)
            units.text = getString(R.string.units_done, Hub.prefs.units)
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.init(this)
        val d = resources.displayMetrics.density
        val ink = Color.rgb(0xC9, 0xC2, 0xB2); val dim = Color.rgb(0x9C, 0x97, 0x8A)
        fun text(s: String, size: Float, color: Int = ink, serif: Boolean = false) = TextView(this).apply {
            text = s; textSize = size; setTextColor(color); if (serif) typeface = Typeface.SERIF
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(7, 9, 12))
            setPadding((24 * d).toInt(), (48 * d).toInt(), (24 * d).toInt(), (24 * d).toInt())
        }
        col.addView(text("drakar.tech", 28f, ink, serif = true).apply { letterSpacing = 0.14f })
        col.addView(text(getString(R.string.wallpaper_description), 15f, dim, serif = true))
        col.addView(text("${getString(R.string.witness)} ${Hub.prefs.sigil}", 14f).apply { typeface = Typeface.MONOSPACE; letterSpacing = 0.14f })
        status = text("", 13f, dim); col.addView(status)
        units = text("", 13f, dim); col.addView(units)
        col.addView(Button(this).apply {
            text = getString(R.string.set_wallpaper)
            setOnClickListener {
                startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(this@SettingsActivity, DrakarWallpaperService::class.java)))
            }
        })
        if (!Hub.engine.supported) col.addView(text(getString(R.string.engine_unsupported), 13f, Color.rgb(0xD9, 0xA8, 0x5C)))
        col.addView(Switch(this).apply {
            text = getString(R.string.compute_visible); setTextColor(ink); isChecked = Hub.prefs.computeVisible
            setPadding(0, (12 * d).toInt(), 0, (12 * d).toInt())
            setOnCheckedChangeListener { _, v -> Hub.prefs.computeVisible = v }
        })
        col.addView(Switch(this).apply {
            text = getString(R.string.compute_charging); setTextColor(ink); isChecked = Hub.prefs.computeCharging
            setPadding(0, (12 * d).toInt(), 0, (12 * d).toInt())
            setOnCheckedChangeListener { _, v -> Hub.prefs.computeCharging = v; ChargingWorker.schedule(this@SettingsActivity, v) }
        })
        col.addView(text(getString(R.string.version, "${BuildConfig.VERSION_NAME} · ${BuildConfig.COMMIT}"), 11f, dim).apply { gravity = Gravity.START })
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.rgb(7, 9, 12)); addView(col) })
    }

    override fun onResume() { super.onResume(); ui.post(refresh) }
    override fun onPause() { ui.removeCallbacks(refresh); super.onPause() }
}

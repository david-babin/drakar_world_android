package tech.drakar.wallpaper

import android.content.Context
import java.security.SecureRandom

/** Persistent choices and counters, and the witness's sigil (stable per device). */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("drakar", Context.MODE_PRIVATE)

    val sigil: String
        get() = p.getString("sigil", null) ?: newSigil().also { p.edit().putString("sigil", it).apply() }

    var computeVisible: Boolean
        get() = p.getBoolean("computeVisible", true)
        set(v) = p.edit().putBoolean("computeVisible", v).apply()

    var computeCharging: Boolean
        get() = p.getBoolean("computeCharging", true)
        set(v) = p.edit().putBoolean("computeCharging", v).apply()

    val units: Int get() = p.getInt("units", 0)
    fun countUnit() = synchronized(Prefs::class.java) { p.edit().putInt("units", units + 1).apply() }

    private fun newSigil(): String {
        val abc = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val r = SecureRandom()
        val s = (0 until 8).map { abc[r.nextInt(abc.length)] }.joinToString("")
        return s.substring(0, 4) + "-" + s.substring(4)
    }
}

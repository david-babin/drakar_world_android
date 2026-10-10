package tech.drakar.wallpaper

import android.content.Context
import android.graphics.Typeface

/**
 * The drakar design system (charte graphique), theme "nuit": colours and type shared by the wallpaper and the
 * settings screen. Brass is rare (the wordmark dot, what is being computed, recent routes); slate carries the data.
 * Fonts (SIL Open Font License) live in res/font: Inter Tight 800 for the wordmark, Manrope for text,
 * JetBrains Mono for figures. Until [load] has run, system fallbacks are used.
 */
object Charte {
    const val SURFACE_100 = 0xFF07090C.toInt()
    const val SURFACE_200 = 0xFF0F1319.toInt()
    const val SURFACE_300 = 0xFF171D25.toInt()
    const val INK = 0xFFECEBE6.toInt()
    const val INK_MUTED = 0xFF9AA3AD.toInt()
    const val INK_INVERSE = 0xFFECEBE6.toInt()
    const val HAIRLINE = 0xFF232A33.toInt()
    const val HAIRLINE_STRONG = 0xFF5E6B7A.toInt()
    const val MARINE = 0xFF2E4468.toInt()
    const val MARINE_TEXT = 0xFFA9BDDC.toInt()
    const val BRASS = 0xFFD4A53A.toInt()
    const val SLATE = 0xFF8AA3B4.toInt()
    const val SUCCESS = 0xFF6FBB8D.toInt()
    const val DANGER = 0xFFE5796C.toInt()

    /** Globe colours as GL floats (r, g, b). */
    val GL_INK = floatArrayOf(0.925f, 0.922f, 0.902f)
    val GL_SLATE = floatArrayOf(0.541f, 0.639f, 0.706f)
    val GL_BRASS = floatArrayOf(0.831f, 0.647f, 0.227f)
    val GL_HAIRLINE_STRONG = floatArrayOf(0.369f, 0.420f, 0.478f)
    /** Lineage colours keep their hue, muted: saturation and lightness. */
    const val LINEAGE_S = 0.30f
    const val LINEAGE_L = 0.72f

    var display: Typeface = Typeface.create(Typeface.SANS_SERIF, 800, false); private set
    var sans: Typeface = Typeface.SANS_SERIF; private set
    var sansSemibold: Typeface = Typeface.create(Typeface.SANS_SERIF, 600, false); private set
    var sansBold: Typeface = Typeface.create(Typeface.SANS_SERIF, 700, false); private set
    var mono: Typeface = Typeface.MONOSPACE; private set
    var monoMedium: Typeface = Typeface.MONOSPACE; private set

    @Synchronized fun load(context: Context) {
        val r = context.resources
        runCatching {
            display = r.getFont(R.font.inter_tight_800)
            sans = r.getFont(R.font.manrope_400); sansSemibold = r.getFont(R.font.manrope_600); sansBold = r.getFont(R.font.manrope_700)
            mono = r.getFont(R.font.jetbrains_mono_400); monoMedium = r.getFont(R.font.jetbrains_mono_500)
        }
    }
}

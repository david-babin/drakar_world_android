package tech.drakar.wallpaper

import kotlin.math.cos
import kotlin.math.sin

/**
 * Parsers of the Earth data files (formats: docs/protocol.md §1), validated before anything reaches the GPU.
 * Lines come out as unit vectors (x, y, z per point), rings as (lon, lat) in degrees, unwrapped.
 */
object EarthData {
    class Line(val level: Int, val xyz: FloatArray) { val n get() = xyz.size / 3 }

    private class VarReader(private val b: ByteArray) {
        private var o = 0
        fun u(): Int {
            var v = 0L; var sh = 0
            while (true) {
                require(o < b.size && sh <= 28) { "unreadable Earth data" }
                val x = b[o++].toInt() and 0xff
                v = v or ((x and 0x7f).toLong() shl sh)
                if (x < 0x80) return v.toInt()
                sh += 7
            }
        }
        fun s(): Int { val v = u(); return if (v and 1 == 1) -((v + 1) ushr 1) else v ushr 1 }
    }

    /** coasts.bin (withLevel = false) and contours.bin (withLevel = true): deltas in 1/100° */
    fun lines(bytes: ByteArray, withLevel: Boolean): List<Line> {
        val r = VarReader(bytes); val n = r.u()
        require(n in 0..100_000) { "unreadable Earth data" }
        val out = ArrayList<Line>(n)
        repeat(n) {
            val level = if (withLevel) r.u() else 0
            val m = r.u(); require(m in 0..65_536) { "unreadable Earth data" }
            val xyz = FloatArray(m * 3); var px = 0; var py = 0
            for (j in 0 until m) {
                px += r.s(); py += r.s()
                require(py in -9000..9000 && px in -18000..18000) { "unreadable Earth data" }
                val lo = Math.toRadians(px / 100.0); val la = Math.toRadians(py / 100.0); val cl = cos(la)
                xyz[3 * j] = (cl * cos(lo)).toFloat(); xyz[3 * j + 1] = (cl * sin(lo)).toFloat(); xyz[3 * j + 2] = sin(la).toFloat()
            }
            out += Line(level, xyz)
        }
        return out
    }

    /** land.bin: rings for the stencil fill, (lon, lat) degrees, deltas in 1/60° */
    fun rings(bytes: ByteArray): List<FloatArray> {
        val r = VarReader(bytes); val n = r.u()
        require(n in 0..100_000) { "unreadable Earth data" }
        val out = ArrayList<FloatArray>(n)
        repeat(n) {
            val m = r.u(); require(m in 0..(1 shl 20)) { "unreadable Earth data" }
            val ring = FloatArray(m * 2); var px = 0; var py = 0
            for (j in 0 until m) {
                px += r.s(); py += r.s()
                require(py in -5400..5400 && px in -21600..32400) { "unreadable Earth data" }
                ring[2 * j] = px / 60f; ring[2 * j + 1] = py / 60f
            }
            out += ring
        }
        return out
    }
}

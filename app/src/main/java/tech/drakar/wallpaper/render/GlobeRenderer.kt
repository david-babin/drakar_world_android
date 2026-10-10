package tech.drakar.wallpaper.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES30.*
import android.opengl.GLUtils
import tech.drakar.wallpaper.Config
import tech.drakar.wallpaper.EarthData
import tech.drakar.wallpaper.WorldModel
import kotlin.math.*

/** what the renderer needs from the Earth data, ready to upload */
class Earth(val rings: List<FloatArray>, val coasts: List<EarthData.Line>, val contours: List<EarthData.Line>, val base: Bitmap)

/**
 * The globe as on the page, natively: terrain classes and relief (base.png), vector land (stencil-parity mask),
 * coasts and contour lines, then life (routes, regions being computed, settlements, groups, cities) and the
 * brand with the year. Draws only when something visible changed: the camera (at most [Config.FPS_MOVING] frames a
 * second) or the world (at most once a second).
 */
class GlobeRenderer(
    private val earth: () -> Earth?,
    private val world: WorldModel,
    private val density: Float,
    private val yearText: (Int) -> String,
) {
    private var ready = false
    private lateinit var globe: Program; private lateinit var fill: Program; private lateinit var white: Program
    private lateinit var line: Program; private lateinit var point: Program; private lateinit var text: Program
    private var quad = 0; private var texBase = 0; private var texMask = 0; private var texText = 0
    private class LineSet(val vb: Int, val ib: Int, val count: Int, val color: FloatArray, val width: Float)
    private val staticLines = ArrayList<LineSet>()
    private var dynLines: LineSet? = null
    private var pointsVb = 0; private var pointsCount = 0
    private var builtVersion = -1
    private var shown: WorldModel.Frame? = null
    private var textYear = Int.MIN_VALUE; private var textW = 0; private var textH = 0

    // camera
    private var lon = Math.toRadians(20.0); private var lat = Math.toRadians(18.0)
    private var target: DoubleArray? = null
    private var lastFrame = 0L; private var lastSig = ""; private var lastVersion = -1; private var dataDrawnAt = 0L

    /** returns true when it drew (the caller then swaps buffers) */
    fun frame(now: Long, w: Int, h: Int, force: Boolean): Boolean {
        val e = earth()
        if (e == null) { glViewport(0, 0, w, h); glClearColor(0.027f, 0.035f, 0.047f, 1f); glClear(GL_COLOR_BUFFER_BIT); return force }
        if (!ready) init(e)
        val dt = if (lastFrame == 0L) 0.0 else ((now - lastFrame).coerceIn(0, 1000)) / 1000.0
        lastFrame = now
        moveCamera(world.focus(), dt)
        val R = min(w, h) * 0.44f; val cx = w / 2f; val cy = h / 2f
        val sig = "%.4f|%.4f|%dx%d".format(lon, lat, w, h)
        val camMoved = sig != lastSig
        val dataMoved = world.version != lastVersion && now - dataDrawnAt >= Config.DATA_REDRAW_MS
        if (!force && !camMoved && !dataMoved) return false
        lastSig = sig
        if (world.version != lastVersion) { lastVersion = world.version; dataDrawnAt = now }
        if (builtVersion != lastVersion || dynLines == null) { val f = world.frame(); buildLife(f); shown = f; builtVersion = lastVersion }
        val f = shown ?: return false

        glViewport(0, 0, w, h)
        glDisable(GL_BLEND)
        drawGlobe(w, h, R, f.climate)
        val M = cameraMatrix()
        glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        line.use()
        glUniformMatrix3fv(line.u("M"), 1, false, M, 0)
        glUniform2f(line.u("res"), w.toFloat(), h.toFloat()); glUniform2f(line.u("center"), cx, cy); glUniform1f(line.u("R"), R)
        for (s in staticLines) drawLines(s, 8)
        dynLines?.let { drawLines(it, 12) }
        drawPoints(M, w, h, cx, cy, R)
        drawText(f.year, w, h)
        glDisable(GL_BLEND)
        return true
    }

    fun release() {
        // the EGL context is destroyed with the surface: GL objects go with it
        ready = false; staticLines.clear(); dynLines = null; pointsVb = 0; builtVersion = -1; textYear = Int.MIN_VALUE; lastSig = ""
    }

    // ---------- camera: glides to the region being computed (ours, otherwise another witness's), else turns ----------
    private fun moveCamera(reg: Int?, dt: Double) {
        if (reg != null) target = doubleArrayOf(Math.toRadians((reg % 6 + 0.5) * 60 - 180), Math.toRadians(90 - (reg / 6 + 0.5) * 45))
        val t = target
        if (t != null) {
            val k = 1 - (1 - 0.012).pow(dt * 60)
            var dl = t[0] - lon; dl = atan2(sin(dl), cos(dl))
            lon += dl * k; lat += (t[1].coerceIn(-LAT, LAT) - lat) * k
        } else lon += 0.021 * dt
    }

    private fun cameraMatrix(): FloatArray {
        val c = cos(lon).toFloat(); val s = sin(lon).toFloat(); val cl = cos(lat).toFloat(); val sl = sin(lat).toFloat()
        return floatArrayOf(-s, -sl * c, cl * c, c, -sl * s, cl * s, 0f, cl, sl)   // column-major, as on the page
    }

    // ---------- one-time GPU setup ----------
    private fun init(e: Earth) {
        globe = Program(Shaders.GLOBE_VS, Shaders.GLOBE_FS)
        fill = Program(Shaders.FILL_VS, Shaders.WHITE_FS)
        white = Program(Shaders.QUAD_VS, Shaders.WHITE_FS)
        line = Program(Shaders.LINE_VS, Shaders.LINE_FS)
        point = Program(Shaders.POINT_VS, Shaders.POINT_FS)
        text = Program(Shaders.TEXT_VS, Shaders.TEXT_FS)
        quad = genBuffer(); glBindBuffer(GL_ARRAY_BUFFER, quad)
        glBufferData(GL_ARRAY_BUFFER, 32, floats(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)), GL_STATIC_DRAW)

        texBase = genTexture(); glBindTexture(GL_TEXTURE_2D, texBase)
        texParams(GL_NEAREST, GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GL_TEXTURE_2D, 0, e.base, 0)
        buildMask(e.rings)
        for (lv in 0 until 5) staticLines += staticSet(e.contours.filter { it.level == lv }, floatArrayOf(0.79f, 0.76f, 0.70f, 0.10f + lv * 0.05f), 0.8f)
        staticLines += staticSet(e.coasts, floatArrayOf(0.79f, 0.76f, 0.70f, 0.55f), 1.2f)
        texText = genTexture()
        ready = true
    }

    private fun texParams(filter: Int, wrapS: Int) {
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrapS); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    }

    /** land: the Natural Earth rings filled by stencil parity into an equirectangular R8 texture, once */
    private fun buildMask(rings: List<FloatArray>) {
        val max = IntArray(1); glGetIntegerv(GL_MAX_TEXTURE_SIZE, max, 0)
        val mw = min(Config.MASK_WIDTH, max[0]); val mh = mw / 2
        var tri = 0; for (r in rings) tri += max(0, r.size / 2 - 2)
        val data = FloatArray(tri * 6); var o = 0
        for (r in rings) { val ax = r[0]; val ay = r[1]; for (i in 1 until r.size / 2 - 1) { data[o++] = ax; data[o++] = ay; data[o++] = r[2 * i]; data[o++] = r[2 * i + 1]; data[o++] = r[2 * i + 2]; data[o++] = r[2 * i + 3] } }
        val vb = genBuffer(); glBindBuffer(GL_ARRAY_BUFFER, vb); glBufferData(GL_ARRAY_BUFFER, data.size * 4, floats(data), GL_STATIC_DRAW)
        texMask = genTexture(); glBindTexture(GL_TEXTURE_2D, texMask); texParams(GL_LINEAR, GL_REPEAT)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, mw, mh, 0, GL_RED, GL_UNSIGNED_BYTE, null)
        val fb = IntArray(1); glGenFramebuffers(1, fb, 0); glBindFramebuffer(GL_FRAMEBUFFER, fb[0])
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texMask, 0)
        val rb = IntArray(1); glGenRenderbuffers(1, rb, 0); glBindRenderbuffer(GL_RENDERBUFFER, rb[0])
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, mw, mh)
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, rb[0])
        glViewport(0, 0, mw, mh); glClearColor(0f, 0f, 0f, 1f); glClearStencil(0); glClear(GL_COLOR_BUFFER_BIT or GL_STENCIL_BUFFER_BIT)
        fill.use(); val ll = fill.a("ll"); glEnableVertexAttribArray(ll); glVertexAttribPointer(ll, 2, GL_FLOAT, false, 0, 0)
        glEnable(GL_STENCIL_TEST); glColorMask(false, false, false, false)
        glStencilFunc(GL_ALWAYS, 0, 0xff); glStencilOp(GL_KEEP, GL_KEEP, GL_INVERT)
        for (off in floatArrayOf(0f, -360f)) { glUniform1f(fill.u("off"), off); glDrawArrays(GL_TRIANGLES, 0, tri * 3) }   // rings unwrapped past 180°
        glColorMask(true, true, true, true); glStencilFunc(GL_EQUAL, 1, 0xff); glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP)
        glDisableVertexAttribArray(ll)
        white.use(); glBindBuffer(GL_ARRAY_BUFFER, quad); val p = white.a("p"); glEnableVertexAttribArray(p); glVertexAttribPointer(p, 2, GL_FLOAT, false, 0, 0)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4); glDisableVertexAttribArray(p)
        glDisable(GL_STENCIL_TEST)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glDeleteRenderbuffers(1, rb, 0); glDeleteFramebuffers(1, fb, 0); deleteBuffer(vb)
    }

    // ---------- lines ----------
    /** static set: per vertex a0, a1, corner (8 floats); colour constant */
    private fun staticSet(lines: List<EarthData.Line>, color: FloatArray, width: Float): LineSet {
        var segs = 0; for (l in lines) segs += max(0, l.n - 1)
        val v = FloatArray(segs * 4 * 8); val ix = IntArray(segs * 6); var s = 0
        for (l in lines) for (j in 0 until l.n - 1) {
            for (k in 0 until 4) {
                val o = (s * 4 + k) * 8
                for (c in 0 until 3) { v[o + c] = l.xyz[3 * j + c]; v[o + 3 + c] = l.xyz[3 * (j + 1) + c] }
                v[o + 6] = (k shr 1).toFloat(); v[o + 7] = if (k and 1 == 1) 1f else -1f
            }
            val b = s * 4; val q = s * 6
            ix[q] = b; ix[q + 1] = b + 1; ix[q + 2] = b + 2; ix[q + 3] = b + 2; ix[q + 4] = b + 1; ix[q + 5] = b + 3
            s++
        }
        return upload(v, ix, segs, color, width)
    }

    private fun upload(v: FloatArray, ix: IntArray, segs: Int, color: FloatArray, width: Float): LineSet {
        val vb = genBuffer(); glBindBuffer(GL_ARRAY_BUFFER, vb); glBufferData(GL_ARRAY_BUFFER, max(4, v.size * 4), floats(v), GL_STATIC_DRAW)
        val ib = genBuffer(); glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ib); glBufferData(GL_ELEMENT_ARRAY_BUFFER, max(4, ix.size * 4), ints(ix), GL_STATIC_DRAW)
        return LineSet(vb, ib, segs, color, width)
    }

    private fun drawLines(s: LineSet, stride: Int) {
        if (s.count == 0) return
        val a0 = line.a("a0"); val a1 = line.a("a1"); val corner = line.a("corner"); val color = line.a("color")
        glBindBuffer(GL_ARRAY_BUFFER, s.vb)
        glEnableVertexAttribArray(a0); glEnableVertexAttribArray(a1); glEnableVertexAttribArray(corner)
        glVertexAttribPointer(a0, 3, GL_FLOAT, false, stride * 4, 0)
        glVertexAttribPointer(a1, 3, GL_FLOAT, false, stride * 4, 12)
        glVertexAttribPointer(corner, 2, GL_FLOAT, false, stride * 4, 24)
        if (stride == 12) { glEnableVertexAttribArray(color); glVertexAttribPointer(color, 4, GL_FLOAT, false, 48, 32) }
        else { glDisableVertexAttribArray(color); glVertexAttrib4f(color, s.color[0], s.color[1], s.color[2], s.color[3]) }
        glUniform1f(line.u("hw"), s.width * density / 2)
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, s.ib)
        glDrawElements(GL_TRIANGLES, s.count * 6, GL_UNSIGNED_INT, 0)
        glDisableVertexAttribArray(a0); glDisableVertexAttribArray(a1); glDisableVertexAttribArray(corner); glDisableVertexAttribArray(color)
    }

    // ---------- life ----------
    private fun buildLife(f: WorldModel.Frame) {
        // routes (great circles, sea lanes dashed) and the regions being computed, one dynamic line set
        val v = FloatList(4096); val ix = ArrayList<Int>(); var segs = 0
        fun seg(a: FloatArray, b: FloatArray, c: FloatArray) {
            for (k in 0 until 4) { v.add(a[0], a[1], a[2], b[0], b[1], b[2], (k shr 1).toFloat(), if (k and 1 == 1) 1f else -1f, c[0], c[1], c[2], c[3]) }
            val base = segs * 4; ix.addAll(listOf(base, base + 1, base + 2, base + 2, base + 1, base + 3)); segs++
        }
        val byId = f.settlements.associateBy { it.id }
        val tickNow = f.year * 4
        for (r in f.routes) {
            val a = byId[r.a] ?: continue; val b = byId[r.b] ?: continue
            val age = min(1f, (tickNow - r.tick) / 600f)
            val c = hsl(f.hues.getOrElse(r.lin) { 0 }.toFloat(), 0.45f, 0.70f, 0.18f + 0.3f * age)
            val pts = geodesic(cell(a.x.toFloat(), a.y.toFloat()), cell(b.x.toFloat(), b.y.toFloat()), if (r.sea) 2 else 1)
            for (i in 0 until pts.size - 1) if (!r.sea || i % 2 == 0) seg(pts[i], pts[i + 1], c)
        }
        fun box(reg: Int, c: FloatArray) {
            val x0 = (reg % 6) * 64f; val y0 = (reg / 6) * 48f
            val corners = arrayOf(floatArrayOf(x0, y0), floatArrayOf(x0 + 64, y0), floatArrayOf(x0 + 64, y0 + 48), floatArrayOf(x0, y0 + 48), floatArrayOf(x0, y0))
            for (i in 0 until 4) for (j in 0 until 16) {
                val (ax, ay) = corners[i].let { it[0] to it[1] }; val (bx, by) = corners[i + 1].let { it[0] to it[1] }
                seg(cell(ax + (bx - ax) * j / 16 - 0.5f, ay + (by - ay) * j / 16 - 0.5f), cell(ax + (bx - ax) * (j + 1) / 16 - 0.5f, ay + (by - ay) * (j + 1) / 16 - 0.5f), c)
            }
        }
        for (p in f.peers) box(p.region, floatArrayOf(0.79f, 0.76f, 0.70f, 0.26f))
        if (f.ownRegion >= 0) box(f.ownRegion, floatArrayOf(0.85f, 0.66f, 0.36f, 0.6f))
        dynLines?.let { deleteBuffer(it.vb); deleteBuffer(it.ib) }
        dynLines = upload(v.toArray(), ix.toIntArray(), segs, FLOAT0, 0.9f)

        // settlements, groups, settlement areas (villages → cities)
        val pts = FloatList(8192)
        fun pt(xyz: FloatArray, c: FloatArray, sizeDp: Float, kind: Float, thick: Float) { pts.add(xyz[0], xyz[1], xyz[2], c[0], c[1], c[2], c[3], sizeDp * density, kind, thick) }
        for (s in f.settlements) pt(cell(s.x.toFloat(), s.y.toFloat()), hsl(f.hues.getOrElse(s.lin) { 0 }.toFloat(), 0.55f, 0.62f, 1f), 5f, 2f, 0.4f)
        val live = HashSet<Int>(); f.ownAgents.forEach { live += it[0] }; f.peers.forEach { p -> live += p.agents.keys }
        val agentDot = { x: Int, y: Int, lin: Int, pop: Int, alpha: Float ->
            pt(cell(x.toFloat(), y.toFloat()), hsl(f.hues.getOrElse(lin) { 0 }.toFloat(), 0.6f, 0.66f, alpha), 2 * min(4f, 1 + sqrt(pop.toFloat()) * 0.2f), 0f, 0f)
        }
        for (area in Urban.areas(f.agents.filter { it.id !in live && it.pop > 0 })) {
            val tier = area.pop / Config.CITY_SOULS
            if (tier == 0) { for (a in area.members) agentDot(a.x, a.y, a.lin, a.pop, 0.8f); continue }
            for (a in area.members) agentDot(a.x, a.y, a.lin, a.pop, 0.28f)
            val r = 3.4f + 1.8f * sqrt(tier.toFloat()); val at = cell(area.x, area.y); val h = f.hues.getOrElse(area.lin) { 0 }.toFloat()
            pt(at, hsl(h, 0.6f, 0.68f, 0.9f), 2 * r, 0f, 0f)
            val rings = min(3, ceil(ln(tier + 1.0) / ln(2.0)).toInt())
            for (i in 0 until rings) { val rr = r + 2.5f + i * 2.5f; pt(at, hsl(h, 0.6f, 0.68f, 0.35f), 2 * rr, 1f, 0.8f / rr) }
        }
        for (p in f.peers) for (a in p.agents.values) if (a[4] > 0) agentDot(a[1], a[2], a[3], a[4], 0.9f)
        for (a in f.ownAgents) if (a[4] > 0) agentDot(a[1], a[2], a[3], a[4], 1f)
        if (pointsVb == 0) pointsVb = genBuffer()
        glBindBuffer(GL_ARRAY_BUFFER, pointsVb); glBufferData(GL_ARRAY_BUFFER, max(4, pts.size * 4), floats(pts.toArray()), GL_DYNAMIC_DRAW)
        pointsCount = pts.size / 10
    }

    private fun drawPoints(M: FloatArray, w: Int, h: Int, cx: Float, cy: Float, R: Float) {
        if (pointsCount == 0) return
        point.use()
        glUniformMatrix3fv(point.u("M"), 1, false, M, 0)
        glUniform2f(point.u("res"), w.toFloat(), h.toFloat()); glUniform2f(point.u("center"), cx, cy); glUniform1f(point.u("R"), R)
        val pos = point.a("pos"); val color = point.a("color"); val style = point.a("style")
        glBindBuffer(GL_ARRAY_BUFFER, pointsVb)
        glEnableVertexAttribArray(pos); glEnableVertexAttribArray(color); glEnableVertexAttribArray(style)
        glVertexAttribPointer(pos, 3, GL_FLOAT, false, 40, 0)
        glVertexAttribPointer(color, 4, GL_FLOAT, false, 40, 12)
        glVertexAttribPointer(style, 3, GL_FLOAT, false, 40, 28)
        glDrawArrays(GL_POINTS, 0, pointsCount)
        glDisableVertexAttribArray(pos); glDisableVertexAttribArray(color); glDisableVertexAttribArray(style)
    }

    private fun drawGlobe(w: Int, h: Int, R: Float, climate: Float) {
        globe.use()
        glBindBuffer(GL_ARRAY_BUFFER, quad); val p = globe.a("p"); glEnableVertexAttribArray(p); glVertexAttribPointer(p, 2, GL_FLOAT, false, 0, 0)
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, texBase); glUniform1i(globe.u("base"), 0)
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, texMask); glUniform1i(globe.u("mask"), 1)
        glUniform2f(globe.u("res"), w.toFloat(), h.toFloat()); glUniform2f(globe.u("center"), 0f, 0f); glUniform1f(globe.u("R"), R)
        glUniform1f(globe.u("lon0"), lon.toFloat()); glUniform1f(globe.u("lat0"), lat.toFloat()); glUniform1f(globe.u("climate"), climate)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        glDisableVertexAttribArray(p)
        glActiveTexture(GL_TEXTURE0)
    }

    // ---------- brand and year (rendered with Android's text engine into a texture, when the year changes) ----------
    private fun drawText(year: Int, w: Int, h: Int) {
        if (year != textYear) {
            textYear = year
            val brand = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.SERIF; textSize = 24 * density; color = 0xFFC9C2B2.toInt(); letterSpacing = 0.14f }
            val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.SERIF; textSize = 14 * density; color = 0xFFC9C2B2.toInt(); letterSpacing = 0.08f }
            val yt = yearText(year)
            textW = (max(brand.measureText("drakar.tech"), small.measureText(yt)) + 4 * density).toInt()
            textH = (48 * density).toInt()
            val bmp = Bitmap.createBitmap(textW, textH, Bitmap.Config.ARGB_8888)
            val dp = density                                     // (inside Canvas.apply, `density` would be the canvas's)
            Canvas(bmp).apply { drawText("drakar.tech", 0f, 24 * dp, brand); drawText(yt, 0f, 44 * dp, small) }
            glBindTexture(GL_TEXTURE_2D, texText); texParams(GL_LINEAR, GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GL_TEXTURE_2D, 0, bmp, 0); bmp.recycle()
        }
        val x = 20 * density; val y = 56 * density                 // below the status bar
        val quadXY = floatArrayOf(x, y, 0f, 0f, x + textW, y, 1f, 0f, x, y + textH, 0f, 1f, x + textW, y + textH, 1f, 1f)
        text.use()
        val vb = genBuffer(); glBindBuffer(GL_ARRAY_BUFFER, vb); glBufferData(GL_ARRAY_BUFFER, 64, floats(quadXY), GL_STREAM_DRAW)
        val p = text.a("p"); val uv = text.a("uv")
        glEnableVertexAttribArray(p); glEnableVertexAttribArray(uv)
        glVertexAttribPointer(p, 2, GL_FLOAT, false, 16, 0); glVertexAttribPointer(uv, 2, GL_FLOAT, false, 16, 8)
        glUniform2f(text.u("res"), w.toFloat(), h.toFloat())
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, texText); glUniform1i(text.u("tex"), 0)
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)               // Android bitmaps are premultiplied
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDisableVertexAttribArray(p); glDisableVertexAttribArray(uv); deleteBuffer(vb)
    }

    companion object {
        private const val LAT = 80 * PI / 180
        private val FLOAT0 = floatArrayOf(0f, 0f, 0f, 0f)
        private val COS_LAT = FloatArray(192) { cos(Math.toRadians(90 - (it + 0.5) / 192 * 180)).toFloat() }

        /** a (possibly fractional) cell → unit vector */
        fun cell(x: Float, y: Float): FloatArray {
            val lo = Math.toRadians(((x + 0.5) / 384) * 360 - 180); val la = Math.toRadians(90 - ((y + 0.5) / 192) * 180)
            val cl = cos(la)
            return floatArrayOf((cl * cos(lo)).toFloat(), (cl * sin(lo)).toFloat(), sin(la).toFloat())
        }

        /** great circle from a to b, as points (slerp), finer for dashed lanes */
        fun geodesic(a: FloatArray, b: FloatArray, fine: Int): List<FloatArray> {
            val d = acos((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1f, 1f).toDouble()).let { if (it < 1e-6) 1e-6 else it }
            val n = max(2, min(48 * fine, ceil(d / Math.toRadians(2.0 / fine)).toInt()))
            return (0..n).map { i ->
                val t = i.toDouble() / n; val s1 = sin((1 - t) * d) / sin(d); val s2 = sin(t * d) / sin(d)
                floatArrayOf((s1 * a[0] + s2 * b[0]).toFloat(), (s1 * a[1] + s2 * b[1]).toFloat(), (s1 * a[2] + s2 * b[2]).toFloat())
            }
        }

        /** CSS-like hsla → rgba (h in degrees) */
        fun hsl(h: Float, s: Float, l: Float, a: Float): FloatArray {
            val c = (1 - abs(2 * l - 1)) * s; val hp = ((h % 360 + 360) % 360) / 60; val x = c * (1 - abs(hp % 2 - 1))
            val (r, g, b) = when { hp < 1 -> Triple(c, x, 0f); hp < 2 -> Triple(x, c, 0f); hp < 3 -> Triple(0f, c, x); hp < 4 -> Triple(0f, x, c); hp < 5 -> Triple(x, 0f, c); else -> Triple(c, 0f, x) }
            val m = l - c / 2
            return floatArrayOf(r + m, g + m, b + m, a)
        }
    }
}

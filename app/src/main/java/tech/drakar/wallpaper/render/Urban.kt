package tech.drakar.wallpaper.render

import tech.drakar.wallpaper.Config
import tech.drakar.wallpaper.WorldModel
import kotlin.math.abs
import kotlin.math.min

/** Settlement areas, as on the page: groups of one lineage linked within [Config.URBAN_LINK] cells (single linkage). */
object Urban {
    class Area(val lin: Int, val pop: Int, val members: List<WorldModel.Agent>, val x: Float, val y: Float)

    fun areas(agents: List<WorldModel.Agent>): List<Area> {
        val link = Config.URBAN_LINK; val gx = (384 + link - 1) / link
        val cells = HashMap<Long, MutableList<WorldModel.Agent>>()
        val key = { x: Int, y: Int -> x.toLong() * 100_000 + y }
        for (a in agents) cells.getOrPut(key(a.x / link, a.y / link)) { ArrayList() } += a
        val seen = HashSet<Int>(); val out = ArrayList<Area>()
        for (a0 in agents) {
            if (!seen.add(a0.id)) continue
            val members = ArrayList<WorldModel.Agent>(); val stack = ArrayDeque<WorldModel.Agent>(); stack += a0
            while (stack.isNotEmpty()) {
                val a = stack.removeLast(); members += a
                val cx = a.x / link; val cy = a.y / link
                for (j in -1..1) for (i in -1..1) for (b in cells[key(((cx + i) % gx + gx) % gx, cy + j)] ?: continue) {
                    if (b.lin != a0.lin || b.id in seen) continue
                    val dx = abs(b.x - a.x).let { min(it, 384 - it) }
                    if (dx <= link && abs(b.y - a.y) <= link) { seen += b.id; stack += b }
                }
            }
            var pop = 0; var core = members[0]
            for (m in members) { pop += m.pop; if (m.pop > core.pop) core = m }
            var sx = 0.0; var sy = 0.0
            for (m in members) { var dx = (m.x - core.x).toDouble(); if (dx > 192) dx -= 384 else if (dx < -192) dx += 384; sx += dx * m.pop; sy += (m.y - core.y).toDouble() * m.pop }
            out += Area(a0.lin, pop, members, (core.x + sx / pop).toFloat(), (core.y + sy / pop).toFloat())
        }
        return out
    }
}

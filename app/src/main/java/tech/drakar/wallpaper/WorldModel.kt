package tech.drakar.wallpaper

import org.json.JSONArray
import org.json.JSONObject

/**
 * The app's copy of the shared world: the `hello` snapshot, then the numbered deltas (docs/protocol.md §3),
 * plus what the witnesses are computing right now. Thread-safe; [version] moves whenever something drawn changed.
 */
class WorldModel {
    class Agent(val id: Int, val x: Int, val y: Int, val lin: Int, val pop: Int)
    class Settlement(val id: Int, val x: Int, val y: Int, var lin: Int, val name: String, val founded: Int)
    class Route(val a: Int, val b: Int, val lin: Int, val tick: Int, val sea: Boolean)
    /** a group being computed live: [id, x, y, lin, pop] */
    class Peer(var region: Int, var step: Int, val agents: HashMap<Int, IntArray> = HashMap(), var at: Long = 0)

    /** an immutable view for one frame */
    class Frame(
        val agents: List<Agent>, val settlements: List<Settlement>, val routes: List<Route>, val hues: IntArray,
        val peers: List<Peer>, val ownRegion: Int, val ownAgents: List<IntArray>, val year: Int, val tick: Int, val climate: Float,
    )

    private val agents = LinkedHashMap<Int, Agent>()
    private val settlements = LinkedHashMap<Int, Settlement>()
    private val routes = ArrayList<Route>()
    private var hues = IntArray(16) { it * 22 }
    private var regionTicks = IntArray(24)
    private val peers = HashMap<String, Peer>()
    private var ownRegion = -1
    private var ownAgents: List<IntArray> = emptyList()

    @Volatile var version = 0; private set
    @Volatile var seq = -1; private set
    @Volatile var loaded = false; private set
    @Volatile var climate = 0f; private set
    @Volatile var online = 1; private set

    // as on the page: the year of the most advanced region (no chronicle entry is dated after it), the tick of the least advanced
    val year: Int get() = synchronized(this) { (regionTicks.maxOrNull() ?: 0) / 4 }
    val tick: Int get() = synchronized(this) { regionTicks.minOrNull() ?: 0 }
    val agentCount: Int get() = synchronized(this) { agents.size }
    val settlementCount: Int get() = synchronized(this) { settlements.size }
    val routeCount: Int get() = synchronized(this) { routes.size }
    val population: Long get() = synchronized(this) { agents.values.sumOf { it.pop.toLong() } }

    private fun agent(a: JSONArray) = Agent(a.getInt(0), a.getInt(1), a.getInt(2), a.getInt(7), a.getInt(5))
    private fun settlement(s: JSONArray) = Settlement(s.getInt(0), s.getInt(1), s.getInt(2), s.getInt(3), s.optString(5), s.getInt(4))
    private fun route(r: JSONArray) = Route(r.getInt(0), r.getInt(1), r.getInt(2), r.getInt(3), r.optInt(4) == 1)

    /** `hello.world` / `world.world` */
    fun loadSnapshot(w: JSONObject) = synchronized(this) {
        agents.clear(); settlements.clear(); routes.clear()
        w.getJSONArray("agents").forEachArray { val a = agent(it); agents[a.id] = a }
        w.getJSONArray("settlements").forEachArray { val s = settlement(it); settlements[s.id] = s }
        w.getJSONArray("routes").forEachArray { routes += route(it) }
        val lin = w.getJSONArray("lineages")
        hues = IntArray(lin.length()) { lin.getJSONArray(it).getInt(2) }
        val reg = w.getJSONArray("regions")
        regionTicks = IntArray(reg.length()) { reg.getJSONArray(it).getInt(1) }
        seq = w.getInt("seq"); climate = w.optDouble("climate", 0.0).toFloat(); online = w.optInt("online", 1)
        loaded = true; version++
    }

    /** applies a `delta`; false on a sequence gap (the caller then asks for `resync`) */
    fun applyDelta(d: JSONObject): Boolean = synchronized(this) {
        if (!loaded || d.getInt("seq") != seq + 1) return false
        d.getJSONArray("removed").forEachInt { agents.remove(it) }
        d.getJSONArray("agents").forEachArray { val a = agent(it); agents[a.id] = a }
        d.optJSONArray("removedSettlements")?.let { gone ->
            val ids = HashSet<Int>(); gone.forEachInt { ids += it; settlements.remove(it) }
            routes.removeAll { it.a in ids || it.b in ids }
        }
        d.optJSONArray("changed")?.forEachArray { c -> settlements[c.getInt(0)]?.lin = c.getInt(1) }
        d.getJSONArray("settlements").forEachArray { val s = settlement(it); settlements[s.id] = s }
        d.getJSONArray("routes").forEachArray { routes += route(it) }
        val r = d.getInt("region"); if (r in regionTicks.indices) regionTicks[r] = d.getInt("regionTick")
        d.optJSONArray("regions")?.forEachArray { rr -> val id = rr.getInt(0); if (id in regionTicks.indices) regionTicks[id] = rr.getInt(1) }
        seq = d.getInt("seq"); version++
        return true
    }

    fun market(m: JSONObject) = synchronized(this) { climate = m.optDouble("climate", climate.toDouble()).toFloat(); version++ }
    fun online(n: Int) { online = n }

    // ---- witnesses computing right now ----
    fun peerLease(sigil: String, region: Int) = synchronized(this) { peers[sigil] = Peer(region, 0, at = System.currentTimeMillis()); version++ }
    fun peerRelease(sigil: String) = synchronized(this) { if (peers.remove(sigil) != null) version++ }
    fun peerProgress(m: JSONObject) = synchronized(this) {
        val sigil = m.getString("sigil")
        val p = peers.getOrPut(sigil) { Peer(m.getInt("region"), 0) }
        if (m.optInt("full") == 1) p.agents.clear()
        m.getJSONArray("agents").forEachArray { a -> p.agents[a.getInt(0)] = IntArray(5) { a.getInt(it) } }
        m.optJSONArray("gone")?.forEachInt { p.agents.remove(it) }
        p.region = m.getInt("region"); p.step = m.optInt("step"); p.at = System.currentTimeMillis()
        version++
    }
    fun peersFrom(list: JSONArray) = synchronized(this) {
        peers.clear()
        list.forEachObject { p -> peers[p.getString("sigil")] = Peer(p.getInt("region"), p.optInt("step"), at = System.currentTimeMillis()) }
        version++
    }
    /** our own unit, animated while it is computed */
    fun own(region: Int, groups: List<IntArray>) = synchronized(this) { ownRegion = region; ownAgents = groups; version++ }
    fun clearOwn() = synchronized(this) { ownRegion = -1; ownAgents = emptyList(); version++ }

    /** the region the camera should look at: ours while computing, otherwise the latest other witness's */
    fun focus(): Int? = synchronized(this) { if (ownRegion >= 0) ownRegion else peers.values.maxByOrNull { it.at }?.region }

    fun frame(): Frame = synchronized(this) {
        val now = System.currentTimeMillis()
        peers.entries.removeAll { now - it.value.at > Config.PEER_TTL_MS }
        Frame(agents.values.toList(), settlements.values.toList(), routes.toList(), hues.copyOf(),
            peers.values.map { Peer(it.region, it.step, HashMap(it.agents), it.at) }, ownRegion, ownAgents, year, tick, climate)
    }
}

internal inline fun JSONArray.forEachArray(f: (JSONArray) -> Unit) { for (i in 0 until length()) f(getJSONArray(i)) }
internal inline fun JSONArray.forEachObject(f: (JSONObject) -> Unit) { for (i in 0 until length()) f(getJSONObject(i)) }
internal inline fun JSONArray.forEachInt(f: (Int) -> Unit) { for (i in 0 until length()) f(getInt(i)) }

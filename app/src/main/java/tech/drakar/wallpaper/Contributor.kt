package tech.drakar.wallpaper

import android.util.Log
import org.json.JSONObject

/**
 * Computes one work unit: lease → engine (stepped, animated on the globe and relayed as progress) → result.
 * The engine version is the one the server announced, or the one a refusal names.
 */
class Contributor(
    private val link: Link,
    private val world: WorldModel,
    private val assets: Assets,
    private val engine: EngineHost,
    private val prefs: Prefs,
) {
    @Volatile var wantedEngine: String? = null

    /** one unit; [animated] spreads it over a few seconds and shows it on the globe. Returns true when accepted. */
    fun runOnce(animated: Boolean): Boolean {
        if (!link.ready || !engine.supported) return false
        val version = wantedEngine ?: link.engine ?: return false
        try {
            engine.ensure(version, { assets.engine(version) }, assets.earth().getValue("terrain.bin"))
        } catch (e: Exception) { Log.w(TAG, "engine", e); return false }

        val m = link.ask(JSONObject().put("t", "lease")) ?: return false
        if (m.optString("t") != "unit") return false
        val started = System.currentTimeMillis()
        try {
            val region = engine.begin(m.getJSONObject("unit").toString())
            var lastProgress = 0L
            while (true) {
                val p = engine.step(if (animated) Config.STEP_SEASONS else 240)
                if (animated) {
                    val arr = p.getJSONArray("agents")
                    val groups = (0 until arr.length()).map { i -> arr.getJSONArray(i).let { a -> IntArray(5) { a.getInt(it) } } }
                    world.own(region, groups)
                    val now = System.currentTimeMillis()
                    if (now - lastProgress > Config.PROGRESS_EVERY_MS && !p.getBoolean("done")) {
                        lastProgress = now
                        link.send(JSONObject().put("t", "progress").put("region", region).put("step", p.getInt("step")).put("full", 1).put("agents", arr))
                    }
                }
                if (p.getBoolean("done")) break
                if (animated) Thread.sleep(Config.STEP_PAUSE_MS)
            }
            if (System.currentTimeMillis() - started > Config.LEASE_STALE_MS || !link.ready) return false
            val r = engine.finish()
            val answer = link.ask(JSONObject().put("t", "result").put("result", r.getJSONObject("result")).put("hash", r.getString("hash"))
                .put("ms", r.optInt("ms")).put("verify", m.optBoolean("verify")).put("engine", version)) ?: return false
            if (answer.optString("t") == "ack") { prefs.countUnit(); return true }
            answer.optString("engine").ifEmpty { null }?.let { wantedEngine = it }      // the server moved on: next unit with its engine
            Log.i(TAG, "refused: ${answer.optString("reason")}")
            return false
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); return false
        } catch (e: Exception) {
            Log.w(TAG, "unit", e); return false
        } finally {
            world.clearOwn()
        }
    }

    companion object { private const val TAG = "drakar.compute" }
}


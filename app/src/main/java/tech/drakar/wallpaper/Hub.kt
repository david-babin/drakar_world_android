package tech.drakar.wallpaper

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.os.BatteryManager
import android.util.Log
import tech.drakar.wallpaper.render.Earth
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * What every wallpaper surface shares: the Earth data, the world, the link (open only while a surface is
 * visible, closed [Config.DISCONNECT_GRACE_MS] after the last one hides) and the compute loop.
 */
object Hub {
    lateinit var prefs: Prefs; private set
    lateinit var world: WorldModel; private set
    lateinit var link: Link; private set
    lateinit var engine: EngineHost; private set
    lateinit var contributor: Contributor; private set
    private lateinit var app: Context
    private lateinit var assets: Assets
    @Volatile var earth: Earth? = null; private set

    private val sched = Executors.newSingleThreadScheduledExecutor { Thread(it, "drakar-hub").apply { isDaemon = true } }
    private val visible = HashSet<Any>()
    private var disconnect: ScheduledFuture<*>? = null
    private var loop: Thread? = null
    private val computeLock = Object()
    private var initialized = false

    @Synchronized fun init(context: Context) {
        if (initialized) return
        initialized = true
        app = context.applicationContext
        Charte.load(app)
        prefs = Prefs(app); world = WorldModel()
        val http = Assets.client()
        assets = Assets(app, http)
        link = Link(http, prefs.sigil, world)
        engine = EngineHost(app)
        contributor = Contributor(link, world, assets, engine, prefs)
        sched.execute(::loadEarth)
    }

    private fun loadEarth() {
        try {
            val f = assets.earth()
            val base = BitmapFactory.decodeByteArray(f.getValue("base.png"), 0, f.getValue("base.png").size, BitmapFactory.Options().apply { inScaled = false })
            earth = Earth(EarthData.rings(f.getValue("land.bin")), EarthData.lines(f.getValue("coasts.bin"), false), EarthData.lines(f.getValue("contours.bin"), true), base)
        } catch (e: Exception) {
            Log.w("drakar.hub", "Earth data unavailable, retrying in a minute", e)
            sched.schedule(::loadEarth, 60, TimeUnit.SECONDS)
        }
    }

    val anyVisible: Boolean get() = synchronized(visible) { visible.isNotEmpty() }

    /** a wallpaper surface became visible or hidden */
    fun visible(who: Any, v: Boolean) = synchronized(visible) {
        val was = visible.isNotEmpty()
        if (v) visible += who else visible -= who
        if (visible.isNotEmpty()) {
            disconnect?.cancel(false); disconnect = null
            if (!was) { link.open(); startLoop() }
        } else if (was) {
            disconnect = sched.schedule({ synchronized(visible) { if (visible.isEmpty()) { stopLoop(); link.close() } } }, Config.DISCONNECT_GRACE_MS, TimeUnit.MILLISECONDS)
        }
    }

    fun charging(): Boolean {
        val i: Intent? = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    /** while visible: a unit now and then (every minute, every ten seconds on the charger), animated on the globe */
    private fun startLoop() {
        if (loop?.isAlive == true) return
        loop = Thread({
            try {
                Thread.sleep(5_000)                                   // let the world arrive first
                while (!Thread.currentThread().isInterrupted) {
                    if (prefs.computeVisible && link.ready) synchronized(computeLock) { contributor.runOnce(animated = true) }
                    Thread.sleep(if (charging()) Config.CHARGING_UNIT_EVERY_MS else Config.VISIBLE_UNIT_EVERY_MS)
                }
            } catch (_: InterruptedException) {}
        }, "drakar-compute").apply { isDaemon = true; start() }
    }
    private fun stopLoop() { loop?.interrupt(); loop = null }

    /** background job (charger + Wi-Fi, screen off): computes for a while, without animation */
    fun backgroundRun(stopped: () -> Boolean): Int {
        if (anyVisible || !prefs.computeCharging) return 0
        var done = 0
        link.open()
        try {
            val t0 = System.currentTimeMillis()
            while (!link.ready && System.currentTimeMillis() - t0 < 30_000 && !stopped()) Thread.sleep(500)
            while (!stopped() && !anyVisible && System.currentTimeMillis() - t0 < Config.BACKGROUND_RUN_MS) {
                if (synchronized(computeLock) { contributor.runOnce(animated = false) }) done++
                Thread.sleep(Config.BACKGROUND_PAUSE_MS)
            }
        } catch (_: InterruptedException) {
        } finally {
            synchronized(visible) { if (visible.isEmpty()) link.close() }
        }
        return done
    }
}

package tech.drakar.wallpaper

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The WebSocket to the server, kept alive only while [open]: hello, the world as it changes, and the replies
 * to leases and results. Recovery as in the page: 45 s of silence = dead link; reconnect with backoff 1 → 15 s;
 * a dropped socket is abandoned at once (its late events are ignored).
 */
class Link(private val http: OkHttpClient, private val sigil: String, private val world: WorldModel) {
    interface Listener { fun onHello(engine: String?) {} }

    private val exec = Executors.newSingleThreadScheduledExecutor { Thread(it, "drakar-link").apply { isDaemon = true } }
    private var ws: WebSocket? = null
    private var generation = 0
    private var wanted = false
    private var backoff = Config.BACKOFF_MIN_MS
    private var lastMessage = 0L
    private var watchdog: ScheduledFuture<*>? = null
    private val replies = LinkedBlockingQueue<JSONObject>()        // unit / ack / nack
    @Volatile var ready = false; private set                         // hello received
    @Volatile var engine: String? = null; private set                // engine version announced by the server
    var listener: Listener? = null

    fun open() = exec.execute {
        if (wanted) return@execute
        wanted = true; backoff = Config.BACKOFF_MIN_MS; connect()
        watchdog = exec.scheduleWithFixedDelay({ if (ws != null && System.currentTimeMillis() - lastMessage > Config.DEAD_AFTER_MS) drop(true) }, 5, 5, TimeUnit.SECONDS)
    }

    fun close() = exec.execute {
        wanted = false; watchdog?.cancel(false)
        generation++; ws?.close(1000, null); ws = null; ready = false
    }

    fun send(o: JSONObject): Boolean = ws?.send(o.toString()) ?: false

    /** sends a request and waits for the next unit / ack / nack (null on timeout or disconnection) */
    fun ask(o: JSONObject): JSONObject? {
        replies.clear()
        if (!send(o)) return null
        return replies.poll(Config.ASK_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: run { exec.execute { drop(true) }; null }
    }

    private fun connect() {
        if (!wanted) return
        val my = ++generation
        lastMessage = System.currentTimeMillis()
        ws = http.newWebSocket(Request.Builder().url(Config.WS).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (my == generation) webSocket.send(JSONObject().put("t", "hello").put("sigil", sigil).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (my != generation) return
                lastMessage = System.currentTimeMillis()
                try { handle(JSONObject(text)) } catch (e: Exception) { Log.w(TAG, "message", e) }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(my)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(my)
        })
    }

    private fun handle(m: JSONObject) {
        when (m.getString("t")) {
            "hello" -> {
                engine = m.optString("engine").ifEmpty { null }
                world.loadSnapshot(m.getJSONObject("world")); world.online(m.optInt("online", 1))
                m.optJSONArray("peers")?.let { world.peersFrom(it) }
                backoff = Config.BACKOFF_MIN_MS; ready = true
                listener?.onHello(engine)
            }
            "world" -> world.loadSnapshot(m.getJSONObject("world"))
            "delta" -> if (!world.applyDelta(m)) send(JSONObject().put("t", "resync"))
            "market" -> world.market(m)
            "online", "tick" -> world.online(m.optInt("online", world.online))
            "lease" -> world.peerLease(m.getString("sigil"), m.getInt("region"))
            "progress" -> world.peerProgress(m)
            "release" -> world.peerRelease(m.getString("sigil"))
            "unit", "ack", "nack" -> replies.offer(m)
        }
    }

    private fun lost(my: Int) = exec.execute {
        if (my != generation) return@execute
        ws = null; ready = false; replies.offer(JSONObject().put("t", "nack").put("reason", "disconnected"))
        if (wanted) { exec.schedule({ if (ws == null) connect() }, backoff, TimeUnit.MILLISECONDS); backoff = (backoff * 2).coerceAtMost(Config.BACKOFF_MAX_MS) }
    }

    /** abandons the current socket and reconnects (now, or after the backoff) */
    private fun drop(now: Boolean) {
        val s = ws ?: return
        generation++; ws = null; ready = false
        try { s.cancel() } catch (_: Exception) {}
        replies.offer(JSONObject().put("t", "nack").put("reason", "disconnected"))
        if (now) connect() else exec.schedule({ connect() }, backoff, TimeUnit.MILLISECONDS)
    }

    companion object { private const val TAG = "drakar.link" }
}

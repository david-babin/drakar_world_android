package tech.drakar.wallpaper

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Runs the server's compute engine (engine.js, global DrakarEngine) in Android's JavaScript sandbox — the same
 * V8 as Chrome, so results are bit-for-bit those of the page and of the server's verification. Never a
 * reimplementation: the engine is downloaded at the version the server announces (docs/protocol.md §4).
 * Calls block: use from a background thread.
 */
class EngineHost(private val context: Context) {
    private var sandbox: JavaScriptSandbox? = null
    private var isolate: JavaScriptIsolate? = null
    var version: String? = null; private set

    val supported: Boolean get() = try { JavaScriptSandbox.isSupported() } catch (e: Throwable) { false }

    /** loads (or reloads) the engine at this version, with the terrain */
    @Synchronized fun ensure(version: String, source: () -> String, terrain: ByteArray) {
        if (this.version == version && isolate != null) return
        close()
        val sb = JavaScriptSandbox.createConnectedInstanceAsync(context.applicationContext).get(30, TimeUnit.SECONDS)
        val iso = sb.createIsolate()
        sandbox = sb; isolate = iso
        eval(source() + "\n;'loaded'")
        eval("DrakarEngine.init(" + JSONObject.quote(Base64.encodeToString(terrain, Base64.NO_WRAP)) + ")")
        this.version = version
        Log.i(TAG, "engine $version loaded")
    }

    fun begin(unitJson: String): Int = eval("DrakarEngine.begin(" + JSONObject.quote(unitJson) + ")").toInt()
    fun step(seasons: Int): JSONObject = JSONObject(eval("DrakarEngine.step($seasons)"))
    fun finish(): JSONObject = JSONObject(eval("DrakarEngine.finish()"))

    private fun eval(code: String): String {
        val iso = isolate ?: error("engine not loaded")
        return try { iso.evaluateJavaScriptAsync(code).get(60, TimeUnit.SECONDS) }
        catch (e: Exception) { close(); throw e }                 // a dead or stuck isolate is replaced next time
    }

    @Synchronized fun close() {
        try { isolate?.close() } catch (_: Exception) {}
        try { sandbox?.close() } catch (_: Exception) {}
        isolate = null; sandbox = null; version = null
    }

    companion object { private const val TAG = "drakar.engine" }
}

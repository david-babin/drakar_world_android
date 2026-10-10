package tech.drakar.wallpaper

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Files fetched from the site and kept in the app's storage: the Earth data (re-validated weekly with ETags)
 * and the compute engine (one file per version, as announced by the server).
 */
class Assets(context: Context, private val http: OkHttpClient) {
    private val earthDir = File(context.filesDir, "earth").apply { mkdirs() }
    private val engineDir = File(context.filesDir, "engine").apply { mkdirs() }

    /** the Earth data files, downloaded when missing or stale; throws if a file cannot be obtained at all */
    fun earth(): Map<String, ByteArray> = Config.EARTH.associateWith { name ->
        val f = File(earthDir, name); val tag = File(earthDir, "$name.etag")
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > Config.EARTH_REFRESH_MS) {
            try {
                val req = Request.Builder().url("${Config.SITE}/$name")
                if (f.exists() && tag.exists()) req.header("If-None-Match", tag.readText())
                http.newCall(req.build()).execute().use { res ->
                    when {
                        res.code == 304 -> f.setLastModified(System.currentTimeMillis())
                        res.isSuccessful -> {
                            val body = res.body!!.bytes()
                            File(earthDir, "$name.tmp").apply { writeBytes(body); renameTo(f) }
                            res.header("ETag")?.let { tag.writeText(it) }
                        }
                        !f.exists() -> error("$name: HTTP ${res.code}")
                    }
                }
            } catch (e: Exception) { if (!f.exists()) throw e }      // offline: keep the cached copy
        }
        f.readBytes()
    }

    /** the engine's source for a version (fingerprint), downloaded once */
    fun engine(version: String): String {
        val safe = version.replace(Regex("[^A-Za-z0-9_-]"), "")
        val f = File(engineDir, "$safe.js")
        if (!f.exists()) {
            http.newCall(Request.Builder().url("${Config.SITE}/engine.js?v=$safe").build()).execute().use { res ->
                check(res.isSuccessful) { "engine.js: HTTP ${res.code}" }
                File(engineDir, "$safe.tmp").apply { writeBytes(res.body!!.bytes()); renameTo(f) }
            }
            engineDir.listFiles()?.filter { it.name != f.name }?.forEach { it.delete() }   // older versions
        }
        return f.readText()
    }

    companion object {
        fun client(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

package tech.drakar.wallpaper

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * engine.js in Android's JavaScript sandbox must compute exactly what the server's library computes:
 * units and hashes produced by drakar_world's src/lib (assets/units.json), engine.js from the same build.
 */
@RunWith(AndroidJUnit4::class)
class EngineDeviceTest {
    @Test fun sameHashesAsTheServer() {
        val ctx = InstrumentationRegistry.getInstrumentation().context          // the test APK's assets
        val host = EngineHost(InstrumentationRegistry.getInstrumentation().targetContext)
        assumeTrue("JavaScript sandbox not supported on this device", host.supported)
        val source = ctx.assets.open("engine.js").bufferedReader().readText()
        val terrain = ctx.assets.open("terrain.bin").readBytes()
        host.ensure("fixture", { source }, terrain)
        val units = JSONArray(ctx.assets.open("units.json").bufferedReader().readText())
        for (i in 0 until units.length()) {
            val u = units.getJSONObject(i)
            host.begin(u.getJSONObject("unit").toString())
            while (!host.step(40).getBoolean("done")) {}
            assertEquals("unit $i", u.getString("hash"), host.finish().getString("hash"))
        }
        host.close()
    }
}

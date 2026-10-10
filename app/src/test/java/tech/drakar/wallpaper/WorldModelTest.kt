package tech.drakar.wallpaper

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The world model against the server's own library: a snapshot and 40 deltas produced by drakar_tech's
 * src/lib (world-fixture.json) must give the server's final state.
 */
class WorldModelTest {
    private fun fixture() = JSONObject(javaClass.getResource("/world-fixture.json")!!.readText())

    @Test fun snapshotThenDeltas() {
        val f = fixture(); val w = WorldModel()
        w.loadSnapshot(f.getJSONObject("snapshot"))
        val deltas = f.getJSONArray("deltas")
        for (i in 0 until deltas.length()) assertTrue("delta $i", w.applyDelta(deltas.getJSONObject(i)))
        val e = f.getJSONObject("expected")
        assertEquals(e.getInt("seq"), w.seq)
        assertEquals(e.getInt("agents"), w.agentCount)
        assertEquals(e.getInt("settlements"), w.settlementCount)
        assertEquals(e.getInt("routes"), w.routeCount)
        assertEquals(e.getInt("year"), w.year)
        assertEquals(e.getLong("population"), w.population)
    }

    @Test fun gapIsRefused() {
        val f = fixture(); val w = WorldModel()
        w.loadSnapshot(f.getJSONObject("snapshot"))
        assertFalse(w.applyDelta(f.getJSONArray("deltas").getJSONObject(1)))      // seq 2 before seq 1
    }
}

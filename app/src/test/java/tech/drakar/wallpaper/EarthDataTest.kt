package tech.drakar.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/** the Earth data parsers on the real files (counts of the files in drakar_tech/assets/earth) */
class EarthDataTest {
    private fun bytes(name: String) = javaClass.getResource("/$name")!!.readBytes()

    @Test fun coasts() {
        val lines = EarthData.lines(bytes("coasts.bin"), false)
        assertEquals(1424, lines.size)
        val p = lines[0].xyz
        assertTrue(abs(sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]) - 1f) < 1e-4f)       // unit vectors
    }

    @Test fun land() {
        val rings = EarthData.rings(bytes("land.bin"))
        assertEquals(1419, rings.size)
        assertEquals(40537, rings.sumOf { it.size / 2 })
    }

    @Test(expected = IllegalArgumentException::class) fun corrupted() { EarthData.lines(ByteArray(8) { -1 }, false) }
}

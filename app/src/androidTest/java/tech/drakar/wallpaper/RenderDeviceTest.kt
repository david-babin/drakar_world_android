package tech.drakar.wallpaper

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES30
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.drakar.wallpaper.render.GlobeRenderer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * End to end on a device: connects to drakar.tech, receives the world, downloads the Earth data, renders a frame
 * offscreen with the wallpaper's renderer, and saves it (Download/drakar-render.png) for a look.
 */
@RunWith(AndroidJUnit4::class)
class RenderDeviceTest {
    @Test fun liveWorldRenders() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        Hub.init(ctx)
        Hub.visible(this, true)                                       // opens the link as a visible wallpaper would
        val t0 = System.currentTimeMillis()
        while ((!Hub.world.loaded || Hub.earth == null) && System.currentTimeMillis() - t0 < 60_000) Thread.sleep(500)
        assertTrue("world received", Hub.world.loaded)
        assertTrue("Earth data loaded", Hub.earth != null)

        val w = 1080; val h = 2340
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY); val v = IntArray(2); EGL14.eglInitialize(display, v, 0, v, 1)
        val configs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
        EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_NONE), 0, configs, 0, 1, n, 0)
        val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        val surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, surface, surface, context)

        val density = ctx.resources.displayMetrics.density
        val renderer = GlobeRenderer({ Hub.earth }, Hub.world, density) { y -> "Year $y" }
        var now = System.currentTimeMillis()
        assertTrue("first frame drawn", renderer.frame(now, w, h, true))
        repeat(30) { now += 100; renderer.frame(now, w, h, false) }           // let the camera move a little
        assertTrue(renderer.frame(now + 100, w, h, true))
        assertEquals("GL error", GLES30.GL_NO_ERROR, GLES30.glGetError())

        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); buf.position(0); bmp.copyPixelsFromBuffer(buf)
        val flipped = Bitmap.createBitmap(bmp, 0, 0, w, h, android.graphics.Matrix().apply { preScale(1f, -1f) }, false)
        // saved in Downloads (kept after the test APKs are uninstalled), pulled by CI
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "drakar-render.png")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download")
        }
        ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.let { uri ->
            ctx.contentResolver.openOutputStream(uri)?.use { flipped.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        // the globe is drawn: the centre is not the background colour
        val c = flipped.getPixel(w / 2, h / 2)
        val bg = android.graphics.Color.rgb(7, 9, 12)
        assertTrue("globe visible at the centre", c != bg)
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface); EGL14.eglDestroyContext(display, context)
        Hub.visible(this, false)
    }
}

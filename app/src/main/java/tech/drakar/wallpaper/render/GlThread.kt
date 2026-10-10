package tech.drakar.wallpaper.render

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.util.Log
import android.view.SurfaceHolder
import tech.drakar.wallpaper.Config

/**
 * One rendering thread per wallpaper surface: an OpenGL ES 3 context on the surface, frames requested only while
 * visible, at most [Config.FPS_MOVING] a second, and buffers swapped only when the renderer actually drew.
 */
class GlThread(private val holder: SurfaceHolder, private val renderer: GlobeRenderer) : Thread("drakar-gl") {
    private val lock = Object()
    private var running = true
    private var visible = false
    private var width = 0
    private var height = 0
    private var force = true

    fun setVisible(v: Boolean) = synchronized(lock) { visible = v; if (v) force = true; lock.notifyAll() }
    fun setSize(w: Int, h: Int) = synchronized(lock) { width = w; height = h; force = true; lock.notifyAll() }
    fun finish() { synchronized(lock) { running = false; lock.notifyAll() }; join(3000) }

    override fun run() {
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY; var context: EGLContext = EGL14.EGL_NO_CONTEXT; var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val v = IntArray(2); check(EGL14.eglInitialize(display, v, 0, v, 1)) { "eglInitialize" }
            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 0,
                EGL14.EGL_DEPTH_SIZE, 0, EGL14.EGL_STENCIL_SIZE, 0, EGL14.EGL_SAMPLES, 4, EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, n, 0) || n[0] == 0) {
                attribs[attribs.size - 2] = 0                                                   // no multisampling available
                check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, n, 0) && n[0] > 0) { "eglChooseConfig" }
            }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            surface = EGL14.eglCreateWindowSurface(display, configs[0], holder.surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent" }

            val frameMs = 1000L / Config.FPS_MOVING
            while (true) {
                val w: Int; val h: Int; val f: Boolean
                synchronized(lock) {
                    while (running && (!visible || width == 0 || height == 0)) lock.wait()
                    if (!running) return
                    w = width; h = height; f = force; force = false
                }
                val t0 = System.currentTimeMillis()
                if (renderer.frame(t0, w, h, f)) EGL14.eglSwapBuffers(display, surface)
                val wait = frameMs - (System.currentTimeMillis() - t0)
                if (wait > 0) synchronized(lock) { if (running && !force) lock.wait(wait) }
            }
        } catch (e: Exception) {
            Log.e("drakar.gl", "render thread", e)
        } finally {
            renderer.release()
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglReleaseThread()          // (the display is shared by every surface of the process: not terminated)
            }
        }
    }
}

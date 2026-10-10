package tech.drakar.wallpaper

import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import tech.drakar.wallpaper.render.GlThread
import tech.drakar.wallpaper.render.GlobeRenderer
import java.text.NumberFormat

/** The live wallpaper: one engine per surface (home screen, lock screen, each screen of a foldable, the preview). */
class DrakarWallpaperService : WallpaperService() {
    override fun onCreate() { super.onCreate(); Hub.init(this) }

    override fun onCreateEngine(): Engine = GlobeEngine()

    inner class GlobeEngine : Engine() {
        private var thread: GlThread? = null

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false); setOffsetNotificationsEnabled(false)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            val density = resources.displayMetrics.density
            val renderer = GlobeRenderer({ Hub.earth }, Hub.world, density) { y -> getString(R.string.year, NumberFormat.getIntegerInstance().format(y)) }
            thread = GlThread(holder, renderer).also { it.start(); it.setVisible(isVisible) }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            thread?.setSize(width, height)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            thread?.finish(); thread = null                     // blocks until the GL surface is released
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            thread?.setVisible(visible)
            Hub.visible(this, visible)
        }

        override fun onDestroy() {
            Hub.visible(this, false)
            thread?.finish(); thread = null
            super.onDestroy()
        }
    }
}

package tech.drakar.wallpaper

import android.content.Intent
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Opens the settings screen and saves a screenshot of it (Download/drakar-settings.png), pulled by CI for a look. */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @Test fun settingsScreenShows() {
        val inst = InstrumentationRegistry.getInstrumentation(); val ctx = inst.targetContext
        val activity = inst.startActivitySync(Intent(ctx, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(2500); inst.waitForIdleSync()
        val shot: Bitmap? = inst.uiAutomation.takeScreenshot()
        assertNotNull("screenshot", shot)
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "drakar-settings.png")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download")
        }
        ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.let { uri ->
            ctx.contentResolver.openOutputStream(uri)?.use { shot!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        activity.finish()
    }
}

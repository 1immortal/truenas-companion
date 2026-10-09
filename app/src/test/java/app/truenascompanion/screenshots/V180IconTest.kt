package app.truenascompanion.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import app.truenascompanion.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 1.8.0: renders the real launcher icon from the built resources (adaptive icon: background + foreground, and the
 * themed monochrome layer) so it can be compared with the design sheet, and exports the 512 px README icon.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "xxxhdpi", application = android.app.Application::class)
class V180IconTest {
    private val dir = File(System.getProperty("screenshot.dir") ?: "build/screenshots").apply { mkdirs() }
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Draws the 108-unit adaptive layers at [size] px and clips them to [mask] (the launcher shape). */
    private fun render(size: Int, mask: (Float) -> Path?, monochrome: Boolean = false): Bitmap {
        val icon = ContextCompat.getDrawable(ctx, R.mipmap.ic_launcher) as AdaptiveIconDrawable
        val full = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(full)
        // Layers are 108 units; launchers show the middle 72 (scale 1.5 so the visible part fills the bitmap).
        val layer = (size * 1.5f).toInt(); val off = -(layer - size) / 2
        if (monochrome) {
            c.drawColor(Color.parseColor("#FFD8E2FF"))
            icon.monochrome!!.apply { setBounds(off, off, off + layer, off + layer); setTint(Color.parseColor("#FF1B2B5C")); draw(c) }
        } else {
            icon.background.apply { setBounds(off, off, off + layer, off + layer); draw(c) }
            icon.foreground.apply { setBounds(off, off, off + layer, off + layer); draw(c) }
        }
        val path = mask(size.toFloat()) ?: return full
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val oc = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        oc.drawPath(path, p)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        oc.drawBitmap(full, 0f, 0f, p)
        return out
    }

    private val circle: (Float) -> Path = { s -> Path().apply { addCircle(s / 2, s / 2, s / 2, Path.Direction.CW) } }
    private val squircle: (Float) -> Path = { s -> Path().apply { addRoundRect(0f, 0f, s, s, s * 0.3f, s * 0.3f, Path.Direction.CW) } }
    private val square: (Float) -> Path? = { null }

    private fun save(b: Bitmap, name: String) = File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }

    @Test fun launcherIcon() {
        save(render(512, square), "v180_icon_512")
        val sheet = Bitmap.createBitmap(4 * 256 + 5 * 32, 256 + 64, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet); c.drawColor(Color.parseColor("#FF101828"))
        listOf(render(256, circle), render(256, squircle), render(256, square), render(256, circle, monochrome = true))
            .forEachIndexed { i, b -> c.drawBitmap(b, 32f + i * (256 + 32), 32f, null) }
        save(sheet, "v180_icon_shapes")
        // The mark is drawn (bright pixels in the middle), the corners are the navy background.
        val b = render(512, square)
        val mid = b.getPixel(256, 300); val corner = b.getPixel(4, 4)
        assertTrue(Color.red(mid) + Color.green(mid) + Color.blue(mid) > 500)
        assertTrue(Color.blue(corner) > Color.red(corner) && Color.red(corner) < 60)
    }
}

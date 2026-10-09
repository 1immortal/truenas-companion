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
    private fun render(size: Int, mask: (Float) -> Path?, monochrome: Boolean = false, debugBadge: Boolean = false): Bitmap {
        // Release layers straight from the main resources; the debug build's mipmap adds the "YTN Preview" badge.
        val adaptive = ContextCompat.getDrawable(ctx, R.mipmap.ic_launcher) as AdaptiveIconDrawable
        val bg = ContextCompat.getDrawable(ctx, R.drawable.ic_launcher_background)!!
        val fg = if (debugBadge) adaptive.foreground else ContextCompat.getDrawable(ctx, R.drawable.ic_launcher_foreground)!!
        val full = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(full)
        // Layers are 108 units; launchers show the middle 72 (scale 1.5 so the visible part fills the bitmap).
        val layer = (size * 1.5f).toInt(); val off = -(layer - size) / 2
        if (monochrome) {
            c.drawColor(Color.parseColor("#FFD8E2FF"))
            ContextCompat.getDrawable(ctx, R.drawable.ic_launcher_monochrome)!!.mutate().apply { setBounds(off, off, off + layer, off + layer); setTint(Color.parseColor("#FF1B2B5C")); draw(c) }
        } else {
            bg.apply { setBounds(off, off, off + layer, off + layer); draw(c) }
            fg.apply { setBounds(off, off, off + layer, off + layer); draw(c) }
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
        val sheet = Bitmap.createBitmap(5 * 256 + 6 * 32, 256 + 64, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet); c.drawColor(Color.parseColor("#FF101828"))
        listOf(render(256, circle), render(256, squircle), render(256, square), render(256, circle, monochrome = true), render(256, squircle, debugBadge = true))
            .forEachIndexed { i, b -> c.drawBitmap(b, 32f + i * (256 + 32), 32f, null) }
        save(sheet, "v180_icon_shapes")
        // The mark is drawn (bright pixels in the middle), the corners are the navy background.
        val b = render(512, square)
        val mid = b.getPixel(256, 300); val corner = b.getPixel(4, 4)
        // No debug badge on the release icon (amber at the badge spot only in the debug variant).
        val spot = (512 * (70 - 18) / 72f).toInt()
        assertTrue(Color.red(b.getPixel(spot, spot)) < 200 || Color.blue(b.getPixel(spot, spot)) > 100)
        val dbg = render(512, square, debugBadge = true).getPixel(spot, spot)
        assertTrue(Color.red(dbg) > 200 && Color.blue(dbg) < 100)
        assertTrue(Color.red(mid) + Color.green(mid) + Color.blue(mid) > 500)
        assertTrue(Color.blue(corner) > Color.red(corner) && Color.red(corner) < 60)
    }

    /** Inflates the widget picker preview layout (example data) in day or night mode at [wDp]x[hDp]. */
    private fun widgetBitmap(night: Boolean, wDp: Int = 250, hDp: Int = 110): Bitmap {
        val cfg = android.content.res.Configuration(ctx.resources.configuration).apply {
            uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (night) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO)
        }
        val themed = ctx.createConfigurationContext(cfg)
        val v = android.view.LayoutInflater.from(themed).inflate(R.layout.widget_preview, null)
        val d = themed.resources.displayMetrics.density
        val w = (wDp * d).toInt(); val h = (hDp * d).toInt()
        v.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        // The picker shows the release icon (the test build would otherwise draw the debug badge).
        v.findViewById<android.widget.ImageView>(R.id.widget_preview_icon).setImageBitmap(render((22 * d).toInt(), squircle))
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        return b
    }

    /** The widget picker image (res/drawable-nodpi/widget_preview.png comes from this) plus day/night previews. */
    @Test fun widgetPreview() {
        val day = widgetBitmap(false); val night = widgetBitmap(true)
        save(night, "v180_widget_preview")
        val sheet = Bitmap.createBitmap(day.width + night.width + 3 * 48, day.height + 96, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        c.drawPaint(Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, sheet.width.toFloat(), sheet.height.toFloat(), Color.parseColor("#FF6A85C9"), Color.parseColor("#FF2B3A67"), android.graphics.Shader.TileMode.CLAMP) })
        c.drawBitmap(day, 48f, 48f, null); c.drawBitmap(night, 96f + day.width, 48f, null)
        save(sheet, "v180_widget")
        // Rounded corners: the corner pixel is transparent, the centre is painted.
        assertTrue(Color.alpha(night.getPixel(1, 1)) < 40)
        assertTrue(Color.alpha(night.getPixel(night.width / 2, night.height / 2)) > 250)
    }

    /** A home-screen mock: wallpaper, the widget and the launcher icon among others (for the README / release notes). */
    @Test fun homeScreenMockup() {
        val d = ctx.resources.displayMetrics.density
        val w = (360 * d).toInt(); val h = (520 * d).toInt()
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawPaint(Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Color.parseColor("#FF7B93D6"), Color.parseColor("#FF1D2747"), android.graphics.Shader.TileMode.CLAMP) })
        val widget = widgetBitmap(true, 328, 110)
        c.drawBitmap(widget, 16 * d, 48 * d, null)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 12 * d; textAlign = Paint.Align.CENTER; setShadowLayer(3 * d, 0f, d, Color.argb(120, 0, 0, 0)) }
        val iconPx = (56 * d).toInt()
        val labels = listOf("Phone", "YTN", "Camera", "Files")
        val colors = listOf("#FF34A853", "", "#FF8E8E93", "#FF4285F4")
        val top = 260 * d; val cell = w / 4f
        labels.forEachIndexed { i, label ->
            val cx = cell * i + cell / 2
            if (label == "YTN") c.drawBitmap(render(iconPx, circle), cx - iconPx / 2f, top, null)
            else c.drawCircle(cx, top + iconPx / 2f, iconPx / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(colors[i]) })
            c.drawText(label, cx, top + iconPx + 18 * d, text)
        }
        c.drawText("Example home screen", w / 2f, h - 24 * d, text)
        save(b, "v180_icon_home")
    }
}

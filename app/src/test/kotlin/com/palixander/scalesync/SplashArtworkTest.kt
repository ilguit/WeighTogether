package com.palixander.scalesync

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.w3c.dom.Element

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SplashArtworkTest {
    @Test
    fun renderedArtworkHasTransparentMarginsFlatInkAndTranslucentCrossing() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val drawable = requireNotNull(context.getDrawable(R.drawable.ic_weigh_together_splash))
        val bitmap = Bitmap.createBitmap(1152, 1152, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, bitmap.width, bitmap.height)
        drawable.draw(Canvas(bitmap))
        var opaque = 0
        var crossing = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val color = bitmap.getPixel(x, y)
                val alpha = Color.alpha(color)
                if (alpha > 0) {
                    assertTrue("Pixel outside system safe circle: $x,$y", hypot(x + 0.5 - 576, y + 0.5 - 576) < 384)
                }
                if (alpha == 255) {
                    assertEquals(Color.rgb(40, 118, 107), color)
                    opaque++
                }
                if (alpha == 204) {
                    // Android's premultiplied 8-bit storage can round channels by one.
                    assertTrue(kotlin.math.abs(Color.red(color) - 40) <= 1)
                    assertTrue(kotlin.math.abs(Color.green(color) - 118) <= 1)
                    assertTrue(kotlin.math.abs(Color.blue(color) - 107) <= 1)
                    crossing++
                }
            }
        }
        assertTrue("Opaque wordmark and family missing", opaque > 20_000)
        assertTrue("80% crossing missing", crossing > 20_000)
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(0, 0))
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(576, 200))
        val output = File("build/reports/splash/weigh-together.png")
        requireNotNull(output.parentFile).mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun crossingHasFourTrapezoidsWithSharedHorizontalsAndVanishingPoint() {
        val main = File("src/main").takeIf { it.exists() } ?: File("app/src/main")
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(main, "res/drawable/ic_weigh_together_splash.xml"))
        val android = "http://schemas.android.com/apk/res/android"
        val paths = document.getElementsByTagName("path")
        assertEquals(5, paths.length)
        val vanishingPoints = mutableListOf<Pair<Double, Double>>()
        var top: Double? = null
        var bottom: Double? = null
        for (index in 0 until 4) {
            val path = paths.item(index) as Element
            assertEquals("#28766B", path.getAttributeNS(android, "fillColor"))
            assertEquals("0.8", path.getAttributeNS(android, "fillAlpha"))
            val points = Regex("(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)")
                .findAll(path.getAttributeNS(android, "pathData"))
                .map { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }.toList()
            assertEquals(4, points.size)
            val (a, b, c, d) = points
            assertEquals(a.second, b.second, 0.001)
            assertEquals(c.second, d.second, 0.001)
            if (top == null) { top = a.second; bottom = c.second }
            assertEquals(top, a.second, 0.001)
            assertEquals(bottom!!, c.second, 0.001)
            val ratio = (b.first - a.first) / (c.first - d.first)
            val vy = (a.second - ratio * d.second) / (1 - ratio)
            val vx = (a.first - ratio * d.first) / (1 - ratio)
            vanishingPoints.add(vx to vy)
        }
        for (point in vanishingPoints) {
            assertEquals(vanishingPoints.first().first, point.first, 0.02)
            assertEquals(vanishingPoints.first().second, point.second, 0.02)
        }
        val foreground = paths.item(4) as Element
        assertEquals("#28766B", foreground.getAttributeNS(android, "fillColor"))
        assertEquals("1", foreground.getAttributeNS(android, "fillAlpha"))
    }
}

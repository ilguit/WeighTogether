package com.palixander.scalesync

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class AppLogoResourceTest {
    private val main = File("src/main").takeIf { it.exists() } ?: File("app/src/main")
    private val android = "http://schemas.android.com/apk/res/android"

    private fun xml(path: String): Element = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(File(main, path)).documentElement

    private fun paths(name: String): List<Element> {
        val nodes = xml("res/drawable/$name.xml").getElementsByTagName("path")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test
    fun allLauncherConfigurationsUseSharedBrandLayers() {
        for (directory in listOf("mipmap-anydpi", "mipmap-anydpi-v33")) {
            for (name in listOf("ic_launcher", "ic_launcher_round")) {
                val icon = xml("res/$directory/$name.xml")
                val foreground = icon.getElementsByTagName("foreground").item(0) as Element
                assertEquals("@drawable/ic_app_foreground", foreground.getAttributeNS(android, "drawable"))
                if (directory.endsWith("v33")) {
                    val mono = icon.getElementsByTagName("monochrome").item(0) as Element
                    assertEquals("@drawable/ic_app_monochrome", mono.getAttributeNS(android, "drawable"))
                }
            }
        }
    }

    @Test
    fun everyContourFitsAdaptiveSafeCircleAndMonochromeRetainsCutouts() {
        val foreground = paths("ic_app_foreground")
        val silhouette = foreground.first().getAttributeNS(android, "pathData")
        for (name in listOf("ic_app_monochrome", "ic_notification")) {
            val path = paths(name).single()
            assertEquals(silhouette, path.getAttributeNS(android, "pathData"))
            assertEquals("evenOdd", path.getAttributeNS(android, "fillType"))
        }
        // Three subjects and eight negative contours: eye, arc, five ticks, needle.
        assertEquals(11, silhouette.count { it == 'M' })
        for (path in foreground) {
            val points = Regex("(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)")
                .findAll(path.getAttributeNS(android, "pathData")).toList()
            assertTrue(points.isNotEmpty())
            for (point in points) {
                assertTrue(hypot(point.groupValues[1].toDouble() - 54, point.groupValues[2].toDouble() - 54) <= 33)
            }
        }
    }

    @Test
    fun allNotificationProducersUseCompactTransparentBrandIcon() {
        for (file in listOf(
            "worker/PendingMeasurementNotificationHelper.kt",
            "worker/SuccessfulMeasurementNotificationHelper.kt",
            "ble/ReliabilityScanService.kt",
        )) {
            val source = File(main, "kotlin/com/palixander/scalesync/$file").readText()
            assertTrue(source.contains("R.drawable.ic_notification"))
            assertTrue(!source.contains("android.R.drawable.ic_dialog_info"))
        }
        val icon = xml("res/drawable/ic_notification.xml")
        assertEquals("72", icon.getAttributeNS(android, "viewportWidth"))
        val group = icon.getElementsByTagName("group").item(0) as Element
        assertEquals("-18", group.getAttributeNS(android, "translateX"))
        assertEquals("-18", group.getAttributeNS(android, "translateY"))
    }
}

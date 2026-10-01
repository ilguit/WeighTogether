package com.palixander.weightogether

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
    private val launcherNames = listOf("ic_scalesync_launcher", "ic_scalesync_launcher_round")

    private fun xml(path: String): Element = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(File(main, path)).documentElement

    private fun paths(name: String): List<Element> {
        val nodes = xml("res/drawable/$name.xml").getElementsByTagName("path")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test
    fun manifestUsesUniquelyNamedLauncherResources() {
        val application = xml("AndroidManifest.xml").getElementsByTagName("application").item(0) as Element
        assertEquals("@mipmap/ic_scalesync_launcher", application.getAttributeNS(android, "icon"))
        assertEquals("@mipmap/ic_scalesync_launcher_round", application.getAttributeNS(android, "roundIcon"))

        for (directory in listOf("mipmap-anydpi", "mipmap-anydpi-v33")) {
            assertTrue(!File(main, "res/$directory/ic_launcher.xml").exists())
            assertTrue(!File(main, "res/$directory/ic_launcher_round.xml").exists())
        }
    }

    @Test
    fun allLauncherConfigurationsUseSharedBrandLayers() {
        for (directory in listOf("mipmap-anydpi", "mipmap-anydpi-v33")) {
            for (name in launcherNames) {
                val icon = xml("res/$directory/$name.xml")
                val background = icon.getElementsByTagName("background").item(0) as Element
                val foreground = icon.getElementsByTagName("foreground").item(0) as Element
                assertEquals("@color/scalesync_primary", background.getAttributeNS(android, "drawable"))
                assertEquals("@drawable/ic_app_foreground", foreground.getAttributeNS(android, "drawable"))
                if (directory.endsWith("v33")) {
                    val mono = icon.getElementsByTagName("monochrome").item(0) as Element
                    assertEquals("@drawable/ic_app_monochrome", mono.getAttributeNS(android, "drawable"))
                } else {
                    assertEquals(0, icon.getElementsByTagName("monochrome").length)
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
            val source = File(main, "kotlin/com/palixander/weightogether/$file").readText()
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

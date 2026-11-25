package com.limelight.utils

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Source contract only; verify the merged manifest in the built APK separately. */
class LauncherManifestTest {
    @Test
    fun pcViewIsTheOnlyLauncherAndLegacyAliasesRemainExplicitEntrypoints() {
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
            .firstOrNull(File::isFile) ?: error("Unable to locate app manifest")
        val application = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(manifest).documentElement.children("application").single()
        assertTrue(application.android("enabled") in setOf("", "true"))
        val components = application.children().filter { it.tagName in setOf("activity", "activity-alias") }

        for (category in listOf(
            "android.intent.category.LAUNCHER",
            "android.intent.category.LEANBACK_LAUNCHER",
            "android.intent.category.MULTIWINDOW_LAUNCHER",
            "tv.ouya.intent.category.APP",
        )) {
            val owners = components.filter { component ->
                component.children("intent-filter").any { filter ->
                    filter.children("action").any { it.android("name") == "android.intent.action.MAIN" } &&
                        filter.children("category").any { it.android("name") == category }
                }
            }.map { it.component() }
            assertEquals("$category must have one stable launcher", listOf("PcView"), owners)
        }

        for (name in listOf("PcView", "LauncherSaba", "LauncherSabaDark")) {
            val component = components.single { it.component() == name }
            assertTrue("$name must be enabled by default", component.android("enabled") in setOf("", "true"))
            assertEquals("$name must be externally launchable", "true", component.android("exported"))
            if (name == "PcView") {
                assertEquals("activity", component.tagName)
            } else {
                assertEquals("activity-alias", component.tagName)
                assertEquals("PcView", component.component("targetActivity"))
                assertTrue("$name is only a legacy explicit entry", component.children("intent-filter").isEmpty())
                assertTrue(components.indexOfFirst { it.component() == "PcView" } < components.indexOf(component))
            }
        }
    }

    private fun Element.children(tag: String? = null): List<Element> = (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) as? Element }
        .filter { tag == null || it.tagName == tag }

    private fun Element.android(name: String): String = getAttributeNS("http://schemas.android.com/apk/res/android", name)
    private fun Element.component(attribute: String = "name"): String =
        android(attribute).removePrefix("com.limelight.").removePrefix(".")
}

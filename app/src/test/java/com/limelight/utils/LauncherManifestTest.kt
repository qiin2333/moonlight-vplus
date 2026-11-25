package com.limelight.utils

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Guards source declarations; the merged APK manifest must also be checked. */
class LauncherManifestTest {
    private val sourceDir: File by lazy {
        listOf(File("src"), File("app/src"))
            .firstOrNull { File(it, "main/AndroidManifest.xml").isFile }
            ?: error("Unable to locate app/src/main/AndroidManifest.xml")
    }

    @Test
    fun allLaunchersUsePcViewRegardlessOfLegacyAliasState() {
        val application = requireNotNull(application(File(sourceDir, "main/AndroidManifest.xml")))
        assertEnabled(application)
        val components = application.children().filter {
            it.tagName == "activity" || it.tagName == "activity-alias"
        }

        for (category in LAUNCHER_CATEGORIES) {
            val owners = components.filter { category in it.mainLauncherCategories() }
                .map { componentName(it.android("name")) }
            assertEquals("$category must have one stable launcher component", listOf(PC_VIEW), owners)
        }

        val pcView = components.single { componentName(it.android("name")) == PC_VIEW }
        assertEquals("activity", pcView.tagName)
        assertEnabled(pcView)
        assertEquals("true", pcView.android("exported"))

        for (name in LEGACY_ALIASES) {
            val alias = components.single { componentName(it.android("name")) == name }
            assertEquals("activity-alias", alias.tagName)
            assertEnabled(alias)
            assertEquals("$name must remain usable by existing explicit shortcuts", "true", alias.android("exported"))
            assertEquals(PC_VIEW, componentName(alias.android("targetActivity")))
            assertTrue("$name must not advertise another launcher entry", alias.children("intent-filter").isEmpty())
            assertTrue("$name must follow its target activity", components.indexOf(pcView) < components.indexOf(alias))
        }
    }

    @Test
    fun flavorAndBuildTypeOverlaysPreserveTheSharedLauncherContract() {
        // Keep this contract in main instead of approximating AGP's merging rules here.
        // Inspect every source-set manifest so a new flavor cannot silently add a launcher.
        val overlays = sourceDir.listFiles().orEmpty()
            .filter { it.isDirectory && it.name != "main" && !it.name.startsWith("test") && !it.name.startsWith("androidTest") }
            .map { File(it, "AndroidManifest.xml") }
            .filter(File::isFile)

        for (manifest in overlays) {
            val application = application(manifest) ?: continue
            assertEnabled(application)
            assertTrue("$manifest must preserve the main application declaration",
                application.getAttributeNS(TOOLS_NAMESPACE, "node") in setOf("", "merge"))
            for (component in application.children().filter {
                it.tagName == "activity" || it.tagName == "activity-alias"
            }) {
                val name = componentName(component.android("name"))
                assertTrue("$manifest must keep $name's launcher contract in the main manifest",
                    name !in LEGACY_ALIASES && name != PC_VIEW)
                assertTrue("$manifest must not add another launcher component: $name",
                    component.mainLauncherCategories().isEmpty())
            }
        }
    }

    private fun application(file: File): Element? = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(file).documentElement.children("application").singleOrNull()

    private fun assertEnabled(element: Element) {
        assertTrue("${element.android("name").ifEmpty { element.tagName }} must be enabled by default",
            element.android("enabled") in setOf("", "true"))
    }

    private fun Element.mainLauncherCategories(): Set<String> = children("intent-filter")
        .filter { filter -> filter.children("action").any { it.android("name") == "android.intent.action.MAIN" } }
        .flatMap { filter -> filter.children("category").map { it.android("name") } }
        .toSet().intersect(LAUNCHER_CATEGORIES)

    private fun Element.children(tag: String? = null): List<Element> = (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) as? Element }
        .filter { tag == null || it.tagName == tag }

    private fun Element.android(name: String): String = getAttributeNS(ANDROID_NAMESPACE, name)

    private fun componentName(name: String): String = when {
        name.startsWith(".") -> "com.limelight$name"
        "." !in name -> "com.limelight.$name"
        else -> name
    }

    companion object {
        private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        private const val TOOLS_NAMESPACE = "http://schemas.android.com/tools"
        private const val PC_VIEW = "com.limelight.PcView"
        private val LEGACY_ALIASES = setOf("com.limelight.LauncherSaba", "com.limelight.LauncherSabaDark")
        private val LAUNCHER_CATEGORIES = setOf(
            "android.intent.category.LAUNCHER",
            "android.intent.category.LEANBACK_LAUNCHER",
            "android.intent.category.MULTIWINDOW_LAUNCHER",
            "tv.ouya.intent.category.APP",
        )
    }
}

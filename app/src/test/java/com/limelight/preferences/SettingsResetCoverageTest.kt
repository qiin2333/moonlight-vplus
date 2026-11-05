package com.limelight.preferences

import android.content.SharedPreferences
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsResetCoverageTest {
    @Test(expected = IOException::class)
    fun aFailedWriteMustNotReportSuccessOrContinueClearingOtherStores() {
        val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, _ ->
            when (method.name) {
                "remove", "putBoolean" -> proxy
                "commit" -> false
                else -> error("Unexpected editor operation: ${method.name}")
            }
        } as SharedPreferences.Editor
        val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)
        ) { _, method, _ ->
            check(method.name == "edit")
            editor
        } as SharedPreferences
        SettingsResetManager.resetOverrides(preferences, setOf("list_resolution")) {
            error("A failed write must stop the reset")
        }
    }

    @Test
    fun readerPreferenceKeysMustHaveAResetPolicy() {
        val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(main, "res/xml/preferences.xml"))
        val elements = document.getElementsByTagName("*")
        val xmlKeys = (0 until elements.length).mapNotNull { index ->
            val attributes = elements.item(index).attributes
            if (attributes.getNamedItemNS("http://schemas.android.com/apk/res/android", "persistent")?.nodeValue == "false" ||
                attributes.getNamedItemNS("http://schemas.android.com/apk/res-auto", "persistent")?.nodeValue == "false") {
                return@mapNotNull null
            }
            attributes.getNamedItemNS(
                "http://schemas.android.com/apk/res/android", "key"
            )?.nodeValue
        }.toSet()
        val covered = xmlKeys + PreferenceConfiguration.nonXmlResetPreferenceKeys +
            LegacySettingsModeStore.modeKeys
        val source = File(main, "java/com/limelight/preferences/PreferenceConfiguration.kt").readText()
        val readerKeys = Regex("(?:const )?val\\s+(\\w+)\\s*=\\s*\"([^\"]*)\"")
            .findAll(source)
            .filter {
                val name = it.groupValues[1]
                val key = it.groupValues[2]
                "PREF" in name || name.endsWith("_STRING") ||
                    key.startsWith("checkbox_") || key.startsWith("list_") || key.startsWith("seekbar_")
            }
            .map { it.groupValues[2] }.toSet()
        val missing = readerKeys - covered
        assertTrue("Register non-XML settings and legacy aliases for reset: $missing", missing.isEmpty())
    }
}

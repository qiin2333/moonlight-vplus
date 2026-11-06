package com.limelight.nvstream.http

import com.google.gson.JsonSyntaxException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.StringReader

class NvAppCmdListTest {
    @Test
    fun parsesCommandsWithoutChangingIdsNamesOrExtraFields() {
        val app = NvApp()
        app.setCmdList("""[{"id":"001","name":"启动串流 🎮","extra":{"enabled":true}},{"id":"quit","name":"Quit"}]""")

        val commands = app.cmdList!!
        assertEquals(2, commands.size())
        assertEquals("001", commands[0].asJsonObject["id"].asString)
        assertEquals("启动串流 🎮", commands[0].asJsonObject["name"].asString)
        assertEquals(true, commands[0].asJsonObject["extra"].asJsonObject["enabled"].asBoolean)
        assertEquals("quit", commands[1].asJsonObject["id"].asString)
    }

    @Test
    fun emptyArrayRemainsPresentAndClearsPreviousCommands() {
        val app = NvApp()
        app.setCmdList("""[{"id":"old","name":"Old"}]""")
        app.setCmdList("[]")

        assertNotNull(app.cmdList)
        assertEquals(0, app.cmdList!!.size())
    }

    @Test
    fun blankInputRetainsTheExistingAbsentCommandsBehavior() {
        val app = NvApp()
        for (input in listOf("", " \r\n\t")) {
            app.setCmdList("[]")
            app.setCmdList(input)
            assertNull(app.cmdList)
        }
    }

    @Test
    fun invalidJsonAndNonArrayRootsAreRejectedWithoutReplacingCommands() {
        val app = NvApp()
        app.setCmdList("""[{"id":"old","name":"Old"}]""")
        val previous = app.cmdList

        for (input in listOf("[", "[{\"id\":]", "[] []", "[] trailing", "{}", "42", "true", "\"text\"", "null")) {
            assertThrows("Input: $input", JsonSyntaxException::class.java) {
                app.setCmdList(input)
            }
            assertSame(previous, app.cmdList)
        }
    }

    @Test
    fun appListXmlParsesCommandsAndKeepsNullOrMissingCommandsAbsent() {
        val apps = NvHTTP.getAppListByReader(StringReader(
            """<root status_code="200"><App><AppTitle>Game</AppTitle><ID>1</ID><IsHdrSupported>1</IsHdrSupported><SuperCmds>[{"id":"001","name":"Start &amp; Play"}]</SuperCmds></App><App><AppTitle>Desktop</AppTitle><ID>2</ID><SuperCmds>null</SuperCmds></App><App><AppTitle>Other</AppTitle><ID>3</ID></App></root>"""
        ))

        assertEquals(3, apps.size)
        assertEquals("Game", apps[0].appName)
        assertEquals(1, apps[0].appId)
        assertEquals(true, apps[0].hdrSupported)
        val command = apps[0].cmdList!![0].asJsonObject
        assertEquals("001", command["id"].asString)
        assertEquals("Start & Play", command["name"].asString)
        assertNull(apps[1].cmdList)
        assertNull(apps[2].cmdList)
    }

}

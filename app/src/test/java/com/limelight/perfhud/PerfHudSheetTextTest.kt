package com.limelight.perfhud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfHudSheetTextTest {

    @Test
    fun parsesHeadingsBulletsRowsAndTips() {
        val blocks = PerfHudSheetText.parse(
            "延迟范围：\n• <20ms：极佳\n\n\n帧率：60 / 60 FPS\nPacket loss: 0.00%\n────────\n💡 串流小课堂：长按看说明\n"
        )
        assertEquals(
            listOf(
                SheetBlock.Heading("延迟范围"),
                SheetBlock.Bullet("<20ms：极佳"),
                SheetBlock.Gap,
                SheetBlock.Row("帧率", "60 / 60 FPS"),
                SheetBlock.Row("Packet loss", "0.00%"),
                SheetBlock.Divider,
                SheetBlock.Tip("💡 串流小课堂：长按看说明")
            ),
            blocks
        )
    }

    @Test
    fun rowFollowedByBulletsBecomesCaptionedHeading() {
        val blocks = PerfHudSheetText.parse("Rx FPS：接收帧率\n• 从主机接收的视频帧数")
        assertEquals(
            listOf(SheetBlock.Heading("Rx FPS", "接收帧率"), SheetBlock.Bullet("从主机接收的视频帧数")),
            blocks
        )
    }

    @Test
    fun longSentencesStayParagraphs() {
        val line = "这是把各段加起来的估算值，可不是实测的“按键到画面”延迟哦：网络段用往返时间的一半近似单程，杂鱼别当真～"
        assertTrue(PerfHudSheetText.parse(line).single() is SheetBlock.Paragraph)
        assertNull(PerfHudSheetText.row("Hello world"))
    }

    @Test
    fun rowCarriesColorAndSwatch() {
        val row = PerfHudSheetText.row("网络 RTT：6 ms（±2）", valueColor = 1, swatch = 2)
        assertEquals(SheetBlock.Row("网络 RTT", "6 ms（±2）", 1, 2), row)
    }

    @Test
    fun splitsLeadingEmojiFromTitle() {
        assertEquals("📊" to "帧率信息", PerfHudSheetText.splitIcon("📊 帧率信息"))
        assertEquals("⛓️" to "延迟链（估算）", PerfHudSheetText.splitIcon("⛓️ 延迟链（估算）"))
        assertEquals(null to "Frame rate info", PerfHudSheetText.splitIcon("Frame rate info"))
    }
}

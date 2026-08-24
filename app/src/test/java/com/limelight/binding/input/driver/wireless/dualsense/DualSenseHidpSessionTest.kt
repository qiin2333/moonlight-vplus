package com.limelight.binding.input.driver.wireless.dualsense

import com.limelight.binding.input.driver.DualSenseInputState
import com.limelight.binding.input.driver.wireless.hidp.HidpErrorCode
import com.limelight.binding.input.driver.wireless.hidp.HidpFailure
import com.limelight.binding.input.driver.wireless.l2cap.L2capHidChannels
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DualSenseHidpSessionTest {
    @Test
    fun becomesReadyOnlyAfterValidCrcCheckedInput() {
        var input: DualSenseInputState? = null
        val rejected = mutableListOf<DualSenseBluetoothInputDisposition>()
        val control = mutableListOf<ByteArray>()
        val session = DualSenseHidpSession(
            sendControl = { control += it; true },
            sendInterrupt = { true },
            listener = object : DualSenseHidpListener {
                override fun onInput(
                    state: DualSenseInputState,
                    metadata: DualSenseBluetoothInputResult
                ) {
                    input = state
                }

                override fun onInputRejected(disposition: DualSenseBluetoothInputDisposition) {
                    rejected += disposition
                }

                override fun onClosed() = Unit
                override fun onFailure(failure: HidpFailure) = Unit
            }
        )

        session.l2capListener.onChannelsOpen(L2capHidChannels(0x40, 0x70, 0x41, 0x71))
        session.l2capListener.onControlData(byteArrayOf(0x00))
        assertArrayEquals(byteArrayOf(0x71), control[0])
        assertArrayEquals(byteArrayOf(0x43, 0x05), control[1])
        assertEquals(DualSenseHidpState.WAITING_FOR_VALID_INPUT, session.state)

        val corrupt = DualSenseBluetoothInputCodecTest.report(1).also {
            it[20] = (it[20].toInt() xor 1).toByte()
        }
        session.l2capListener.onInterruptData(byteArrayOf(0xA1.toByte()) + corrupt)
        assertNull(input)
        assertEquals(DualSenseBluetoothInputDisposition.INVALID_CRC, rejected.single())
        assertEquals(DualSenseHidpState.WAITING_FOR_VALID_INPUT, session.state)

        session.l2capListener.onInterruptData(
            byteArrayOf(0xA1.toByte()) + DualSenseBluetoothInputCodecTest.report(1)
        )
        assertTrue(input != null)
        assertEquals(DualSenseHidpState.READY, session.state)
    }

    @Test
    fun failsTheSessionWhenFullInputFeatureRequestCannotBeSent() {
        var sends = 0
        var receivedFailure: HidpFailure? = null
        val session = DualSenseHidpSession(
            sendControl = { ++sends == 1 },
            sendInterrupt = { true },
            listener = object : DualSenseHidpListener {
                override fun onInput(
                    state: DualSenseInputState,
                    metadata: DualSenseBluetoothInputResult
                ) = Unit

                override fun onClosed() = Unit

                override fun onFailure(failure: HidpFailure) {
                    receivedFailure = failure
                }
            }
        )

        session.l2capListener.onChannelsOpen(L2capHidChannels(0x40, 0x70, 0x41, 0x71))
        session.l2capListener.onControlData(byteArrayOf(0x00))

        assertEquals(DualSenseHidpState.FAILED, session.state)
        assertEquals(HidpErrorCode.SEND_FAILED, receivedFailure?.code)
    }
}

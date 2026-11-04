package com.limelight.binding.input.touchpad

import com.limelight.binding.input.touchpad.ScreenDs5ClickGestureDetector.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenDs5ClickGestureDetectorTest {
    private val detector = ScreenDs5ClickGestureDetector(24f, 300L)

    private fun firstTap() {
        assertEquals(Action.NONE, detector.onDown(0, 100f, 200f, 1000L, false))
        assertEquals(Action.NONE, detector.onUp(0, 100f, 200f, 1050L))
    }

    private fun startHold() {
        firstTap()
        assertEquals(Action.PRESS, detector.onDown(1, 100f, 200f, 1150L, false))
    }

    @Test fun secondDownPressesImmediatelyAndLiftReleasesWithoutAnotherClick() {
        startHold()
        assertEquals(Action.RELEASE, detector.onUp(1, 100f, 200f, 1200L))
        assertEquals(Action.NONE, detector.cancel())
    }

    @Test fun holdingAndDraggingKeepsTheButtonDownUntilLift() {
        startHold()
        detector.onMove(1, 500f, 700f)
        assertEquals(Action.RELEASE, detector.onUp(1, 500f, 700f, 6150L))
    }

    @Test fun stationaryLongTouchDoesNotBecomeAButtonPressOrPrimeADoubleTap() {
        assertEquals(Action.NONE, detector.onDown(0, 100f, 200f, 1000L, false))
        assertEquals(Action.NONE, detector.onUp(0, 100f, 200f, 6000L))
        assertEquals(Action.NONE, detector.onDown(0, 100f, 200f, 6100L, false))
    }

    @Test fun doubleTapTimeoutIncludesTheBoundaryAndRejectsTheNextMillisecond() {
        for ((time, expected) in listOf(1350L to Action.PRESS, 1351L to Action.NONE)) {
            detector.cancel()
            firstTap()
            assertEquals("Second contact at $time", expected,
                detector.onDown(0, 100f, 200f, time, false))
        }
    }

    @Test fun distantSecondContactDoesNotClick() {
        firstTap()
        assertEquals(Action.NONE, detector.onDown(0, 125f, 200f, 1150L, false))
    }

    @Test fun movementDisqualifiesTheFirstContactEvenIfItReturns() {
        detector.onDown(0, 100f, 200f, 1000L, false)
        detector.onMove(0, 140f, 200f)
        detector.onMove(0, 100f, 200f)
        assertEquals(Action.NONE, detector.onUp(0, 100f, 200f, 1050L))
        assertEquals(Action.NONE, detector.onDown(0, 100f, 200f, 1150L, false))
    }

    @Test fun twoFingerTouchCannotPrimeADoubleTap() {
        detector.onDown(0, 100f, 200f, 1000L, false)
        detector.onPointerDown()
        assertEquals(Action.NONE, detector.onUp(0, 100f, 200f, 1050L))
        assertEquals(Action.NONE, detector.onDown(0, 100f, 200f, 1150L, false))
    }

    @Test fun cancelClearsThePendingFirstTap() {
        firstTap()
        assertEquals(Action.NONE, detector.cancel())
        assertEquals(Action.NONE, detector.onDown(0, 100f, 200f, 1150L, false))
    }

    @Test fun cancelReleasesAHeldButtonAndCannotPrimeAnotherHold() {
        startHold()
        assertEquals(Action.RELEASE, detector.cancel())
        assertEquals(Action.NONE, detector.onDown(0, 100f, 200f, 1250L, false))
    }

    @Test fun anotherFingerDoesNotReleaseOrTakeOverTheHeldButton() {
        startHold()
        detector.onPointerDown()
        assertEquals(Action.NONE, detector.onPointerUp(2))
        assertEquals(Action.RELEASE, detector.onPointerUp(1))
        assertEquals(Action.NONE, detector.onUp(2, 100f, 200f, 1250L))
    }

    @Test fun pressureDropCannotReleaseAnExplicitDoubleTapHold() {
        startHold()
        assertEquals(Action.NONE, detector.onPressureChanged(true))
        assertEquals(Action.NONE, detector.onPressureChanged(false))
        assertEquals(Action.RELEASE, detector.onUp(1, 100f, 200f, 2150L))
    }

    @Test fun pressureReleaseDoesNotTapOrPrimeAnotherHoldInEitherMode() {
        for (legacy in listOf(false, true)) {
            detector.cancel()
            assertEquals(Action.NONE, detector.onDown(0, 100f, 200f, 1000L, legacy))
            assertEquals(Action.PRESS, detector.onPressureChanged(true))
            assertEquals(Action.RELEASE, detector.onPressureChanged(false))
            assertEquals(Action.NONE, detector.onUp(0, 100f, 200f, 1100L))
            assertEquals(Action.NONE, detector.onDown(0, 100f, 200f, 1200L, legacy))
        }
    }

    @Test fun pressureHeldUntilLiftReleasesExactlyOnce() {
        detector.onDown(0, 100f, 200f, 1000L, false)
        assertEquals(Action.PRESS, detector.onPressureChanged(true))
        assertEquals(Action.RELEASE, detector.onUp(0, 100f, 200f, 6000L))
        assertEquals(Action.NONE, detector.onPressureChanged(false))
        assertEquals(Action.NONE, detector.cancel())
    }

    @Test fun legacyModeKeepsSingleTapClickWithoutDoubleTapHold() {
        assertEquals(Action.NONE, detector.onDown(0, 100f, 200f, 1000L, true))
        assertEquals(Action.TAP, detector.onUp(0, 100f, 200f, 1050L))
        assertEquals(Action.NONE, detector.onDown(0, 100f, 200f, 1150L, true))
        assertEquals(Action.NONE, detector.onUp(0, 100f, 200f, 6150L))
    }

    @Test fun changingToLegacyModeCannotConsumeAPendingStandardTap() {
        firstTap()
        assertEquals(Action.NONE, detector.onDown(0, 100f, 200f, 1150L, true))
        assertEquals(Action.TAP, detector.onUp(0, 100f, 200f, 1200L))
    }

    @Test fun repeatedDoubleClicksEachHaveOnePressAndOneRelease() {
        startHold()
        assertEquals(Action.RELEASE, detector.onUp(1, 100f, 200f, 1200L))
        assertEquals(Action.NONE, detector.onDown(0, 100f, 200f, 1250L, false))
        assertEquals(Action.NONE, detector.onUp(0, 100f, 200f, 1300L))
        assertEquals(Action.PRESS, detector.onDown(0, 100f, 200f, 1350L, false))
        assertEquals(Action.RELEASE, detector.onUp(0, 100f, 200f, 1400L))
    }
}

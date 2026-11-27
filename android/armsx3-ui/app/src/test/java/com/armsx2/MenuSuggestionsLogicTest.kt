package com.armsx2

import com.armsx2.ui.emulation.SessionOrder
import com.armsx2.ui.settings.firstNumberIn
import com.armsx2.ui.settings.sliderStepForTyped
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pure parts of the typed slider values and the first tab's saved order. */
class MenuSuggestionsLogicTest {
    private val percent: (Int) -> String = { "$it%" }
    private val fpsCap: (Int) -> String = { if (it == 0) "Off" else "$it fps" }
    private val speed: (Int) -> String = { if (it >= 11) "Unlimited" else "${it}×" }

    @Test fun typedNumberPicksItsStep() {
        assertEquals(150, sliderStepForTyped("150", 10, 300, percent))
        assertEquals(150, sliderStepForTyped(" 150% ", 10, 300, percent))
    }

    @Test fun typedNumberOutsideTheRangeTakesTheNearestEnd() {
        assertEquals(300, sliderStepForTyped("1000", 10, 300, percent))
        assertEquals(10, sliderStepForTyped("2", 10, 300, percent))
    }

    @Test fun aStepWithoutANumberIsReachedByItsLabelOrPastItsEnd() {
        assertEquals(0, sliderStepForTyped("off", 0, 240, fpsCap))
        assertEquals(0, sliderStepForTyped("0", 0, 240, fpsCap))
        assertEquals(60, sliderStepForTyped("60", 0, 240, fpsCap))
        assertEquals(11, sliderStepForTyped("50", 2, 11, speed))
        assertEquals(4, sliderStepForTyped("4", 2, 11, speed))
    }

    @Test fun nothingUsefulTypedChangesNothing() {
        assertNull(sliderStepForTyped("", 10, 300, percent))
        assertNull(sliderStepForTyped("abc", 10, 300, percent))
    }

    @Test fun labelsInTheUsersLocaleReadTheirDecimalComma() {
        assertEquals(59.94, firstNumberIn("59,94 Hz")!!, 1e-9)
        assertEquals(59.94, firstNumberIn("59.94 Hz")!!, 1e-9)
        assertNull(firstNumberIn("Off"))
    }

    @Test fun savedOrderWinsAndNewRowsFollowTheirDefaultNeighbour() {
        // "c" is new: it goes right after "b", the row it follows by default, wherever "b" is now.
        assertEquals(listOf("b", "c", "a"), SessionOrder.merge(listOf("b", "a"), listOf("a", "b", "c")))
        assertEquals(listOf("c", "a", "x"), SessionOrder.merge(listOf("c", "a"), listOf("a", "x", "c")))
        assertEquals(listOf("first", "b", "a"), SessionOrder.merge(listOf("b", "a"), listOf("first", "a", "b")))
        // A saved key that is not shown right now (Swap Disc in a single-disc game) is left out, not invented.
        assertEquals(listOf("a", "b"), SessionOrder.merge(listOf("swapDisc", "a", "b"), listOf("a", "b")))
    }

    @Test fun aRowNotShownKeepsItsPlaceAfterTheRowItFollowed() {
        // Swap Disc was saved after Resume; arranged again in a game without it, Close went to the top.
        assertEquals(
            listOf("close", "resume", "swapDisc", "restart"),
            SessionOrder.keepHidden(listOf("close", "resume", "restart"), listOf("resume", "swapDisc", "restart", "close")),
        )
        // Saved first, it stays first; two hidden in a row keep their order.
        assertEquals(listOf("h1", "h2", "b", "a"), SessionOrder.keepHidden(listOf("b", "a"), listOf("h1", "h2", "a", "b")))
        // Nothing saved yet: the order as arranged.
        assertEquals(listOf("a", "b"), SessionOrder.keepHidden(listOf("a", "b"), emptyList()))
    }
}

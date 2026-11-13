package com.winlator.star.ui.deck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The XMB in-game drawer's width (Lab's: 46% of the screen, 420..600 dp) and when it stacks its rows. No Android classes involved. */
class XmbInGameDrawerTest {

    @Test
    fun aCompactScreenGetsTheWholeWidth() {
        assertEquals(412f, xmbInGameDrawerWidth(412f, compact = true), 0.001f)
    }

    @Test
    fun aWideScreenGetsFortySixPercent() {
        assertEquals(460f, xmbInGameDrawerWidth(1000f, compact = false), 0.001f)
    }

    @Test
    fun theWidthIsHeldBetween420And600() {
        assertEquals(420f, xmbInGameDrawerWidth(700f, compact = false), 0.001f)
        assertEquals(600f, xmbInGameDrawerWidth(2000f, compact = false), 0.001f)
    }

    @Test
    fun aScreenNarrowerThanTheMinimumIsNeverExceeded() {
        // Not compact (e.g. a landscape window) but under 420 dp: the drawer fits the screen instead of overflowing it.
        assertEquals(300f, xmbInGameDrawerWidth(300f, compact = false), 0.001f)
    }

    @Test
    fun narrowDrawersStackTheirRows() {
        assertTrue(xmbInGameDrawerStacksRows(420f))
        assertTrue(xmbInGameDrawerStacksRows(XMB_DRAWER_STACK_BELOW_DP - 1f))
        assertFalse(xmbInGameDrawerStacksRows(XMB_DRAWER_STACK_BELOW_DP))
        assertFalse(xmbInGameDrawerStacksRows(600f))
    }
}

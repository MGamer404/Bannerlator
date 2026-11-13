package com.winlator.star.ui.deck

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The interface sounds of the XMB (XmbSoundSynth.kt, XmbSounds.kt): their length, level and edges, and the rate limits. No Android classes involved. */
class XmbSoundSynthTest {

    private val rate = XmbSoundSynth.SAMPLE_RATE

    private fun render(kind: XmbSoundKind) = XmbSoundSynth.render(kind, rate)

    private fun peak(s: ShortArray) = s.maxOf { abs(it.toInt()) }

    private fun millis(s: ShortArray) = s.size * 1000.0 / rate

    // ---- The sounds ----

    @Test
    fun eachSoundHasItsLength() {
        assertEquals(60.0, millis(render(XmbSoundKind.FOCUS)), 5.0)
        assertEquals(130.0, millis(render(XmbSoundKind.SELECT)), 10.0)
        assertEquals(100.0, millis(render(XmbSoundKind.BACK)), 10.0)
        assertEquals(120.0, millis(render(XmbSoundKind.PAGE)), 10.0)
    }

    @Test
    fun noSoundIsLouderThanHalfScaleAndNoneIsSilent() {
        for (kind in XmbSoundKind.entries) {
            val p = peak(render(kind))
            assertTrue("$kind peaks at $p", p <= 0.5 * Short.MAX_VALUE)
            assertTrue("$kind peaks at $p", p >= 0.1 * Short.MAX_VALUE)
        }
    }

    @Test
    fun theFocusTickIsTheQuietest() {
        val focus = peak(render(XmbSoundKind.FOCUS))
        for (kind in listOf(XmbSoundKind.SELECT, XmbSoundKind.BACK, XmbSoundKind.PAGE)) {
            assertTrue("$kind", focus < peak(render(kind)))
        }
    }

    @Test
    fun thePageSoundIsQuieterThanSelectAndBack() {
        val page = peak(render(XmbSoundKind.PAGE))
        assertTrue(page < peak(render(XmbSoundKind.SELECT)))
        assertTrue(page < peak(render(XmbSoundKind.BACK)))
    }

    @Test
    fun everySoundStartsAndEndsNearZero() {
        val near = 0.01 * Short.MAX_VALUE
        for (kind in XmbSoundKind.entries) {
            val s = render(kind)
            assertTrue("$kind starts at ${s.first()}", abs(s.first().toInt()) <= near)
            assertTrue("$kind ends at ${s.last()}", abs(s.last().toInt()) <= near)
        }
    }

    @Test
    fun theSameCallGivesTheSameSamples() {
        for (kind in XmbSoundKind.entries) assertArrayEquals("$kind", render(kind), render(kind))
    }

    @Test
    fun theSoundsDifferFromEachOther() {
        val all = XmbSoundKind.entries.map { render(it).toList() }
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun aHigherRateGivesProportionallyMoreSamples() {
        for (kind in XmbSoundKind.entries) {
            val low = XmbSoundSynth.render(kind, 22_050).size
            val high = XmbSoundSynth.render(kind, 44_100).size
            assertEquals("$kind", high / 2.0, low.toDouble(), 1.0)
        }
    }

    // ---- The numbers the sounds are made from ----

    @Test
    fun theDefaultNumbersAreTheSoundsAsTheyShip() {
        val d = XmbSoundParams.DEFAULT
        assertEquals(XmbFocusParams(pitchHz = 1000, lengthMs = 60, attackMs = 4, decayMs = 16, peakPercent = 17), d.focus)
        assertEquals(
            XmbSelectParams(
                note1Hz = 880, note2Hz = 1320, note2StartMs = 45, lengthMs = 130, attackMs = 5, decay1Ms = 45, decay2Ms = 60,
                harmonicPercent = 20, peakPercent = 45,
            ),
            d.select,
        )
        assertEquals(
            XmbBackParams(startHz = 600, glidePercent = -10, lengthMs = 100, attackMs = 5, decayMs = 35, harmonicPercent = 15, peakPercent = 40),
            d.back,
        )
        assertEquals(
            XmbPageParams(startHz = 520, glidePercent = 20, lengthMs = 120, attackMs = 12, decayMs = 50, harmonicPercent = 8, peakPercent = 22),
            d.page,
        )
    }

    @Test
    fun noNumbersGiveTheDefaultSounds() {
        for (kind in XmbSoundKind.entries) {
            assertArrayEquals("$kind", XmbSoundSynth.render(kind, rate), XmbSoundSynth.render(kind, rate, XmbSoundParams.DEFAULT))
        }
    }

    @Test
    fun aChangedNumberChangesOnlyItsOwnSound() {
        val changed = XmbSoundParams(focus = XmbFocusParams(pitchHz = 2000))
        assertFalse(render(XmbSoundKind.FOCUS).contentEquals(XmbSoundSynth.render(XmbSoundKind.FOCUS, rate, changed)))
        for (kind in listOf(XmbSoundKind.SELECT, XmbSoundKind.BACK, XmbSoundKind.PAGE)) {
            assertArrayEquals("$kind", render(kind), XmbSoundSynth.render(kind, rate, changed))
        }
    }

    @Test
    fun theLengthAndPeakFollowTheNumbers() {
        val p = XmbSoundParams(page = XmbPageParams(lengthMs = 250, peakPercent = 30))
        val s = XmbSoundSynth.render(XmbSoundKind.PAGE, rate, p)
        assertEquals(250.0, millis(s), 1.0)
        assertEquals(0.30 * Short.MAX_VALUE, peak(s).toDouble(), 2.0)
    }

    @Test
    fun theRateLimitWindowsOfTheDefaultsAreTheShippedOnes() {
        assertEquals(XmbSoundLimiter.FOCUS_GAP_MS, XmbSoundParams.DEFAULT.focusGapMs)
        assertEquals(XmbSoundLimiter.ACTION_GAP_MS, XmbSoundParams.DEFAULT.actionGapMs)
    }

    @Test
    fun theRateLimitWindowsFollowTheLengths() {
        val p = XmbSoundParams(focus = XmbFocusParams(lengthMs = 100), select = XmbSelectParams(lengthMs = 300))
        assertEquals(100L, p.focusGapMs)
        assertEquals(320L, p.actionGapMs)
    }

    @Test
    fun aLimiterUsesTheWindowsItIsGiven() {
        val limiter = XmbSoundLimiter(focusGapMs = 100L, actionGapMs = 320L)
        assertTrue(limiter.allow(XmbSoundKind.FOCUS, 1_000L))
        assertFalse(limiter.allow(XmbSoundKind.FOCUS, 1_099L))
        assertTrue(limiter.allow(XmbSoundKind.SELECT, 1_100L))
        assertFalse(limiter.allow(XmbSoundKind.PAGE, 1_419L))
        assertTrue(limiter.allow(XmbSoundKind.PAGE, 1_420L))
    }

    // ---- The WAV file SoundPool loads ----

    @Test
    fun theWavHasAPcmHeaderAndTheSamples() {
        val samples = render(XmbSoundKind.SELECT)
        val wav = XmbSoundSynth.wav(samples, rate)
        assertEquals(44 + samples.size * 2, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        fun le(at: Int, bytes: Int): Int = (0 until bytes).sumOf { (wav[at + it].toInt() and 0xFF) shl (8 * it) }
        assertEquals(wav.size - 8, le(4, 4))
        assertEquals(1, le(20, 2))
        assertEquals(1, le(22, 2))
        assertEquals(rate, le(24, 4))
        assertEquals(16, le(34, 2))
        assertEquals(samples.size * 2, le(40, 4))
        // The first sample after the header, little endian.
        assertEquals(samples[1].toInt(), (le(46, 2) shl 16) shr 16)
    }

    // ---- The rate limits ----

    @Test
    fun aTickWaitsForThePreviousTick() {
        val limiter = XmbSoundLimiter()
        assertTrue(limiter.allow(XmbSoundKind.FOCUS, 1_000L))
        assertFalse(limiter.allow(XmbSoundKind.FOCUS, 1_000L + XmbSoundLimiter.FOCUS_GAP_MS - 1))
        assertTrue(limiter.allow(XmbSoundKind.FOCUS, 1_000L + XmbSoundLimiter.FOCUS_GAP_MS))
    }

    @Test
    fun aTickRightAfterASelectBackOrPageSoundIsPartOfIt() {
        for (kind in listOf(XmbSoundKind.SELECT, XmbSoundKind.BACK, XmbSoundKind.PAGE)) {
            val limiter = XmbSoundLimiter()
            assertTrue("$kind", limiter.allow(kind, 5_000L))
            assertFalse("$kind", limiter.allow(XmbSoundKind.FOCUS, 5_000L + XmbSoundLimiter.ACTION_GAP_MS - 1))
            assertTrue("$kind", limiter.allow(XmbSoundKind.FOCUS, 5_000L + XmbSoundLimiter.ACTION_GAP_MS))
        }
    }

    @Test
    fun aKeyAndThePageChangeItCausesPlayOneSound() {
        val limiter = XmbSoundLimiter()
        assertTrue(limiter.allow(XmbSoundKind.SELECT, 2_000L))
        assertFalse(limiter.allow(XmbSoundKind.PAGE, 2_040L))
        assertTrue(limiter.allow(XmbSoundKind.PAGE, 2_000L + XmbSoundLimiter.ACTION_GAP_MS))
    }

    @Test
    fun aTickDoesNotHoldBackASelect() {
        val limiter = XmbSoundLimiter()
        assertTrue(limiter.allow(XmbSoundKind.FOCUS, 3_000L))
        assertTrue(limiter.allow(XmbSoundKind.SELECT, 3_001L))
    }

    @Test
    fun theFirstSoundOfAnyKindPlaysAtAnyUptime() {
        for (kind in XmbSoundKind.entries) assertTrue("$kind", XmbSoundLimiter().allow(kind, 0L))
    }
}

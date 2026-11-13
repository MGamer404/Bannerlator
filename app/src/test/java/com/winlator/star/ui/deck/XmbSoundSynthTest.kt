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
        assertEquals(40.0, millis(render(XmbSoundKind.FOCUS)), 5.0)
        assertEquals(130.0, millis(render(XmbSoundKind.SELECT)), 10.0)
        assertEquals(100.0, millis(render(XmbSoundKind.BACK)), 10.0)
        assertEquals(180.0, millis(render(XmbSoundKind.PAGE)), 10.0)
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

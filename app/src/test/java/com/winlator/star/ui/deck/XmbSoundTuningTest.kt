package com.winlator.star.ui.deck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The temporary sound tuning (XmbSoundTuning.kt): its table of numbers, their ranges, what is stored and the text that is copied. No Android classes involved. */
class XmbSoundTuningTest {

    private val fields = XmbSoundTuning.fields

    private fun field(kind: XmbSoundKind, name: String) = fields.first { it.kind == kind && it.name == name }

    @Test
    fun everyNumberOfEverySoundHasAField() {
        assertEquals(5, XmbSoundTuning.fieldsOf(XmbSoundKind.FOCUS).size)
        assertEquals(9, XmbSoundTuning.fieldsOf(XmbSoundKind.SELECT).size)
        assertEquals(7, XmbSoundTuning.fieldsOf(XmbSoundKind.BACK).size)
        assertEquals(7, XmbSoundTuning.fieldsOf(XmbSoundKind.PAGE).size)
        assertEquals(28, fields.size)
    }

    @Test
    fun theFieldsComeSoundBySoundInTheOrderOfTheKinds() {
        assertEquals(XmbSoundKind.entries.flatMap { k -> fields.filter { it.kind == k } }, fields)
    }

    @Test
    fun keysAndIdsAreUnique() {
        assertEquals(fields.size, fields.map { it.key }.toSet().size)
        assertEquals(fields.size, fields.map { it.id }.toSet().size)
        assertEquals("xmb_sound_focus_pitchHz", field(XmbSoundKind.FOCUS, "pitchHz").key)
        assertEquals("app.appearance.tune.select.note2StartMs", field(XmbSoundKind.SELECT, "note2StartMs").id)
    }

    @Test
    fun everyDefaultIsInsideItsRangeAndOnItsStepGrid() {
        for (f in fields) {
            assertTrue("${f.id} default ${f.default}", f.default in f.min..f.max)
            assertEquals("${f.id} default ${f.default} off the step grid", 0, (f.default - f.min) % f.step)
            assertTrue("${f.id}: the range is a whole number of steps", (f.max - f.min) % f.step == 0)
        }
    }

    @Test
    fun theDefaultsAreTheDefaultParameters() {
        assertEquals(XmbSoundParams.DEFAULT, XmbSoundTuning.params { null })
    }

    @Test
    fun eachFieldReadsAndWritesItsOwnNumber() {
        for (f in fields) {
            val changed = f.write(XmbSoundParams.DEFAULT, f.max)
            assertEquals(f.id, f.max, f.read(changed))
            // Nothing else moved.
            val others = fields.filter { it !== f }
            assertTrue(f.id, others.all { it.read(changed) == it.read(XmbSoundParams.DEFAULT) })
        }
    }

    @Test
    fun aStoredNumberIsKeptAndHeldToItsRange() {
        val pitch = field(XmbSoundKind.FOCUS, "pitchHz")
        assertEquals(1500, XmbSoundTuning.params { if (it === pitch) 1500 else null }.focus.pitchHz)
        assertEquals(pitch.max, XmbSoundTuning.params { if (it === pitch) 99_999 else null }.focus.pitchHz)
        assertEquals(pitch.min, XmbSoundTuning.params { if (it === pitch) -5 else null }.focus.pitchHz)
        val glide = field(XmbSoundKind.BACK, "glidePercent")
        assertEquals(-30, XmbSoundTuning.params { if (it === glide) -30 else null }.back.glidePercent)
        assertEquals(glide.min, XmbSoundTuning.params { if (it === glide) -500 else null }.back.glidePercent)
    }

    @Test
    fun clampHoldsAValueToTheRange() {
        val f = field(XmbSoundKind.SELECT, "lengthMs")
        assertEquals(f.min, f.clamp(f.min - 1))
        assertEquals(f.max, f.clamp(f.max + 1))
        assertEquals(f.default, f.clamp(f.default))
    }

    @Test
    fun aValueIsShownWithItsUnit() {
        assertEquals("1000 Hz", field(XmbSoundKind.FOCUS, "pitchHz").show(1000))
        assertEquals("60 ms", field(XmbSoundKind.FOCUS, "lengthMs").show(60))
        assertEquals("17%", field(XmbSoundKind.FOCUS, "peakPercent").show(17))
        assertEquals("-10%", field(XmbSoundKind.BACK, "glidePercent").show(-10))
    }

    @Test
    fun theExportedTextHasOneLinePerSoundWithAllItsNumbers() {
        val expected = listOf(
            "FOCUS pitchHz=1000 lengthMs=60 attackMs=4 decayMs=16 peakPercent=17",
            "SELECT note1Hz=880 note2Hz=1320 note2StartMs=45 lengthMs=130 attackMs=5 decay1Ms=45 decay2Ms=60 harmonicPercent=20 peakPercent=45",
            "BACK startHz=600 glidePercent=-10 lengthMs=100 attackMs=5 decayMs=35 harmonicPercent=15 peakPercent=40",
            "PAGE startHz=520 glidePercent=20 lengthMs=120 attackMs=12 decayMs=50 harmonicPercent=8 peakPercent=22",
        ).joinToString("\n")
        assertEquals(expected, XmbSoundTuning.exportText(XmbSoundParams.DEFAULT))
    }

    @Test
    fun theExportedTextShowsTheChangedNumbers() {
        val pitch = field(XmbSoundKind.FOCUS, "pitchHz")
        val glide = field(XmbSoundKind.PAGE, "glidePercent")
        val p = XmbSoundTuning.params { if (it === pitch) 1100 else if (it === glide) 35 else null }
        val lines = XmbSoundTuning.exportText(p).lines()
        assertEquals(4, lines.size)
        assertTrue(lines[0], lines[0].startsWith("FOCUS pitchHz=1100 "))
        assertTrue(lines[3], lines[3].contains(" glidePercent=35 "))
    }

    @Test
    fun everySoundStaysQuietAndCleanAtTheEndsOfEveryRange() {
        val near = 0.01 * Short.MAX_VALUE
        for (f in fields) {
            for (value in listOf(f.min, f.max)) {
                val p = f.write(XmbSoundParams.DEFAULT, value)
                val s = XmbSoundSynth.render(f.kind, XmbSoundSynth.SAMPLE_RATE, p)
                assertTrue("${f.id}=$value is empty", s.isNotEmpty())
                val loudest = s.maxOf { abs(it.toInt()) }
                assertTrue("${f.id}=$value peaks at $loudest", loudest <= 0.6 * Short.MAX_VALUE + 1)
                assertTrue("${f.id}=$value starts at ${s.first()}", abs(s.first().toInt()) <= near)
                assertTrue("${f.id}=$value ends at ${s.last()}", abs(s.last().toInt()) <= near)
            }
        }
    }
}

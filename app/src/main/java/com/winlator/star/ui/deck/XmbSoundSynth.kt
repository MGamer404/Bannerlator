package com.winlator.star.ui.deck

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// The interface sounds of the XMB (XmbSounds.kt plays them): four short, quiet sounds computed here from plain sine tones and decays,
// shaped by the numbers of XmbSoundParams. Nothing is recorded or loaded: every sample comes out of the formulas below, the same on
// every call, so the sounds are the same on every device and can be tested without Android.
//
//   FOCUS   a barely-there soft, round "tok": one low sine with a slow attack and a smooth decay.
//   SELECT  a warm two-note chime rising by a fifth, each note a sine with a quiet 2nd harmonic and a soft attack.
//   BACK    one lower soft note that sinks a little in pitch.
//   PAGE    a short, quiet sine that glides up a little, with a very quiet 2nd harmonic.
//
// Each sound is scaled to its own peak, well under full scale, and ends on a short fade, so none of them clips or clicks.

/** The sounds of the XMB interface. */
internal enum class XmbSoundKind { FOCUS, SELECT, BACK, PAGE }

internal object XmbSoundSynth {

    const val SAMPLE_RATE = 44_100

    private const val FULL_SCALE = 32767.0
    private const val TWO_PI = 2.0 * PI

    // The closing fade (ms) of each sound, held to a third of a shorter sound.
    private const val FOCUS_FADE_MS = 10
    private const val SELECT_FADE_MS = 30
    private const val BACK_FADE_MS = 25
    private const val PAGE_FADE_MS = 30

    // SELECT: the first note is a little quieter than the second.
    private const val SELECT_FIRST_GAIN = 0.8

    /**
     * [kind] as 16-bit mono samples at [sampleRate], shaped by [params]. Pure and deterministic: the same arguments give the same
     * samples.
     */
    fun render(kind: XmbSoundKind, sampleRate: Int, params: XmbSoundParams = XmbSoundParams.DEFAULT): ShortArray {
        val rate = sampleRate.toDouble()
        return when (kind) {
            XmbSoundKind.FOCUS -> params.focus.let { p ->
                shape(p.lengthMs, p.peakPercent, FOCUS_FADE_MS, rate) { x, r -> focus(x, r, p) }
            }
            XmbSoundKind.SELECT -> params.select.let { p ->
                shape(p.lengthMs, p.peakPercent, SELECT_FADE_MS, rate) { x, r -> select(x, r, p) }
            }
            XmbSoundKind.BACK -> params.back.let { p ->
                shape(p.lengthMs, p.peakPercent, BACK_FADE_MS, rate) { x, r ->
                    glide(x, r, p.startHz, p.glidePercent, p.attackMs, p.decayMs, p.harmonicPercent)
                }
            }
            XmbSoundKind.PAGE -> params.page.let { p ->
                shape(p.lengthMs, p.peakPercent, PAGE_FADE_MS, rate) { x, r ->
                    glide(x, r, p.startHz, p.glidePercent, p.attackMs, p.decayMs, p.harmonicPercent)
                }
            }
        }
    }

    /**
     * A sound of [lengthMs] filled in by [fill], then faded out over [fadeMs] (at most a third of the sound) and scaled to
     * [peakPercent] of full scale.
     */
    private fun shape(lengthMs: Int, peakPercent: Int, fadeMs: Int, rate: Double, fill: (DoubleArray, Double) -> Unit): ShortArray {
        val x = DoubleArray((lengthMs / 1000.0 * rate).roundToInt())
        fill(x, rate)
        return pcm(x, peakPercent / 100.0, (min(fadeMs, lengthMs / 3) / 1000.0 * rate).roundToInt())
    }

    /** [samples] as the bytes of a WAV file (16-bit mono PCM at [sampleRate]), which is what SoundPool loads. */
    fun wav(samples: ShortArray, sampleRate: Int): ByteArray {
        val data = samples.size * 2
        val out = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + data).put("WAVE".toByteArray(Charsets.US_ASCII))
        out.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(1)
            .putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        out.put("data".toByteArray(Charsets.US_ASCII)).putInt(data)
        for (s in samples) out.putShort(s)
        return out.array()
    }

    private fun focus(x: DoubleArray, rate: Double, p: XmbFocusParams) {
        val decay = p.decayMs / 1000.0
        val attackS = p.attackMs / 1000.0
        for (i in x.indices) {
            val t = i / rate
            x[i] = sin(TWO_PI * p.pitchHz * t) * exp(-t / decay) * attack(t, attackS)
        }
    }

    /** A bell-like tone: a sine at [hz] and a quiet 2nd harmonic of level [harmonic], at time [t] into the note. */
    private fun bell(t: Double, hz: Double, harmonic: Double): Double =
        sin(TWO_PI * hz * t) + harmonic * sin(TWO_PI * 2.0 * hz * t)

    private fun select(x: DoubleArray, rate: Double, p: XmbSelectParams) {
        val harmonic = p.harmonicPercent / 100.0
        val attackS = p.attackMs / 1000.0
        val second = p.note2StartMs / 1000.0
        val decay1 = p.decay1Ms / 1000.0
        val decay2 = p.decay2Ms / 1000.0
        for (i in x.indices) {
            val t = i / rate
            val first = SELECT_FIRST_GAIN * bell(t, p.note1Hz.toDouble(), harmonic) * exp(-t / decay1) * attack(t, attackS)
            val u = t - second
            val next = if (u < 0.0) 0.0 else bell(u, p.note2Hz.toDouble(), harmonic) * exp(-u / decay2) * attack(u, attackS)
            x[i] = first + next
        }
    }

    /**
     * One note of a sine and a quiet 2nd harmonic whose pitch goes from [startHz] by [glidePercent] over the sound (linearly), with
     * an attack and an exponential decay.
     */
    private fun glide(x: DoubleArray, rate: Double, startHz: Int, glidePercent: Int, attackMs: Int, decayMs: Int, harmonicPercent: Int) {
        val length = x.size / rate
        val harmonic = harmonicPercent / 100.0
        val slope = glidePercent / 100.0
        val decay = decayMs / 1000.0
        val attackS = attackMs / 1000.0
        var phase = 0.0
        for (i in x.indices) {
            val t = i / rate
            x[i] = (sin(phase) + harmonic * sin(2.0 * phase)) * exp(-t / decay) * attack(t, attackS)
            phase += TWO_PI * startHz * (1.0 + slope * t / length) / rate
        }
    }

    /** 0 at [t] = 0, rising along a half cosine to 1 at [seconds], and 1 after: no click at the start. */
    private fun attack(t: Double, seconds: Double): Double = if (t >= seconds) 1.0 else 0.5 - 0.5 * cos(PI * t / seconds)

    /** [x] faded out over its last [fadeSamples] samples, scaled so its loudest sample is [peak] of full scale, as 16-bit samples. */
    private fun pcm(x: DoubleArray, peak: Double, fadeSamples: Int): ShortArray {
        val n = x.size
        val fade = min(fadeSamples, n)
        for (i in n - fade until n) x[i] *= 0.5 + 0.5 * cos(PI * (i - (n - fade)) / fade)
        val loudest = x.maxOf { abs(it) }
        val gain = if (loudest > 0.0) peak / loudest else 0.0
        return ShortArray(n) { (x[it] * gain * FULL_SCALE).roundToInt().toShort() }
    }
}

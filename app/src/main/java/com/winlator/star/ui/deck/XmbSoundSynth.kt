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

// The interface sounds of the XMB (XmbSounds.kt plays them): four short, quiet sounds computed here from plain sine tones, decays and
// filtered noise. Nothing is recorded or loaded: every sample comes out of the formulas below, the same on every call, so the sounds
// are the same on every device and can be tested without Android.
//
//   FOCUS   a soft glassy tick: a bright sine with a fast decay, a faint inharmonic overtone and a breath of noise at the start.
//   SELECT  a warm two-note chime rising by a fifth, each note a sine with a quiet 2nd harmonic and a soft attack.
//   BACK    one lower soft note that sinks a little in pitch.
//   PAGE    a gentle swell: low-passed noise rising and falling over a very soft tone.
//
// Each sound is scaled to its own peak, well under full scale, and ends on a short fade, so none of them clips or clicks.

/** The sounds of the XMB interface. */
internal enum class XmbSoundKind { FOCUS, SELECT, BACK, PAGE }

internal object XmbSoundSynth {

    const val SAMPLE_RATE = 44_100

    private const val FULL_SCALE = 32767.0
    private const val TWO_PI = 2.0 * PI

    // Length (s), peak (share of full scale) and closing fade (s) of each sound.
    private const val FOCUS_SECONDS = 0.040
    private const val FOCUS_PEAK = 0.25
    private const val FOCUS_FADE_S = 0.005
    private const val SELECT_SECONDS = 0.130
    private const val SELECT_PEAK = 0.45
    private const val SELECT_FADE_S = 0.030
    private const val BACK_SECONDS = 0.100
    private const val BACK_PEAK = 0.40
    private const val BACK_FADE_S = 0.025
    private const val PAGE_SECONDS = 0.180
    private const val PAGE_PEAK = 0.35
    private const val PAGE_FADE_S = 0.010

    // FOCUS: a 1.75 kHz sine decaying with a 7 ms time constant, an overtone at 2.76 x (glass is not harmonic), and noise through a
    // one-pole low-pass that dies away in 1.5 ms.
    private const val FOCUS_HZ = 1750.0
    private const val FOCUS_DECAY_S = 0.007
    private const val FOCUS_OVERTONE_RATIO = 2.76
    private const val FOCUS_OVERTONE_GAIN = 0.25
    private const val FOCUS_OVERTONE_DECAY_S = 0.004
    private const val FOCUS_NOISE_COEF = 0.45
    private const val FOCUS_NOISE_GAIN = 0.35
    private const val FOCUS_NOISE_DECAY_S = 0.0015
    private const val FOCUS_ATTACK_S = 0.001
    private const val FOCUS_SEED = 0x2F6E2B1

    // SELECT: 880 Hz, then 1320 Hz (a perfect fifth up) 45 ms later; each note a sine plus a 2nd harmonic at 0.2, a 5 ms attack, an
    // exponential decay.
    private const val SELECT_FIRST_HZ = 880.0
    private const val SELECT_SECOND_HZ = 1320.0
    private const val SELECT_SECOND_AT_S = 0.045
    private const val SELECT_FIRST_GAIN = 0.8
    private const val SELECT_FIRST_DECAY_S = 0.045
    private const val SELECT_SECOND_DECAY_S = 0.060
    private const val SELECT_HARMONIC_GAIN = 0.2
    private const val SELECT_ATTACK_S = 0.005

    // BACK: 600 Hz gliding down 10 % over the sound, a quiet 2nd harmonic, a 5 ms attack, a 35 ms decay.
    private const val BACK_HZ = 600.0
    private const val BACK_GLIDE = 0.10
    private const val BACK_HARMONIC_GAIN = 0.15
    private const val BACK_DECAY_S = 0.035
    private const val BACK_ATTACK_S = 0.005

    // PAGE: noise through two one-pole low-passes at 900 Hz and a 700 Hz sine under it, both under a sin^2 swell that is zero at both ends.
    private const val PAGE_CUTOFF_HZ = 900.0
    private const val PAGE_NOISE_GAIN = 3.0
    private const val PAGE_TONE_HZ = 700.0
    private const val PAGE_TONE_GAIN = 0.08
    private const val PAGE_SEED = 0x1B3A7D5

    /** [kind] as 16-bit mono samples at [sampleRate]. Pure and deterministic: the same arguments give the same samples. */
    fun render(kind: XmbSoundKind, sampleRate: Int): ShortArray {
        val rate = sampleRate.toDouble()
        return when (kind) {
            XmbSoundKind.FOCUS -> shape(FOCUS_SECONDS, FOCUS_PEAK, FOCUS_FADE_S, rate, this::focus)
            XmbSoundKind.SELECT -> shape(SELECT_SECONDS, SELECT_PEAK, SELECT_FADE_S, rate, this::select)
            XmbSoundKind.BACK -> shape(BACK_SECONDS, BACK_PEAK, BACK_FADE_S, rate, this::back)
            XmbSoundKind.PAGE -> shape(PAGE_SECONDS, PAGE_PEAK, PAGE_FADE_S, rate, this::page)
        }
    }

    /** A sound of [seconds] filled in by [fill], then faded out over [fadeSeconds] and scaled to [peak]. */
    private fun shape(seconds: Double, peak: Double, fadeSeconds: Double, rate: Double, fill: (DoubleArray, Double) -> Unit): ShortArray {
        val x = DoubleArray((seconds * rate).roundToInt())
        fill(x, rate)
        return pcm(x, peak, (fadeSeconds * rate).roundToInt())
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

    private fun focus(x: DoubleArray, rate: Double) {
        val noise = Noise(FOCUS_SEED)
        var lowPassed = 0.0
        for (i in x.indices) {
            val t = i / rate
            val glass = sin(TWO_PI * FOCUS_HZ * t) * exp(-t / FOCUS_DECAY_S) +
                FOCUS_OVERTONE_GAIN * sin(TWO_PI * FOCUS_HZ * FOCUS_OVERTONE_RATIO * t) * exp(-t / FOCUS_OVERTONE_DECAY_S)
            lowPassed += FOCUS_NOISE_COEF * (noise.next() - lowPassed)
            x[i] = (glass + FOCUS_NOISE_GAIN * lowPassed * exp(-t / FOCUS_NOISE_DECAY_S)) * attack(t, FOCUS_ATTACK_S)
        }
    }

    /** A bell-like tone: a sine at [hz] and a quiet 2nd harmonic, at time [t] into the note. */
    private fun bell(t: Double, hz: Double): Double =
        sin(TWO_PI * hz * t) + SELECT_HARMONIC_GAIN * sin(TWO_PI * 2.0 * hz * t)

    private fun select(x: DoubleArray, rate: Double) {
        for (i in x.indices) {
            val t = i / rate
            val first = SELECT_FIRST_GAIN * bell(t, SELECT_FIRST_HZ) * exp(-t / SELECT_FIRST_DECAY_S) * attack(t, SELECT_ATTACK_S)
            val u = t - SELECT_SECOND_AT_S
            val second = if (u < 0.0) 0.0 else bell(u, SELECT_SECOND_HZ) * exp(-u / SELECT_SECOND_DECAY_S) * attack(u, SELECT_ATTACK_S)
            x[i] = first + second
        }
    }

    private fun back(x: DoubleArray, rate: Double) {
        val length = x.size / rate
        var phase = 0.0
        for (i in x.indices) {
            val t = i / rate
            x[i] = (sin(phase) + BACK_HARMONIC_GAIN * sin(2.0 * phase)) * exp(-t / BACK_DECAY_S) * attack(t, BACK_ATTACK_S)
            phase += TWO_PI * BACK_HZ * (1.0 - BACK_GLIDE * t / length) / rate
        }
    }

    private fun page(x: DoubleArray, rate: Double) {
        val noise = Noise(PAGE_SEED)
        val coef = 1.0 - exp(-TWO_PI * PAGE_CUTOFF_HZ / rate)
        val length = x.size / rate
        var pole1 = 0.0
        var pole2 = 0.0
        for (i in x.indices) {
            val t = i / rate
            pole1 += coef * (noise.next() - pole1)
            pole2 += coef * (pole1 - pole2)
            val s = sin(PI * t / length)
            x[i] = s * s * (PAGE_NOISE_GAIN * pole2 + PAGE_TONE_GAIN * sin(TWO_PI * PAGE_TONE_HZ * t))
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

    /** White noise in [-1, 1) from a fixed seed (xorshift), so a sound is the same every time. */
    private class Noise(seed: Int) {
        private var state = seed

        fun next(): Double {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            return state / 2147483648.0
        }
    }
}

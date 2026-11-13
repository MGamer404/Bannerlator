package com.winlator.star.ui.deck

// The numbers that shape each XMB interface sound (XmbSoundSynth.kt turns them into samples). Whole numbers in the unit their name
// says, so they are easy to store, show and paste: Hz, ms, and percent (of full scale for a peak, of the main tone for a harmonic,
// of the pitch for a glide). The defaults are the sounds as they ship.

/** FOCUS: one soft sine, a raised-cosine attack, an exponential decay. */
internal data class XmbFocusParams(
    val pitchHz: Int = 1000,
    val lengthMs: Int = 60,
    val attackMs: Int = 4,
    val decayMs: Int = 16,
    val peakPercent: Int = 17,
)

/** SELECT: two bell notes (a sine and a 2nd harmonic), the second starting [note2StartMs] after the first. */
internal data class XmbSelectParams(
    val note1Hz: Int = 880,
    val note2Hz: Int = 1320,
    val note2StartMs: Int = 45,
    val lengthMs: Int = 130,
    val attackMs: Int = 5,
    val decay1Ms: Int = 45,
    val decay2Ms: Int = 60,
    val harmonicPercent: Int = 20,
    val peakPercent: Int = 45,
)

/** BACK: one note gliding by [glidePercent] of its pitch over the sound (negative sinks). */
internal data class XmbBackParams(
    val startHz: Int = 600,
    val glidePercent: Int = -10,
    val lengthMs: Int = 100,
    val attackMs: Int = 5,
    val decayMs: Int = 35,
    val harmonicPercent: Int = 15,
    val peakPercent: Int = 40,
)

/** PAGE: one note gliding by [glidePercent] of its pitch over the sound (positive rises). */
internal data class XmbPageParams(
    val startHz: Int = 520,
    val glidePercent: Int = 20,
    val lengthMs: Int = 120,
    val attackMs: Int = 12,
    val decayMs: Int = 50,
    val harmonicPercent: Int = 8,
    val peakPercent: Int = 22,
)

/** All four sounds. */
internal data class XmbSoundParams(
    val focus: XmbFocusParams = XmbFocusParams(),
    val select: XmbSelectParams = XmbSelectParams(),
    val back: XmbBackParams = XmbBackParams(),
    val page: XmbPageParams = XmbPageParams(),
) {
    /** The numbers of [kind]'s sound. */
    fun of(kind: XmbSoundKind): Any = when (kind) {
        XmbSoundKind.FOCUS -> focus
        XmbSoundKind.SELECT -> select
        XmbSoundKind.BACK -> back
        XmbSoundKind.PAGE -> page
    }

    /** How long a tick holds the next tick back: its own length. */
    val focusGapMs: Long get() = focus.lengthMs.toLong()

    /** How long a select, back or page sound holds the next one back: the longest of them and a little more. */
    val actionGapMs: Long get() = maxOf(select.lengthMs, back.lengthMs, page.lengthMs) + ACTION_GAP_MARGIN_MS

    companion object {
        val DEFAULT = XmbSoundParams()

        private const val ACTION_GAP_MARGIN_MS = 20L
    }
}

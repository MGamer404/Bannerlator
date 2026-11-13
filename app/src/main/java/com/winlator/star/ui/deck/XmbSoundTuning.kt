package com.winlator.star.ui.deck

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.preference.PreferenceManager
import com.winlator.star.ui.simple.T

// TEMPORARY: the tuning of the XMB interface sounds, to find the numbers by ear on a device. Settings > Appearance > Sounds shows one
// slider per number of every sound (XmbSoundTuningRows.kt); a slider keeps its value as a preference and the sound is rebuilt and
// played at once. Once the numbers are chosen they become the defaults of XmbSoundParams, and removing the tuning is deleting this file and
// XmbSoundTuningRows.kt, taking XmbSoundTuning.load out of XmbSounds.attach and the rows out of AppSettingsRegistry.defs.

/** One tunable number of one sound: where it lives in [XmbSoundParams], its range, and the preference it is kept in. */
internal class XmbTuneField(
    val kind: XmbSoundKind,
    /** The number's name in [XmbSoundParams] (also in the exported text). */
    val name: String,
    val label: String,
    val hint: String,
    /** "Hz", "ms" or "%". */
    val unit: String,
    val min: Int,
    val max: Int,
    val step: Int,
    val read: (XmbSoundParams) -> Int,
    val write: (XmbSoundParams, Int) -> XmbSoundParams,
) {
    val key: String = "xmb_sound_" + kind.name.lowercase() + "_" + name
    val id: String = "app.appearance.tune." + kind.name.lowercase() + "." + name
    val default: Int = read(XmbSoundParams.DEFAULT)

    fun clamp(value: Int): Int = value.coerceIn(min, max)

    /** [value] with its unit, as a slider shows it. */
    fun show(value: Int): String = if (unit == "%") "$value%" else "$value $unit"
}

internal object XmbSoundTuning {

    private fun focus(
        name: String, label: String, hint: String, unit: String, min: Int, max: Int, step: Int,
        read: (XmbFocusParams) -> Int, write: (XmbFocusParams, Int) -> XmbFocusParams,
    ) = XmbTuneField(
        XmbSoundKind.FOCUS, name, label, hint, unit, min, max, step,
        { read(it.focus) }, { p, v -> p.copy(focus = write(p.focus, v)) },
    )

    private fun select(
        name: String, label: String, hint: String, unit: String, min: Int, max: Int, step: Int,
        read: (XmbSelectParams) -> Int, write: (XmbSelectParams, Int) -> XmbSelectParams,
    ) = XmbTuneField(
        XmbSoundKind.SELECT, name, label, hint, unit, min, max, step,
        { read(it.select) }, { p, v -> p.copy(select = write(p.select, v)) },
    )

    private fun back(
        name: String, label: String, hint: String, unit: String, min: Int, max: Int, step: Int,
        read: (XmbBackParams) -> Int, write: (XmbBackParams, Int) -> XmbBackParams,
    ) = XmbTuneField(
        XmbSoundKind.BACK, name, label, hint, unit, min, max, step,
        { read(it.back) }, { p, v -> p.copy(back = write(p.back, v)) },
    )

    private fun page(
        name: String, label: String, hint: String, unit: String, min: Int, max: Int, step: Int,
        read: (XmbPageParams) -> Int, write: (XmbPageParams, Int) -> XmbPageParams,
    ) = XmbTuneField(
        XmbSoundKind.PAGE, name, label, hint, unit, min, max, step,
        { read(it.page) }, { p, v -> p.copy(page = write(p.page, v)) },
    )

    private val LENGTH = T("Total length of the sound.")
    private val ATTACK = T("Fade-in time: longer is softer.")
    private val DECAY = T("How fast it dies away: shorter is tighter.")
    private val HARMONIC = T("Level of the 2nd harmonic: more is brighter.")
    private val PEAK = T("Loudest point, before the volume setting.")
    private val GLIDE = T("Pitch change over the sound: negative sinks, positive rises.")

    /** Every tunable number, sound by sound (in the order of [XmbSoundKind]), each sound's numbers in the order they are shown and exported. */
    val fields: List<XmbTuneField> = listOf(
        focus("pitchHz", T("Pitch"), T("Pitch of the tone."), "Hz", 200, 4000, 50, { it.pitchHz }, { p, v -> p.copy(pitchHz = v) }),
        focus("lengthMs", T("Length"), LENGTH, "ms", 20, 200, 5, { it.lengthMs }, { p, v -> p.copy(lengthMs = v) }),
        focus("attackMs", T("Attack"), ATTACK, "ms", 1, 20, 1, { it.attackMs }, { p, v -> p.copy(attackMs = v) }),
        focus("decayMs", T("Decay"), DECAY, "ms", 3, 100, 1, { it.decayMs }, { p, v -> p.copy(decayMs = v) }),
        focus("peakPercent", T("Loudness"), PEAK, "%", 2, 60, 1, { it.peakPercent }, { p, v -> p.copy(peakPercent = v) }),

        select("note1Hz", T("First note pitch"), T("Pitch of the first note."), "Hz", 200, 3000, 10, { it.note1Hz }, { p, v -> p.copy(note1Hz = v) }),
        select("note2Hz", T("Second note pitch"), T("Pitch of the second note."), "Hz", 200, 4000, 10, { it.note2Hz }, { p, v -> p.copy(note2Hz = v) }),
        select("note2StartMs", T("Second note starts at"), T("How long after the first note the second begins."), "ms", 0, 200, 5, { it.note2StartMs }, { p, v -> p.copy(note2StartMs = v) }),
        select("lengthMs", T("Length"), LENGTH, "ms", 60, 400, 5, { it.lengthMs }, { p, v -> p.copy(lengthMs = v) }),
        select("attackMs", T("Attack"), ATTACK, "ms", 1, 40, 1, { it.attackMs }, { p, v -> p.copy(attackMs = v) }),
        select("decay1Ms", T("First note decay"), DECAY, "ms", 5, 300, 5, { it.decay1Ms }, { p, v -> p.copy(decay1Ms = v) }),
        select("decay2Ms", T("Second note decay"), DECAY, "ms", 5, 300, 5, { it.decay2Ms }, { p, v -> p.copy(decay2Ms = v) }),
        select("harmonicPercent", T("Bell harmonic"), HARMONIC, "%", 0, 100, 1, { it.harmonicPercent }, { p, v -> p.copy(harmonicPercent = v) }),
        select("peakPercent", T("Loudness"), PEAK, "%", 5, 60, 1, { it.peakPercent }, { p, v -> p.copy(peakPercent = v) }),

        back("startHz", T("Start pitch"), T("Pitch the note starts at."), "Hz", 150, 2000, 10, { it.startHz }, { p, v -> p.copy(startHz = v) }),
        back("glidePercent", T("Glide"), GLIDE, "%", -50, 50, 1, { it.glidePercent }, { p, v -> p.copy(glidePercent = v) }),
        back("lengthMs", T("Length"), LENGTH, "ms", 40, 400, 5, { it.lengthMs }, { p, v -> p.copy(lengthMs = v) }),
        back("attackMs", T("Attack"), ATTACK, "ms", 1, 40, 1, { it.attackMs }, { p, v -> p.copy(attackMs = v) }),
        back("decayMs", T("Decay"), DECAY, "ms", 5, 300, 5, { it.decayMs }, { p, v -> p.copy(decayMs = v) }),
        back("harmonicPercent", T("Harmonic"), HARMONIC, "%", 0, 100, 1, { it.harmonicPercent }, { p, v -> p.copy(harmonicPercent = v) }),
        back("peakPercent", T("Loudness"), PEAK, "%", 5, 60, 1, { it.peakPercent }, { p, v -> p.copy(peakPercent = v) }),

        page("startHz", T("Start pitch"), T("Pitch the note starts at."), "Hz", 150, 2000, 10, { it.startHz }, { p, v -> p.copy(startHz = v) }),
        page("glidePercent", T("Glide"), GLIDE, "%", -50, 50, 1, { it.glidePercent }, { p, v -> p.copy(glidePercent = v) }),
        page("lengthMs", T("Length"), LENGTH, "ms", 40, 400, 5, { it.lengthMs }, { p, v -> p.copy(lengthMs = v) }),
        page("attackMs", T("Attack"), ATTACK, "ms", 1, 60, 1, { it.attackMs }, { p, v -> p.copy(attackMs = v) }),
        page("decayMs", T("Decay"), DECAY, "ms", 5, 300, 5, { it.decayMs }, { p, v -> p.copy(decayMs = v) }),
        page("harmonicPercent", T("Harmonic"), HARMONIC, "%", 0, 100, 1, { it.harmonicPercent }, { p, v -> p.copy(harmonicPercent = v) }),
        page("peakPercent", T("Loudness"), PEAK, "%", 5, 60, 1, { it.peakPercent }, { p, v -> p.copy(peakPercent = v) }),
    )

    /** The numbers of [kind]'s sound. */
    fun fieldsOf(kind: XmbSoundKind): List<XmbTuneField> = fields.filter { it.kind == kind }

    /** The parameters whose stored numbers [read] gives (null: not set, so the default), each held to its range. */
    fun params(read: (XmbTuneField) -> Int?): XmbSoundParams =
        fields.fold(XmbSoundParams.DEFAULT) { p, f -> f.write(p, f.clamp(read(f) ?: f.default)) }

    /** The parameters as stored in the app's preferences. */
    fun load(context: Context): XmbSoundParams {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return params { f -> if (prefs.contains(f.key)) prefs.getInt(f.key, f.default) else null }
    }

    /**
     * One line per sound with all its numbers, e.g. `FOCUS pitchHz=1000 lengthMs=60 attackMs=4 decayMs=16 peakPercent=17`: what the
     * "Copy all values" row puts on the clipboard.
     */
    fun exportText(params: XmbSoundParams): String =
        XmbSoundKind.entries.joinToString("\n") { kind ->
            fieldsOf(kind).joinToString(" ", prefix = kind.name + " ") { f -> f.name + "=" + f.read(params) }
        }

    /** Keeps [value] (held to its range) as [field]'s number and has the sound rebuilt and played. */
    fun set(context: Context, field: XmbTuneField, value: Int) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putInt(field.key, field.clamp(value)).apply()
        XmbSounds.setParams(load(context))
        XmbSounds.preview(field.kind)
    }

    /** Takes [kind]'s numbers back to the defaults. */
    fun reset(context: Context, kind: XmbSoundKind) {
        val edit = PreferenceManager.getDefaultSharedPreferences(context).edit()
        fieldsOf(kind).forEach { edit.remove(it.key) }
        edit.apply()
        XmbSounds.setParams(load(context))
        XmbSounds.preview(kind)
    }

    /** Puts [exportText] of the current numbers on the clipboard and shows it. */
    fun copyAll(context: Context) {
        val text = exportText(load(context))
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("XMB sound values", text))
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }
}

package com.winlator.star.ui.deck.settings

import androidx.preference.PreferenceManager
import com.winlator.star.ui.deck.XmbSoundKind
import com.winlator.star.ui.deck.XmbSoundTuning
import com.winlator.star.ui.deck.XmbSounds
import com.winlator.star.ui.deck.XmbTuneField
import com.winlator.star.ui.deck.settings.SettingLevel.SIMPLE
import com.winlator.star.ui.simple.T

// TEMPORARY (see XmbSoundTuning.kt): the rows of Settings > Appearance, under the Sounds group, that tune the XMB interface sounds by
// ear. A "Copy all values" row first, then for each sound a slider per number and a Play / Reset row. They are XMB-only rows like
// AppSettingsRows': a category that is not in AppSettingsRegistry.categories, filed into the Appearance tab by the "app.appearance."
// prefix of their ids. A slider writes its preference and the sound plays with it; the Play, Reset and Copy rows are choices that keep
// no value (their row always reads as nothing picked) and run their action when one is tapped.

internal object XmbSoundTuningRows {

    const val CAT = "app.x.soundTuning"
    const val ID_COPY = "app.appearance.tune.copy"

    private fun groupOf(kind: XmbSoundKind): String = when (kind) {
        XmbSoundKind.FOCUS -> T("Tuning: focus tick (the highlight moves)")
        XmbSoundKind.SELECT -> T("Tuning: select chime (A, confirm)")
        XmbSoundKind.BACK -> T("Tuning: back note (B)")
        XmbSoundKind.PAGE -> T("Tuning: page glide (tabs and pages)")
    }

    private fun actionsId(kind: XmbSoundKind) = "app.appearance.tune." + kind.name.lowercase() + ".actions"

    /** One tuning row, switched off while Interface sounds is off (nothing would play) unless it is not [gated]. */
    private fun row(
        id: String, group: String, label: String, hint: String, tech: String?, control: Control, binding: AppBinding, gated: Boolean = true,
    ) = SettingDef(
        id = id, category = CAT, group = group, label = label, hint = hint, tech = tech, level = SIMPLE,
        control = { _, _ -> control }, app = binding,
        disabled = { _, values -> if (!gated || values(AppSettingsRows.ID_SOUNDS) == "1") null else T("Needs Interface sounds on.") },
    )

    private fun slider(f: XmbTuneField) = row(
        f.id, groupOf(f.kind), f.label, f.hint, f.key,
        Control.Slider(f.min.toFloat(), f.max.toFloat(), f.step.toFloat(), { f.show(it.toInt()) }, { it.toInt().toString() }),
        AppBinding(
            get = { c -> f.clamp(PreferenceManager.getDefaultSharedPreferences(c).getInt(f.key, f.default)).toString() },
            set = { c, v -> v.toIntOrNull()?.let { XmbSoundTuning.set(c, f, it) } },
        ),
    )

    private fun actions(kind: XmbSoundKind) = row(
        actionsId(kind), groupOf(kind), T("Try it"), T("Play the sound with the numbers above, or take them all back to the defaults."), null,
        Control.Segmented(listOf(Opt("play", T("Play")), Opt("reset", T("Reset sound to defaults")))),
        AppBinding(
            get = { _ -> "" },
            set = { c, v ->
                when (v) {
                    "play" -> XmbSounds.preview(kind)
                    "reset" -> XmbSoundTuning.reset(c, kind)
                    else -> Unit
                }
            },
        ),
    )

    fun defs(): List<SettingDef> {
        val copy = row(
            ID_COPY, T("Sound tuning (temporary)"), T("Sound values"),
            T("Tune the sounds below by ear (each change plays its sound), then copy every value to paste into a message."), null,
            Control.Segmented(listOf(Opt("copy", T("Copy all values")))),
            AppBinding(get = { _ -> "" }, set = { c, _ -> XmbSoundTuning.copyAll(c) }), gated = false,
        )
        return listOf(copy) + XmbSoundKind.entries.flatMap { kind ->
            XmbSoundTuning.fieldsOf(kind).map { slider(it) } + actions(kind)
        }
    }
}

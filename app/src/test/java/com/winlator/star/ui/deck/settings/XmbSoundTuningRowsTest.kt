package com.winlator.star.ui.deck.settings

import com.winlator.star.ui.deck.XmbSoundKind
import com.winlator.star.ui.deck.XmbSoundTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The rows of the temporary sound tuning (XmbSoundTuningRows.kt): each has one home, on the Appearance tab, and none of them is a
 * row the Lab or the plain Deck would list.
 */
class XmbSoundTuningRowsTest {

    private val rowIds = listOf(XmbSoundTuningRows.ID_COPY) + XmbSoundKind.entries.flatMap { kind ->
        XmbSoundTuning.fieldsOf(kind).map { it.id } + ("app.appearance.tune." + kind.name.lowercase() + ".actions")
    }

    @Test
    fun everyTuningRowIsFiledOnTheAppearanceTab() {
        for (id in rowIds) assertEquals(id, EditorTabs.APP_APPEARANCE, AppSettingsLayout.tabFor(id))
    }

    @Test
    fun theRowsAreOneCopyRowAndPerSoundItsSlidersAndAPlayRow() {
        val defs = runCatching { XmbSoundTuningRows.defs() }
        assumeTrue("the rows cannot be built on the JVM: ${defs.exceptionOrNull()}", defs.isSuccess)
        val rows = defs.getOrThrow()
        assertEquals(rowIds, rows.map { it.id })
        assertEquals(rowIds.size, rows.map { it.id }.toSet().size)
        assertEquals("the copy row comes first", XmbSoundTuningRows.ID_COPY, rows.first().id)
        assertTrue(rows.all { it.category == XmbSoundTuningRows.CAT && it.app != null })
    }

    @Test
    fun theTuningRowsSitOutsideTheCategoriesTheOtherStylesWalk() {
        val labCategories = runCatching { AppSettingsRegistry.categories.map { it.id }.toSet() }
        val tuning = runCatching { XmbSoundTuningRows.defs() }
        val legacy = runCatching { AppSettingsRegistry.legacyDefs }
        assumeTrue("the app registry cannot be built on the JVM", labCategories.isSuccess && tuning.isSuccess && legacy.isSuccess)
        assertTrue(XmbSoundTuningRows.CAT !in labCategories.getOrThrow())
        val ids = tuning.getOrThrow().map { it.id }.toSet()
        assertEquals(emptyList<String>(), legacy.getOrThrow().filter { it.id in ids }.map { it.id })
    }
}

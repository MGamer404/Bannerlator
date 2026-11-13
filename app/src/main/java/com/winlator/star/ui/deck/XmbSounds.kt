package com.winlator.star.ui.deck

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.preference.PreferenceManager
import com.winlator.star.XServerDisplayActivity
import com.winlator.star.ui.findActivity
import java.io.File

// The soft interface sounds of the XMB: a tick when the highlight moves, a chime on select, a lower note on back, a swell on a page
// change. XmbSoundSynth makes them; this plays them.
//
//  * Only an XMB window that holds a highlight host plays anything: the host attaches its lifecycle ([attach]), so the Classic, plain
//    Deck and Lab looks have nothing attached and stay silent, and so does a game's own window (the in-game menu).
//  * Sound is played only while an attached window is resumed, so nothing plays while a game, or any other app, is in front.
//  * SoundPool plays them as UI sounds (they follow the system's touch sounds and never duck or interrupt a game's audio). It is built
//    on the first sound and released when the last host leaves or the app stops.
//  * Anything that goes wrong in here (no audio, no cache folder) turns the sounds off for the rest of the process: they are never worth a crash.
//
// Everything runs on the main thread.

/**
 * Which sound may play when ([nowMs] is an uptime), so that one action is one sound: a fast scroll does not machine-gun, and a key
 * press that also moves the highlight or changes the page plays its own sound only.
 */
internal class XmbSoundLimiter {
    private var lastFocusAt = NEVER
    private var lastActionAt = NEVER

    /** Whether [kind] may play now; if it may, this counts as played. */
    fun allow(kind: XmbSoundKind, nowMs: Long): Boolean {
        val quiet = nowMs - lastActionAt >= ACTION_GAP_MS
        if (kind != XmbSoundKind.FOCUS) {
            if (quiet) lastActionAt = nowMs
            return quiet
        }
        // The move that goes with a select, back or page sound is part of it.
        if (!quiet || nowMs - lastFocusAt < FOCUS_GAP_MS) return false
        lastFocusAt = nowMs
        return true
    }

    companion object {
        /** The sound of the previous select / back / page covers this long: a second one inside it (a key and the page change it causes) is dropped. */
        const val ACTION_GAP_MS = 150L

        /** A tick is about this long: the next one waits for it. */
        const val FOCUS_GAP_MS = 45L

        private const val NEVER = Long.MIN_VALUE / 2
    }
}

internal object XmbSounds {
    /** The Interface sounds switch and its volume (0 to 100), in the app's default preferences. */
    const val PREF_ENABLED = "xmb_sounds"
    const val PREF_VOLUME = "xmb_sound_volume"
    const val DEFAULT_ENABLED = true
    const val DEFAULT_VOLUME = 60

    private const val TAG = "XmbSounds"
    private const val CACHE_DIR = "xmb_sounds"
    private const val MAX_STREAMS = 4

    // The hosts that are composed now (one entry each; a window can have several), and the app context to read the settings with.
    private val attached = ArrayList<Lifecycle>()
    private var appContext: Context? = null

    private val wavs: List<ByteArray> by lazy {
        XmbSoundKind.entries.map { XmbSoundSynth.wav(XmbSoundSynth.render(it, XmbSoundSynth.SAMPLE_RATE), XmbSoundSynth.SAMPLE_RATE) }
    }
    private var pool: SoundPool? = null
    private val soundIds = IntArray(XmbSoundKind.entries.size)
    private var failed = false
    private val limiter = XmbSoundLimiter()

    private val stopObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_STOP && attached.none { it.currentState.isAtLeast(Lifecycle.State.STARTED) }) release()
    }

    /** A highlight host of an XMB window came into composition. A game's own window is not attached: it stays silent. */
    fun attach(context: Context, lifecycle: Lifecycle) {
        if (context.findActivity() is XServerDisplayActivity) return
        appContext = context.applicationContext
        attached.add(lifecycle)
        lifecycle.addObserver(stopObserver)
    }

    /** The host that attached with [lifecycle] left composition. */
    fun detach(lifecycle: Lifecycle) {
        attached.remove(lifecycle)
        if (lifecycle !in attached) lifecycle.removeObserver(stopObserver)
        if (attached.isEmpty()) release()
    }

    /** Plays [kind] now, unless the sounds are off, a rate limit holds it back, or no XMB window is in front. */
    fun play(kind: XmbSoundKind) {
        if (failed || attached.none { it.currentState.isAtLeast(Lifecycle.State.RESUMED) }) return
        val context = appContext ?: return
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            if (!prefs.getBoolean(PREF_ENABLED, DEFAULT_ENABLED)) return
            val volume = prefs.getInt(PREF_VOLUME, DEFAULT_VOLUME).coerceIn(0, 100) / 100f
            if (volume <= 0f || !limiter.allow(kind, SystemClock.uptimeMillis())) return
            val sp = pool ?: createPool(context)
            // A sound that has not finished loading (the very first one) just does not play.
            sp.play(soundIds[kind.ordinal], volume, volume, 1, 0, 1f)
        } catch (t: Throwable) {
            Log.w(TAG, "interface sounds turned off", t)
            failed = true
            release()
        }
    }

    private fun createPool(context: Context): SoundPool {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val sp = SoundPool.Builder().setMaxStreams(MAX_STREAMS).setAudioAttributes(attrs).build()
        pool = sp
        val dir = File(context.cacheDir, CACHE_DIR)
        dir.mkdirs()
        for (kind in XmbSoundKind.entries) {
            val file = File(dir, kind.name.lowercase() + ".wav")
            file.writeBytes(wavs[kind.ordinal])
            soundIds[kind.ordinal] = sp.load(file.path, 1)
        }
        return sp
    }

    private fun release() {
        pool?.release()
        pool = null
    }
}

/**
 * Plays [kind] each time [keys] change, but not for the values the screen starts with. For a cursor or a page the XMB keeps in its own
 * state, which the highlight host never sees.
 */
@Composable
internal fun XmbSoundOnChange(kind: XmbSoundKind, vararg keys: Any?) {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(*keys) {
        if (started) {
            XmbSounds.play(kind)
        } else {
            started = true
        }
    }
}

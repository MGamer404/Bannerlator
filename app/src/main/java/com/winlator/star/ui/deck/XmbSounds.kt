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

// The soft interface sounds of the XMB: a tick when the highlight moves, a chime on select, a lower note on back, a soft glide on a page
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
 * press that also moves the highlight or changes the page plays its own sound only. The windows follow the lengths of the sounds
 * ([XmbSoundParams.focusGapMs], [XmbSoundParams.actionGapMs]).
 */
internal class XmbSoundLimiter(
    /** A tick holds the next tick back this long (about its own length). */
    var focusGapMs: Long = FOCUS_GAP_MS,
    /** A select, back or page sound holds every next one back this long: a second one inside it (a key and the page change it causes) is dropped. */
    var actionGapMs: Long = ACTION_GAP_MS,
) {
    private var lastFocusAt = NEVER
    private var lastActionAt = NEVER

    /** Whether [kind] may play now; if it may, this counts as played. */
    fun allow(kind: XmbSoundKind, nowMs: Long): Boolean {
        val quiet = nowMs - lastActionAt >= actionGapMs
        if (kind != XmbSoundKind.FOCUS) {
            if (quiet) lastActionAt = nowMs
            return quiet
        }
        // The move that goes with a select, back or page sound is part of it.
        if (!quiet || nowMs - lastFocusAt < focusGapMs) return false
        lastFocusAt = nowMs
        return true
    }

    companion object {
        /** The windows of the shipped sounds (see [XmbSoundParams.DEFAULT]). */
        const val ACTION_GAP_MS = 150L
        const val FOCUS_GAP_MS = 60L

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

    // The numbers the sounds are made from and, per sound, its WAV bytes (made on first use), its SoundPool id and whether that id has
    // finished loading. A sound asked for by [preview] before it has loaded plays as soon as it has.
    private var params = XmbSoundParams.DEFAULT
    private val wavs = arrayOfNulls<ByteArray>(XmbSoundKind.entries.size)
    private val soundIds = IntArray(XmbSoundKind.entries.size)
    private val ready = BooleanArray(XmbSoundKind.entries.size)
    private val previewWhenReady = BooleanArray(XmbSoundKind.entries.size)
    private var pool: SoundPool? = null
    private var failed = false
    private val limiter = XmbSoundLimiter()

    private val stopObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_STOP && attached.none { it.currentState.isAtLeast(Lifecycle.State.STARTED) }) release()
    }

    /** A highlight host of an XMB window came into composition. A game's own window is not attached: it stays silent. */
    fun attach(context: Context, lifecycle: Lifecycle) {
        if (context.findActivity() is XServerDisplayActivity) return
        if (appContext == null) {
            appContext = context.applicationContext
            setParams(XmbSoundTuning.load(context))
        }
        attached.add(lifecycle)
        lifecycle.addObserver(stopObserver)
    }

    /** The host that attached with [lifecycle] left composition. */
    fun detach(lifecycle: Lifecycle) {
        attached.remove(lifecycle)
        if (lifecycle !in attached) lifecycle.removeObserver(stopObserver)
        if (attached.isEmpty()) release()
    }

    /** The sounds are made from [next] from now on: a sound whose numbers changed is rebuilt, and the rate limits follow the lengths. */
    fun setParams(next: XmbSoundParams) {
        val before = params
        params = next
        limiter.focusGapMs = next.focusGapMs
        limiter.actionGapMs = next.actionGapMs
        for (kind in XmbSoundKind.entries) if (before.of(kind) != next.of(kind)) rebuild(kind)
    }

    /** Plays [kind] now, unless the sounds are off, a rate limit holds it back, or no XMB window is in front. */
    fun play(kind: XmbSoundKind) = start(kind, preview = false)

    /** Plays [kind] with its current numbers whatever the rate limits say, waiting for it to load if it must. */
    fun preview(kind: XmbSoundKind) = start(kind, preview = true)

    private fun start(kind: XmbSoundKind, preview: Boolean) {
        if (failed || attached.none { it.currentState.isAtLeast(Lifecycle.State.RESUMED) }) return
        val context = appContext ?: return
        try {
            val volume = volumeOf(context)
            if (volume <= 0f || (!preview && !limiter.allow(kind, SystemClock.uptimeMillis()))) return
            val sp = pool ?: createPool(context)
            // A sound that has not finished loading (the very first one) just does not play, unless it is a preview: that waits.
            if (ready[kind.ordinal]) {
                sp.play(soundIds[kind.ordinal], volume, volume, 1, 0, 1f)
            } else if (preview) {
                previewWhenReady[kind.ordinal] = true
            }
        } catch (t: Throwable) {
            turnOff(t)
        }
    }

    /** The playback volume (0 to 1) from the settings; 0 when the sounds are off. */
    private fun volumeOf(context: Context): Float {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.getBoolean(PREF_ENABLED, DEFAULT_ENABLED)) return 0f
        return prefs.getInt(PREF_VOLUME, DEFAULT_VOLUME).coerceIn(0, 100) / 100f
    }

    private fun turnOff(t: Throwable) {
        Log.w(TAG, "interface sounds turned off", t)
        failed = true
        release()
    }

    private fun createPool(context: Context): SoundPool {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val sp = SoundPool.Builder().setMaxStreams(MAX_STREAMS).setAudioAttributes(attrs).build()
        pool = sp
        sp.setOnLoadCompleteListener { _, sampleId, status -> onLoaded(sampleId, status == 0) }
        for (kind in XmbSoundKind.entries) loadSound(sp, context, kind)
        return sp
    }

    /** Writes [kind]'s WAV to the cache folder and hands it to [sp]. */
    private fun loadSound(sp: SoundPool, context: Context, kind: XmbSoundKind) {
        val dir = File(context.cacheDir, CACHE_DIR)
        dir.mkdirs()
        val file = File(dir, kind.name.lowercase() + ".wav")
        val wav = wavs[kind.ordinal]
            ?: XmbSoundSynth.wav(XmbSoundSynth.render(kind, XmbSoundSynth.SAMPLE_RATE, params), XmbSoundSynth.SAMPLE_RATE)
                .also { wavs[kind.ordinal] = it }
        file.writeBytes(wav)
        ready[kind.ordinal] = false
        soundIds[kind.ordinal] = sp.load(file.path, 1)
    }

    /** [kind]'s numbers changed: forget its WAV and, if the pool is up, load the new one in place of the old. */
    private fun rebuild(kind: XmbSoundKind) {
        wavs[kind.ordinal] = null
        val sp = pool ?: return
        val context = appContext ?: return
        try {
            sp.unload(soundIds[kind.ordinal])
            loadSound(sp, context, kind)
        } catch (t: Throwable) {
            turnOff(t)
        }
    }

    private fun onLoaded(sampleId: Int, ok: Boolean) {
        val i = soundIds.indexOf(sampleId)
        val sp = pool ?: return
        val context = appContext ?: return
        if (i < 0) return
        ready[i] = ok
        if (ok && previewWhenReady[i]) {
            previewWhenReady[i] = false
            val volume = volumeOf(context)
            if (volume > 0f) sp.play(sampleId, volume, volume, 1, 0, 1f)
        }
    }

    private fun release() {
        pool?.release()
        pool = null
        ready.fill(false)
        previewWhenReady.fill(false)
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

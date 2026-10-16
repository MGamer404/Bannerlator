package com.winlator.star.inputcontrols;

import android.util.Log;

import java.io.File;
import java.io.IOException;

/**
 * App side of the BANNERIN controller-input transport: publishes the complete state of one
 * player slot (0..3) for the guest evdev layers (libfakeinput.so) to read.
 *
 * <p>Each slot is one fixed-size shared file, {@code <imagefs>/dev/bannerin/pad<slot>}, holding
 * the current pad state (never an append-only stream, so it can never grow or lose framing).
 * Readers diff it against what they last delivered: button edges are replayed in order (a press
 * and a release between two guest reads are both delivered), sticks are coalesced to the latest
 * value. The native half lives in libbannerin.so; its per-slot state is process-wide and outlives
 * writer objects, so a reconnect or a new session continues from the same state.
 *
 * <p>The public surface mirrors the previous writer class (same names and meaning), so callers
 * can switch by renaming the type:
 * <ul>
 *   <li>{@link #writeGamepadState} takes absolute state; an unchanged state publishes nothing and
 *       wakes nobody. It is allocation-free, never blocks and does no file I/O.</li>
 *   <li>{@link #softRelease} / {@link #reset} neutralise without an unplug; {@link #destroy} is an
 *       unplug (every reader attached to the slot sees a removal) and deletes the discovery node.</li>
 *   <li>A publish that cannot reach the backing keeps the state; the next successful publish (or
 *       the backing being recreated, which the native side retries at most once a second) leaves
 *       the guest with the full current state. {@link #lastPublishFailed()} reports it.</li>
 * </ul>
 * Instances are thread-safe; the static members are too.
 */
public final class BannerinWriter {
    private static final String TAG = "BannerinWriter";

    public static final int MAX_SLOTS = 4;
    /** Sibling of {@code dev/input} that holds the slot files. */
    public static final String BACKING_DIR_NAME = "bannerin";

    // Indices for getSlotStat (match bannerin_core::writer::STAT_*).
    public static final int STAT_PUBLISHES = 0;
    public static final int STAT_EDGES = 1;
    public static final int STAT_WAKE_CALLS = 2;
    public static final int STAT_WOKEN = 3;
    public static final int STAT_UNPLUGS = 4;
    public static final int STAT_READER_ATTACHES = 5;
    public static final int STAT_READER_REATTACHES = 6;
    public static final int STAT_READER_TAPS = 7;
    public static final int STAT_READER_COALESCED = 8;
    public static final int STAT_READER_RESYNCS = 9;
    public static final int STAT_READER_FRAMES = 10;
    public static final int STAT_READER_EVENTS = 11;
    public static final int STAT_READER_REMOVALS = 12;
    public static final int STAT_INSTANCE = 13;
    public static final int STAT_FAILED_PUBLISHES = 14;
    public static final int STAT_HAS_BACKING = 15;
    /** Stick-only frames readers held back (paced to BANNERIN_AXIS_HZ) before delivering them. */
    public static final int STAT_READER_PACED_FRAMES = 16;
    /** Publishes readers merged into a later frame instead of delivering one each. */
    public static final int STAT_READER_MERGED_AXIS = 17;
    /** Auto pacing: the stick-frame rate (Hz) a reader last derived from the game's frame rate. */
    public static final int STAT_READER_AXIS_HZ = 18;
    /** Auto pacing: times a reader lost a fresh frame feed and fell back to the fixed 60 Hz. */
    public static final int STAT_READER_AUTO_FALLBACKS = 19;
    /** The game frame interval the frame feed currently estimates (ns, 0 = none or stale). */
    public static final int STAT_FRAME_INTERVAL_NS = 20;
    /** Game frames reported to the feed so far (this app process). */
    public static final int STAT_GAME_FRAMES = 21;
    private static final int STAT_COUNT = 22;

    // Each slot's counters as they stood when releaseAllRingSlots() retired its file, for the
    // session-end diagnostics that may run after the release.
    private static final long[][] RELEASED_STATS = new long[MAX_SLOTS][STAT_COUNT];

    private static final boolean NATIVE_OK;

    static {
        boolean ok = false;
        try {
            System.loadLibrary("bannerin");
            ok = true;
        } catch (Throwable t) {
            Log.e(TAG, "libbannerin.so could not be loaded; controller input is unavailable", t);
        }
        NATIVE_OK = ok;
    }

    private static native boolean nativePrepare(String backingDir, int slot);
    private static native void nativeRelease(int slot);
    private static native boolean nativeOpen(int slot, String backingDir);
    private static native boolean nativePublish(int slot, int buttons, float lx, float ly, float rx,
                                                float ry, float lt, float rt, int dpad);
    private static native boolean nativeSetOverlay(int slot, int bits);
    private static native boolean nativeReset(int slot);
    private static native boolean nativeUnplug(int slot);
    private static native long nativeStat(int slot, int which);
    private static native void nativeGameFrame();

    // ------------------------------------------------------------------------------------------
    // static (process-wide)

    /** {@code <imagefs>/dev/bannerin} for {@code <imagefs>/dev/input}. */
    public static File backingDir(File fakeInputDir) {
        File parent = fakeInputDir.getAbsoluteFile().getParentFile();
        return new File(parent != null ? parent : fakeInputDir, BACKING_DIR_NAME);
    }

    /** The slot file a guest attaches to. */
    public static File backingFile(File fakeInputDir, int slot) {
        return new File(backingDir(fakeInputDir), "pad" + slot);
    }

    private static boolean validSlot(int slot) {
        return slot >= 0 && slot < MAX_SLOTS;
    }

    /** Bit N set = slot N's backing exists and is live. */
    private static synchronized int prepareSlots(File fakeInputDir, int slotCount) {
        if (!NATIVE_OK || fakeInputDir == null) return 0;
        File dir = backingDir(fakeInputDir);
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            Log.w(TAG, "cannot create " + dir);
            return 0;
        }
        int count = Math.max(0, Math.min(slotCount, MAX_SLOTS));
        String path = dir.getAbsolutePath();
        int mask = 0;
        for (int slot = 0; slot < count; slot++) {
            if (nativePrepare(path, slot)) mask |= 1 << slot;
            else Log.w(TAG, "could not prepare " + new File(dir, "pad" + slot));
        }
        return mask;
    }

    /**
     * Make sure the backing of slots {@code 0..slotCount-1} (clamped to 4) exists and is live,
     * so readers can attach before any pad connects. Idempotent.
     */
    public static void prepareRingSlots(File fakeInputDir, int slotCount) {
        prepareSlots(fakeInputDir, slotCount);
    }

    /**
     * Prepare all 4 slots and return the {@code FAKE_EVDEV_MEMFD_PATHS} value
     * ({@code <slot>=<absolute path>;...}), or null if nothing could be prepared.
     */
    public static String getRingEnv(File fakeInputDir) {
        int mask = prepareSlots(fakeInputDir, MAX_SLOTS);
        if (mask == 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int slot = 0; slot < MAX_SLOTS; slot++) {
            if ((mask & (1 << slot)) == 0) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append(slot).append('=').append(backingFile(fakeInputDir, slot).getAbsolutePath());
        }
        return sb.toString();
    }

    /**
     * Retire and delete every slot's backing (session end). Readers still attached show a neutral
     * pad and re-attach by themselves when the next session prepares new files; this is not an
     * unplug. The writers' state is kept.
     */
    public static synchronized void releaseAllRingSlots() {
        if (!NATIVE_OK) return;
        for (int slot = 0; slot < MAX_SLOTS; slot++) {
            if (nativeStat(slot, STAT_HAS_BACKING) != 0) {
                for (int which = 0; which < STAT_COUNT; which++) {
                    RELEASED_STATS[slot][which] = nativeStat(slot, which);
                }
            }
            nativeRelease(slot);
        }
    }

    /**
     * The game presented a frame. Call it for every frame the game's own window presents (not the
     * desktop shell), whether or not any HUD is shown: readers in auto pacing (the default) send
     * stick-only updates at about 90 % of the game's frame rate, so a game that takes one input
     * report per rendered frame never falls behind. Cheap enough for the present threads: one JNI
     * call into a wait-free estimator plus a few atomic stores; no allocation, no lock.
     */
    public static void onGameFrame() {
        if (NATIVE_OK) nativeGameFrame();
    }

    /** A counter for diagnostics (see the STAT_* constants), or 0. */
    public static long getSlotStat(int slot, int which) {
        if (!NATIVE_OK || !validSlot(slot)) return 0;
        return nativeStat(slot, which);
    }

    /**
     * {@link #getSlotStat} of the slot's live file, or, once {@link #releaseAllRingSlots()} has
     * retired it at session end, the value it had then (0 if it never had a file).
     */
    public static synchronized long getSessionStat(int slot, int which) {
        if (!NATIVE_OK || !validSlot(slot) || which < 0 || which >= STAT_COUNT) return 0;
        if (nativeStat(slot, STAT_HAS_BACKING) != 0) return nativeStat(slot, which);
        return RELEASED_STATS[slot][which];
    }

    // ------------------------------------------------------------------------------------------
    // one writer per player slot

    private final String fakeInputPath;
    private final int slot;
    private volatile boolean open;
    private boolean destroyed;
    private volatile boolean lastPublishFailed;

    /** Writer for player slot {@code slot} (0..3). {@code fakeInputPath} is the dev/input dir. */
    public BannerinWriter(String fakeInputPath, int slot) {
        this.fakeInputPath = fakeInputPath;
        this.slot = slot;
    }

    private File node() {
        return new File(fakeInputPath, "event" + slot);
    }

    /**
     * "This slot now has a controller": create the discovery node {@code event<slot>} and make
     * the slot live for readers. Idempotent. False after {@link #destroy()} or on failure.
     */
    public synchronized boolean open() {
        if (destroyed || !NATIVE_OK || !validSlot(slot) || fakeInputPath == null) return false;
        if (open) return true;
        File node = node();
        try {
            if (!node.exists() && !node.createNewFile() && !node.exists()) {
                Log.w(TAG, "could not create " + node);
                return false;
            }
        } catch (IOException e) {
            Log.w(TAG, "could not create " + node, e);
            return false;
        }
        File dir = backingDir(new File(fakeInputPath));
        if (!dir.isDirectory()) dir.mkdirs();
        if (!nativeOpen(slot, dir.getAbsolutePath())) return false;
        open = true;
        return true;
    }

    private void noteResult(boolean ok) {
        if (!ok && !lastPublishFailed) {
            Log.w(TAG, "slot " + slot + ": publish could not reach the backing; state kept, retrying");
        }
        lastPublishFailed = !ok;
    }

    /**
     * "This is the slot's complete current state." Absolute, not a delta: only what changed since
     * the last publish reaches the guest, and an unchanged state produces nothing. No reference to
     * {@code state} is kept.
     */
    public synchronized void writeGamepadState(GamepadState state) {
        if (state == null || (!open && !open())) return;
        int dpad = (state.dpad[0] ? 1 : 0) | (state.dpad[1] ? 2 : 0)
                | (state.dpad[2] ? 4 : 0) | (state.dpad[3] ? 8 : 0);
        noteResult(nativePublish(slot, state.buttons, state.thumbLX, state.thumbLY,
                state.thumbRX, state.thumbRY, state.triggerL, state.triggerR, dpad));
    }

    /**
     * OR the app's own Guide (BTN_MODE) and/or A over whatever the pad holds on this slot and
     * publish now. Clearing never releases a button the user is physically holding; every edge
     * reaches the guest, in order.
     */
    public synchronized void setSystemButtons(boolean guide, boolean a) {
        if (!open && !open()) return;
        noteResult(nativeSetOverlay(slot, (guide ? 1 : 0) | (a ? 2 : 0)));
    }

    /** Neutralise: every button released, every axis 0, published at once. Stays connected. */
    public synchronized void reset() {
        if (!open && !open()) return;
        noteResult(nativeReset(slot));
    }

    /** Neutralise, then mark this writer closed. Not an unplug: readers stay attached and valid. */
    public synchronized void softRelease() {
        reset();
        close();
    }

    /**
     * "This controller was unplugged": neutralise, make every reader attached to the slot see a
     * removal, delete the discovery node. The writer is dead afterwards ({@link #open()} returns
     * false); a reconnect uses a new writer.
     */
    public synchronized void destroy() {
        if (destroyed) return;
        destroyed = true;
        open = false;
        if (NATIVE_OK && validSlot(slot)) nativeUnplug(slot);
        if (fakeInputPath != null && validSlot(slot)) {
            File node = node();
            if (node.exists() && !node.delete()) Log.w(TAG, "could not delete " + node);
        }
    }

    /** Marks the writer closed. No guest-visible effect. */
    public synchronized void close() {
        open = false;
    }

    public boolean isOpen() {
        return open;
    }

    /** True if the most recent publish could not reach the backing (it is retried). */
    public boolean lastPublishFailed() {
        return lastPublishFailed;
    }

    public int getSlot() {
        return slot;
    }
}

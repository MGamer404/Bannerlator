package com.winlator.star.store.xbox

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Cloud saves on session exit. Container saves (XGameSave): the game's own runtime uploads each save in the
 * background as it is written and marks its index "dirty 0" once the cloud has it; quitting right after a save cut
 * that upload off, so the shutdown screen first waits, game still running, until this session's saves are uploaded
 * (bounded). File saves (XGameSaveFiles): Xodus Nexus' xgameruntime starts a helper
 * (`rundll32.exe xgameruntime.dll,XodusSaveFilesMonitor`) that uploads the game's saves once the game process
 * has exited, through our bridge and account service. Bannerlator's teardown stops every Wine process at once,
 * which would kill that helper before it uploads; so for an Xbox session with cloud saves on, the shutdown
 * screen first ends only the game, then waits for the helper to finish (bounded), and only then tears down.
 */
object XboxSaveSync {
    private const val HELPER = "XodusSaveFilesMonitor"

    private fun commandLines(): List<Pair<Int, String>> =
        File("/proc").listFiles().orEmpty().mapNotNull { dir ->
            val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
            if (pid == android.os.Process.myPid()) return@mapNotNull null
            val cmd = runCatching { File(dir, "cmdline").readBytes() }.getOrNull() ?: return@mapNotNull null
            if (cmd.isEmpty()) null else pid to String(cmd).replace('\u0000', ' ')
        }

    /**
     * Container saves first: waits up to [containerTimeoutMs] while an index under [saveRoot] (XodusGameSave) changed
     * since [since] is still dirty. Then ends the game ([exeName]) and waits up to [timeoutMs] for the save helper to
     * finish its final upload.
     */
    @JvmStatic fun finish(app: Context, exeName: String, timeoutMs: Long, saveRoot: File?, since: Long, containerTimeoutMs: Long) {
        if (saveRoot != null) waitForContainerUploads(app, saveRoot, since, containerTimeoutMs)
        val helpers = commandLines().filter { it.second.contains(HELPER) }.map { it.first }
        if (helpers.isEmpty()) {
            XboxDiag.log(app, "save sync on exit: no save helper running")
            return
        }
        val games = commandLines().filter { (_, cmd) -> !cmd.contains(HELPER) && cmd.contains(exeName, ignoreCase = true) && !cmd.contains("winhandler", true) && !cmd.contains("explorer", true) }
        games.forEach { (pid, _) -> runCatching { Os.kill(pid, OsConstants.SIGKILL) } }
        val start = System.currentTimeMillis()
        while (helpers.any { File("/proc/$it").exists() } && System.currentTimeMillis() - start < timeoutMs) Thread.sleep(250)
        val seconds = (System.currentTimeMillis() - start) / 1000.0
        if (helpers.any { File("/proc/$it").exists() })
            XboxDiag.log(app, "save sync on exit: helper still running after ${"%.1f".format(seconds)} s; local saves upload at the next launch")
        else XboxDiag.log(app, "save sync on exit: helper finished in ${"%.1f".format(seconds)} s (ended ${games.size} game process(es))")
    }

    /** Index files (<xuid>/<scid>/<container>/index) changed since [since] that still say "dirty 1". */
    private fun dirtyContainers(saveRoot: File, since: Long): List<File> =
        saveRoot.listFiles().orEmpty().flatMap { it.listFiles().orEmpty().toList() }
            .flatMap { it.listFiles().orEmpty().toList() }
            .map { File(it, "index") }
            .filter { it.isFile && it.lastModified() >= since && runCatching { it.readLines() }.getOrDefault(emptyList()).any { l -> l.trim() == "dirty 1" } }

    private fun waitForContainerUploads(app: Context, saveRoot: File, since: Long, timeoutMs: Long) {
        val dirty = dirtyContainers(saveRoot, since)
        if (dirty.isEmpty()) return
        val start = System.currentTimeMillis()
        var left = dirty
        while (left.isNotEmpty() && System.currentTimeMillis() - start < timeoutMs) {
            Thread.sleep(250)
            left = dirtyContainers(saveRoot, since)
        }
        val seconds = "%.1f".format((System.currentTimeMillis() - start) / 1000.0)
        if (left.isEmpty()) XboxDiag.log(app, "save sync on exit: ${dirty.size} save(s) uploaded in $seconds s")
        else XboxDiag.log(app, "save sync on exit: ${left.size} save(s) not uploaded after $seconds s; they upload at the next launch")
    }
}

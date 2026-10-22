package com.winlator.star.store.xbox

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Xbox game launches (adapted from DroidDeck's XboxRuntime + droiddeck-xbox, GPL-3.0). DroidDeck starts
 * the game through its Linux runtime; here the game runs in an ordinary Bannerlator Wine container and the
 * app does the host side:
 *  - the account service (xodus-service) behind a Unix socket, one per signed-in account;
 *  - a licensed launch view: `xodus-cli run <folder> droiddeck:prepare` materialises the encrypted images
 *    (exe and DLLs) in a private directory inside imagefs, so the Wine session can open them, and prints
 *    the executable to start; that child stays alive for the session and removes the view on exit;
 *  - a loopback bridge (XboxBridge) the game's Xbox runtime modules use to reach the service;
 *  - a lease per launch, renewed while the session runs, released when it ends.
 */
object XboxRuntime {
    /** What the Wine session needs to start an Xbox game, and the cleanup when it ends. */
    class Launch internal constructor(
        val productId: String,
        /** The materialised executable (a Unix path inside imagefs). */
        val exe: File,
        /** Environment for the game process (bridge port/key, service socket, save sync, PlayFab token). */
        val env: Map<String, String>,
        private val token: String,
        private val bridge: XboxBridge,
    ) : Closeable {
        @Volatile private var closed = false
        override fun close() {
            if (closed) return
            closed = true
            runCatching { bridge.close() }
            released(token)
            XboxDiag.log(null, "launch released: $productId")
        }
    }

    private val state = Any()
    private val startup = Any()
    private var child: Xodus.Child? = null
    private var owner: String? = null
    private var serviceSocket: File? = null
    private var generation = 0L
    private val views = XboxLaunchViews()
    private val launches = XboxLaunchLeases { SystemClock.uptimeMillis() }
    @Volatile private var session: String? = null

    fun isActive(id: String): Boolean = launches.active(id)
    private fun released(token: String) { runCatching { launches.release(token)?.close() } }

    /**
     * Prepares an installed game for a session. [imagefsRoot] is the session's imagefs root; the launch view
     * goes under its tmp/xbox-views. Blocks up to ~3 minutes (service start + licensed preparation).
     */
    fun prepare(app: Context, id: String, sessionId: String, imagefsRoot: File, cloudSaves: Boolean): Launch {
        val game = XboxInstallState.get(app, id) ?: error("This Xbox game is not installed.")
        check(File(game.folder, game.exe).isFile) { "This Xbox game's files are missing. Reinstall it." }
        session = sessionId
        val account = XboxAccountStore.signedInAs(app) ?: error("Sign in to Xbox before playing.")
        val (token, current, credentials) = XboxCredentials.lifecycle(app) { epoch ->
            synchronized(state) { Triple(launches.begin(id, account, sessionId), generation, epoch) }
        }
        XboxDiag.log(app, "launch prepare: $id")
        Thread({
            while (isActive(id)) {
                Thread.sleep(10_000)
                launches.renew(token, id, XboxAccountStore.signedInAs(app), session)
                launches.stale(XboxAccountStore.signedInAs(app), session, false).forEach { runCatching { it.close() } }
            }
        }, "xbox-launch-expiry").apply { isDaemon = true; start() }
        try {
            val socket = service(app, account, current, credentials)
            check(synchronized(state) { generation == current }) { "Xbox launch was cancelled" }
            val market = game.market.takeIf { it.matches(Regex("[A-Z]{2}")) } ?: Xodus.market()
            val directory = views.create(File(imagefsRoot, "tmp"))
            val process = try {
                Xodus.start(app, listOf("run", game.folder, "droiddeck:prepare", "--exe", game.exe,
                    "--materialize-chain", "--market", market), launchDirectory = directory, credentialGeneration = credentials)
            } catch (e: Exception) { XboxLaunchViews.remove(directory); throw e }
            if (!launches.attach(token, process)) { process.close(); error("Xbox launch was cancelled") }
            Xodus.diagnostics(app, process)
            val result = FutureTask {
                val input = process.process.inputStream.buffered()
                var line: String
                do { line = XboxCredentials.readLine(input, 131_072) } while (!line.startsWith("DROIDDECK_LAUNCH_V1 "))
                JSONObject(line.removePrefix("DROIDDECK_LAUNCH_V1 "))
            }
            Thread(result, "xbox-launch-view").start()
            val view = try { result.get(150, TimeUnit.SECONDS) } catch (e: Exception) {
                error("The Xbox game could not be prepared (license or files). Check your sign-in and try again.")
            }
            check(launches.renew(token, id, account, sessionId) && synchronized(state) { generation == current }) { "Xbox launch was cancelled" }
            val exe = File(view.getString("exe"))
            check(exe.isFile) { "The Xbox launch view has no executable." }
            stageModules(app, exe.parentFile!!)
            val bridge = XboxBridge(socket.path)
            val env = LinkedHashMap<String, String>()
            env["XODUS_SOCKET"] = socket.path
            env["XODUS_BRIDGE_PORT"] = bridge.port.toString()
            env["XODUS_BRIDGE_KEY"] = bridge.key
            env["XODUS_LOG"] = "off"; env["RUST_LOG"] = "off"
            // Cloud saves are off unless turned on: the backend's downloads were seen to arrive corrupted, and
            // its uploads can overwrite a real account's cloud copies.
            env["XODUS_SAVE_SYNC"] = if (cloudSaves) "auto" else "off"
            view.optString("playfab").takeIf { it.isNotBlank() && it != "null" }?.let { env["XODUS_XSTS_PLAYFAB"] = it }
            XboxDiag.log(app, "launch prepared: $id view=${directory.name} bridge=127.0.0.1:${bridge.port}")
            return Launch(id, exe, env, token, bridge)
        } catch (e: Exception) {
            XboxDiag.log(app, "launch prepare failed: ${e.javaClass.simpleName}")
            released(token); throw e
        }
    }

    private fun service(app: Context, account: String, current: Long, credentials: String): File = synchronized(startup) {
        val existing = synchronized(state) {
            check(generation == current) { "Xbox launch was cancelled" }
            serviceSocket?.takeIf { child?.process?.isAlive == true && owner == account }
        }
        val socket = existing ?: File(app.filesDir, "xbox/runtime/s-${java.util.UUID.randomUUID().toString().replace("-", "")}/xodus.sock")
        if (existing == null) {
            val old = synchronized(state) { child.also { child = null; owner = null; serviceSocket = null } }
            old?.close()
            socket.delete()
            val service = Xodus.start(app, emptyList(), service = true, runtimeDirectory = socket.parentFile!!.apply { mkdirs() },
                credentialGeneration = credentials)
            val accepted = synchronized(state) {
                (generation == current).also { if (it) { child = service; owner = account; serviceSocket = socket } }
            }
            if (!accepted) { service.close(); error("Xbox launch was cancelled") }
            try {
                Xodus.diagnostics(app, service)
                Thread({ runCatching { service.process.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (input.read(buffer) >= 0) { Thread.yield() }
                } } }, "xbox-service-output").apply { isDaemon = true; start() }
                val deadline = SystemClock.elapsedRealtime() + 90_000
                while (!socket.exists() && service.process.isAlive && synchronized(state) { generation == current } &&
                    SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
                check(socket.exists() && service.process.isAlive) {
                    "Xbox account service did not become ready. Check your sign-in and network connection."
                }
                XboxDiag.log(app, "account service ready")
            } catch (e: Exception) {
                synchronized(state) { if (child === service) { child = null; owner = null; serviceSocket = null } }
                service.close()
                throw e
            }
        }
        socket
    }

    /** Ends every launch and the account service (sign-out, session end). */
    fun stop() {
        val processes = synchronized(state) {
            generation++
            val all = launches.clear() + listOfNotNull(child)
            child = null; owner = null; serviceSocket = null
            all
        }
        session = null
        Thread({ processes.forEach { runCatching { it.close() } } }, "xbox-runtime-stop").start()
    }

    /**
     * The five Xodus Nexus Windows modules (shipped in assets/xbox/windows), unpacked once per build to
     * files/xbox/windows, for staging into a game's Wine prefix.
     */
    fun windowsModules(app: Context): File {
        val dir = File(app.filesDir, "xbox/windows")
        val version = runCatching { app.assets.open("xbox/version").use { String(it.readBytes()).trim() } }.getOrDefault("")
        val marker = File(dir, ".version")
        if (version.isNotEmpty() && runCatching { marker.readText() }.getOrNull() == version) return dir
        dir.mkdirs()
        for (name in DLLS) {
            val target = File(dir, name)
            val tmp = File(dir, "$name.tmp")
            app.assets.open("xbox/windows/$name").use { input -> tmp.outputStream().use { input.copyTo(it) } }
            check(tmp.renameTo(target))
        }
        marker.writeText(version)
        return dir
    }

    /** The Xbox runtime modules; all but libnghttp2 are Wine builtins overridden to native. */
    val DLLS = listOf("xgameruntime.dll", "winhttp.dll", "windows.web.dll", "twinapi.appcore.dll", "libnghttp2.dll")
    val DLL_OVERRIDES: String = DLLS.filter { it != "libnghttp2.dll" }.joinToString(";") { it.removeSuffix(".dll") + "=n" }

    /**
     * Puts the modules next to the game's materialised executable. With WINEDLLOVERRIDES=...=n Wine looks in
     * the application's own folder first, so the container's prefix is never touched and the copies go away
     * with the launch view.
     */
    fun stageModules(app: Context, exeDir: File) {
        val source = windowsModules(app)
        for (name in DLLS) {
            val target = File(exeDir, name)
            if (target.exists() && !java.nio.file.Files.isSymbolicLink(target.toPath()) && name == "libnghttp2.dll") continue
            if (java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete()
            File(source, name).copyTo(target, overwrite = true)
        }
    }
}

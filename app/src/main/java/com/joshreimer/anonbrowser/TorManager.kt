package com.joshreimer.anonbrowser

import IPtProxy.Controller
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.Socket

sealed class TorState {
    data object Stopped : TorState()
    data class Starting(val bootstrapPercent: Int) : TorState()
    data class Running(val socksPort: Int) : TorState()
    data class Failed(val message: String) : TorState()
}

/** One relay in the current circuit — the "onion layer" the connection passes through. */
data class CircuitHop(
    val role: String,
    val nickname: String,
    val fingerprint: String,
    val ipAddress: String?
)

data class CircuitInfo(val circuitId: String, val hops: List<CircuitHop>)

/**
 * Launches the bundled tor binary (libtor.so, from the tor-android dependency) as a child
 * process, tracks its bootstrap progress from stdout, and drives its control port for
 * NEWNYM ("new identity"). Optionally starts pluggable-transport listeners (via IPtProxy) and
 * wires them into torrc as bridges. One instance is owned by [TorService] for the life of the
 * app.
 */
class TorManager(private val context: Context) {

    companion object {
        // Tor Browser's traditional local ports — chosen over Orbot's 9050/9051 defaults
        // specifically to avoid clashing with a separately installed Orbot on the same device.
        const val SOCKS_PORT = 9150
        private const val CONTROL_PORT = 9151
        private const val TAG = "TorManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var process: Process? = null

    private val _state = MutableStateFlow<TorState>(TorState.Stopped)
    val state: StateFlow<TorState> = _state.asStateFlow()

    // Non-fatal: set when pasted bridge lines included a transport we can't start (Snowflake,
    // dnstt), so the UI can tell the user those specific lines were skipped.
    private val _bridgeWarning = MutableStateFlow<String?>(null)
    val bridgeWarning: StateFlow<String?> = _bridgeWarning.asStateFlow()

    private val dataDir: File get() = File(context.filesDir, "tor")
    private val torrcFile: File get() = File(context.filesDir, "torrc")
    private val cookieFile: File get() = File(dataDir, "control_auth_cookie")
    private val iptStateDir: File get() = File(context.filesDir, "ipt")

    private var iptController: Controller? = null

    // gomobile binds Go's platform-width `int` return type to Kotlin Long, not Int.
    private val activeTransportPorts = mutableMapOf<String, Long>()

    @Synchronized
    fun start(bridgeLines: List<String> = emptyList()) {
        if (process != null) return
        _state.value = TorState.Starting(0)

        val binaryPath = File(context.applicationInfo.nativeLibraryDir, "libtor.so")
        if (!binaryPath.exists()) {
            _state.value = TorState.Failed("Tor binary not found at ${binaryPath.absolutePath}")
            return
        }

        dataDir.mkdirs()

        val bridgeTorrcLines = try {
            prepareBridges(bridgeLines)
        } catch (t: Throwable) {
            Log.e(TAG, "failed to start bridge transport", t)
            _state.value = TorState.Failed("Bridge transport failed to start: ${t.message}")
            return
        }

        writeTorrc(bridgeTorrcLines)

        scope.launch {
            try {
                val builder = ProcessBuilder(binaryPath.absolutePath, "-f", torrcFile.absolutePath)
                    .redirectErrorStream(true)
                builder.environment()["HOME"] = context.filesDir.absolutePath
                val proc = builder.start()
                process = proc

                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                val bootstrapRegex = Regex("Bootstrapped (\\d+)%")
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    Log.d(TAG, l)
                    val match = bootstrapRegex.find(l)
                    if (match != null) {
                        val pct = match.groupValues[1].toInt()
                        _state.value = if (pct >= 100) TorState.Running(SOCKS_PORT) else TorState.Starting(pct)
                    }
                }

                val exitCode = proc.waitFor()
                process = null
                if (_state.value !is TorState.Failed) {
                    _state.value = TorState.Failed("tor exited unexpectedly (code $exitCode)")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "tor process failed", t)
                process = null
                _state.value = TorState.Failed(t.message ?: "failed to launch tor")
            }
        }
    }

    @Synchronized
    fun stop() {
        process?.destroy()
        process = null
        stopBridgeTransports()
        _state.value = TorState.Stopped
    }

    /** Stops tor (if running), waits briefly for its ports to free up, then starts again with
     * the given bridge configuration. Used when the user changes bridge settings. */
    suspend fun restart(bridgeLines: List<String> = emptyList()) = withContext(Dispatchers.IO) {
        stop()
        delay(300)
        start(bridgeLines)
    }

    /** Sends SIGNAL NEWNYM over the control port, forcing new circuits for a fresh identity. */
    suspend fun newIdentity(): Boolean =
        withControlConnection { input, out ->
            out.write("SIGNAL NEWNYM\r\n".toByteArray())
            out.flush()
            input.readLine()?.startsWith("250") == true
        } ?: false

    /** Looks up the currently-built general-purpose circuit — the relay path (Guard, Middle,
     * Exit) that new page loads are routed through — via the control port. Null if Tor isn't
     * up yet or no circuit has been built. */
    suspend fun getCurrentCircuit(): CircuitInfo? =
        withControlConnection { input, out ->
            out.write("GETINFO circuit-status\r\n".toByteArray())
            out.flush()

            val firstLine = input.readLine() ?: return@withControlConnection null
            val circuitLines = mutableListOf<String>()
            if (firstLine.startsWith("250+circuit-status")) {
                while (true) {
                    val l = input.readLine() ?: break
                    if (l == ".") break
                    circuitLines.add(l)
                }
                input.readLine() // trailing "250 OK"
            }

            val chosen = circuitLines
                .map { it.split(" ") }
                .filter { parts ->
                    parts.size >= 3 && parts[1] == "BUILT" && parts.any { p -> p.startsWith("PURPOSE=GENERAL") }
                }
                .lastOrNull() ?: return@withControlConnection null

            val circuitId = chosen[0]
            val roles = listOf("Guard", "Middle", "Exit")
            val hops = chosen[2].split(",").mapIndexed { index, spec ->
                val withoutDollar = spec.removePrefix("$")
                val fingerprint = withoutDollar.substringBefore("~")
                val nickname = if (withoutDollar.contains("~")) withoutDollar.substringAfter("~") else ""
                CircuitHop(
                    role = roles.getOrElse(index) { "Hop ${index + 1}" },
                    nickname = nickname.ifBlank { "(unknown)" },
                    fingerprint = fingerprint,
                    ipAddress = fetchRelayIp(input, out, fingerprint)
                )
            }

            CircuitInfo(circuitId, hops)
        }

    /** Looks up a relay's IP from the consensus via the control port. Must be called with an
     * already-authenticated connection (see [withControlConnection]); sends one more command
     * on the same socket before it's closed. */
    private fun fetchRelayIp(input: BufferedReader, out: java.io.OutputStream, fingerprint: String): String? {
        return try {
            out.write("GETINFO ns/id/$fingerprint\r\n".toByteArray())
            out.flush()
            val firstLine = input.readLine() ?: return null
            if (!firstLine.startsWith("250+ns/id/")) return null

            var ip: String? = null
            while (true) {
                val l = input.readLine() ?: break
                if (l == ".") break
                if (l.startsWith("r ")) {
                    val parts = l.split(" ")
                    if (parts.size >= 7) ip = parts[6]
                }
            }
            input.readLine() // trailing "250 OK"
            ip
        } catch (t: Throwable) {
            Log.w(TAG, "relay IP lookup failed for $fingerprint", t)
            null
        }
    }

    /** Opens an authenticated control-port connection, runs [block] on it, then closes it. */
    private suspend fun <T> withControlConnection(
        block: (BufferedReader, java.io.OutputStream) -> T?
    ): T? = withContext(Dispatchers.IO) {
        try {
            Socket("127.0.0.1", CONTROL_PORT).use { socket ->
                socket.soTimeout = 5000
                val out = socket.getOutputStream()
                val input = BufferedReader(InputStreamReader(socket.getInputStream()))

                val cookieHex = cookieFile.readBytes().joinToString("") { "%02X".format(it) }
                out.write("AUTHENTICATE $cookieHex\r\n".toByteArray())
                out.flush()
                if (input.readLine()?.startsWith("250") != true) return@withContext null

                val result = block(input, out)

                out.write("QUIT\r\n".toByteArray())
                out.flush()

                result
            }
        } catch (t: Throwable) {
            Log.e(TAG, "control connection failed", t)
            null
        }
    }

    /** Starts an IPtProxy listener for each distinct pluggable transport the given bridge
     * lines need, and returns the extra torrc lines (UseBridges/ClientTransportPlugin/Bridge)
     * to wire them in. Lines needing an unsupported transport are skipped and surfaced via
     * [bridgeWarning]. */
    private fun prepareBridges(bridgeLines: List<String>): List<String> {
        stopBridgeTransports()
        _bridgeWarning.value = null

        val parsed = bridgeLines.mapNotNull { parseBridgeLine(it) }
        if (parsed.isEmpty()) return emptyList()

        val unsupported = parsed.filterIsInstance<ParsedBridge.Unsupported>().map { it.transport }.distinct()
        val usable = parsed.filter { it !is ParsedBridge.Unsupported }
        if (unsupported.isNotEmpty()) {
            _bridgeWarning.value = "Skipped unsupported bridge transport(s): ${unsupported.joinToString(", ")}"
        }
        if (usable.isEmpty()) return emptyList()

        val neededTransports = usable.filterIsInstance<ParsedBridge.WithTransport>()
            .map { it.transport }
            .distinct()

        val lines = mutableListOf("UseBridges 1")

        if (neededTransports.isNotEmpty()) {
            val controller = iptController ?: Controller(
                iptStateDir.absolutePath,
                true,
                false,
                "ERROR",
                object : IPtProxy.OnTransportEvents {
                    override fun connected(name: String?) {
                        Log.d(TAG, "pluggable transport connected: $name")
                    }

                    override fun error(name: String?, error: Exception?) {
                        Log.w(TAG, "pluggable transport error on $name: ${error?.message}")
                    }

                    override fun stopped(name: String?, error: Exception?) {
                        Log.d(TAG, "pluggable transport stopped: $name")
                    }
                }
            ).also { iptController = it }

            for (transport in neededTransports) {
                val port = activeTransportPorts.getOrPut(transport) {
                    controller.start(transport, "")
                    controller.port(transport)
                }
                lines += "ClientTransportPlugin $transport socks5 127.0.0.1:$port"
            }
        }

        for (bridge in usable) {
            val torrcLine = when (bridge) {
                is ParsedBridge.Vanilla -> bridge.torrcLine
                is ParsedBridge.WithTransport -> bridge.torrcLine
                is ParsedBridge.Unsupported -> continue
            }
            lines += "Bridge $torrcLine"
        }

        return lines
    }

    private fun stopBridgeTransports() {
        val controller = iptController ?: return
        for (transport in activeTransportPorts.keys) {
            controller.stop(transport)
        }
        activeTransportPorts.clear()
        iptController = null
    }

    private fun writeTorrc(extraLines: List<String>) {
        val base = """
            SocksPort 127.0.0.1:$SOCKS_PORT
            ControlPort 127.0.0.1:$CONTROL_PORT
            CookieAuthentication 1
            CookieAuthFile ${cookieFile.absolutePath}
            DataDirectory ${dataDir.absolutePath}
            AvoidDiskWrites 1
            ClientOnly 1
            SocksPolicy accept 127.0.0.1
            Log notice stdout
        """.trimIndent()
        val config = (listOf(base) + extraLines).joinToString("\n")
        torrcFile.writeText(config)
    }
}

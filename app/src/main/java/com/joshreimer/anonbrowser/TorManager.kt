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
    val ipAddress: String?,
    val countryCode: String?
)

/** [isOnionCircuit] is true when this is the rendezvous circuit used to reach a .onion
 * address — those never have an exit relay, since the destination never leaves Tor. */
data class CircuitInfo(val circuitId: String, val hops: List<CircuitHop>, val isOnionCircuit: Boolean)

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
    private val geoIpFile: File get() = File(context.filesDir, "geoip")

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

        // Non-fatal: without this, relay country lookups (GETINFO ip-to-country) just come
        // back empty and the circuit viewer shows no flags.
        val geoIpLine = try {
            listOf("GeoIPFile ${ensureGeoIpFile().absolutePath}")
        } catch (t: Throwable) {
            Log.w(TAG, "failed to extract geoip database; country lookups will be unavailable", t)
            emptyList()
        }

        writeTorrc(bridgeTorrcLines + geoIpLine)

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

    /** Looks up the currently-built circuit via the control port — the relay path (Guard,
     * Middle, Exit) that new page loads are routed through. When [forOnionTarget] is set, looks
     * for the rendezvous circuit used to reach a .onion address instead: those are also three
     * hops, but the third is a Rendezvous Point chosen inside the Tor network, not an exit
     * relay — hidden services never involve an exit node, since the destination never leaves
     * Tor. Null if Tor isn't up yet or no matching circuit has been built. */
    suspend fun getCurrentCircuit(forOnionTarget: Boolean = false): CircuitInfo? =
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

            // Tor 0.4.8+ builds Conflux-linked circuit pairs for ordinary web traffic instead
            // of a single PURPOSE=GENERAL circuit; GENERAL is kept as a fallback for older tor
            // builds that don't use Conflux.
            val purposes = if (forOnionTarget) {
                listOf("PURPOSE=HS_CLIENT_REND")
            } else {
                listOf("PURPOSE=CONFLUX_LINKED", "PURPOSE=GENERAL")
            }
            val chosen = circuitLines
                .map { it.split(" ") }
                .filter { parts ->
                    parts.size >= 3 && parts[1] == "BUILT" &&
                        purposes.any { purpose -> parts.any { p -> p.startsWith(purpose) } }
                }
                .lastOrNull() ?: return@withControlConnection null

            val circuitId = chosen[0]
            val roles = if (forOnionTarget) {
                listOf("Guard", "Middle", "Rendezvous")
            } else {
                listOf("Guard", "Middle", "Exit")
            }
            val hops = chosen[2].split(",").mapIndexed { index, spec ->
                val withoutDollar = spec.removePrefix("$")
                val fingerprint = withoutDollar.substringBefore("~")
                val nickname = if (withoutDollar.contains("~")) withoutDollar.substringAfter("~") else ""
                val ip = fetchRelayIp(input, out, fingerprint)
                CircuitHop(
                    role = roles.getOrElse(index) { "Hop ${index + 1}" },
                    nickname = nickname.ifBlank { "(unknown)" },
                    fingerprint = fingerprint,
                    ipAddress = ip,
                    countryCode = ip?.let { fetchRelayCountry(input, out, it) }
                )
            }

            CircuitInfo(circuitId, hops, isOnionCircuit = forOnionTarget)
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

    /** Looks up an IP's two-letter country code from the GeoIP database ([ensureGeoIpFile])
     * via the control port. Must be called with an already-authenticated connection (see
     * [withControlConnection]); sends one more command on the same socket before it's closed.
     *
     * A successful single-keyword GETINFO reply is always two lines — "250-key=value" then a
     * separate "250 OK" — never one ("control_reply_add_done" always appends the OK line
     * itself, per Tor's control_proto.c). Only a failure (e.g. GeoIP data not loaded) is a
     * single "551 ..." line. Leaving that trailing "250 OK" unread here would desync the
     * connection: the next command sent on it (the following hop's ns/id lookup) would read
     * this leftover line instead of its own reply. */
    private fun fetchRelayCountry(input: BufferedReader, out: java.io.OutputStream, ip: String): String? {
        return try {
            out.write("GETINFO ip-to-country/$ip\r\n".toByteArray())
            out.flush()
            val line = input.readLine() ?: return null
            if (line.startsWith("250-")) {
                input.readLine() // trailing "250 OK"
            }
            if (!line.startsWith("250")) return null
            line.substringAfter("=", "").trim().uppercase().takeIf { it.length == 2 && it != "??" }
        } catch (t: Throwable) {
            Log.w(TAG, "country lookup failed for $ip", t)
            null
        }
    }

    /** Copies the bundled GeoIP database (Tor's own country-range format) out of assets into
     * app storage the one time it's needed, so it has a real filesystem path to hand to Tor's
     * GeoIPFile directive — tor can't read straight out of the APK. */
    private fun ensureGeoIpFile(): File {
        if (!geoIpFile.exists()) {
            context.assets.open("geoip").use { input ->
                geoIpFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return geoIpFile
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

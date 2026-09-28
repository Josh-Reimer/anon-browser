package com.joshreimer.anonbrowser

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

/**
 * Launches the bundled tor binary (libtor.so, from the tor-android dependency) as a child
 * process, tracks its bootstrap progress from stdout, and drives its control port for
 * NEWNYM ("new identity"). One instance is owned by [TorService] for the life of the app.
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

    private val dataDir: File get() = File(context.filesDir, "tor")
    private val torrcFile: File get() = File(context.filesDir, "torrc")
    private val cookieFile: File get() = File(dataDir, "control_auth_cookie")

    @Synchronized
    fun start() {
        if (process != null) return
        _state.value = TorState.Starting(0)

        val binaryPath = File(context.applicationInfo.nativeLibraryDir, "libtor.so")
        if (!binaryPath.exists()) {
            _state.value = TorState.Failed("Tor binary not found at ${binaryPath.absolutePath}")
            return
        }

        dataDir.mkdirs()
        writeTorrc()

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
        _state.value = TorState.Stopped
    }

    /** Sends SIGNAL NEWNYM over the control port, forcing new circuits for a fresh identity. */
    suspend fun newIdentity(): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket("127.0.0.1", CONTROL_PORT).use { socket ->
                socket.soTimeout = 5000
                val out = socket.getOutputStream()
                val input = BufferedReader(InputStreamReader(socket.getInputStream()))

                val cookieHex = cookieFile.readBytes().joinToString("") { "%02X".format(it) }
                out.write("AUTHENTICATE $cookieHex\r\n".toByteArray())
                out.flush()
                if (input.readLine()?.startsWith("250") != true) return@withContext false

                out.write("SIGNAL NEWNYM\r\n".toByteArray())
                out.flush()
                val signalOk = input.readLine()?.startsWith("250") == true

                out.write("QUIT\r\n".toByteArray())
                out.flush()

                signalOk
            }
        } catch (t: Throwable) {
            Log.e(TAG, "newIdentity failed", t)
            false
        }
    }

    private fun writeTorrc() {
        val config = """
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
        torrcFile.writeText(config)
    }
}

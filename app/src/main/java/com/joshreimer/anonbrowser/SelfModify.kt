package com.joshreimer.anonbrowser

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the freshly built APK lands — same path `self_modify_inner.sh` runs `gradlew
 * assembleDebug` against, and what `res/xml/file_paths.xml` exposes via FileProvider. */
const val SELF_MODIFY_APK_PATH =
    "/sdcard/coding/anon-browser/app/build/outputs/apk/debug/app-debug.apk"

private const val SELF_MODIFY_SCRIPT_PATH = "/sdcard/coding/anon-browser/tools/self_modify.sh"
private const val TERMUX_BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"
const val ACTION_SELF_MODIFY_RESULT = "com.joshreimer.anonbrowser.action.SELF_MODIFY_RESULT"

/** One attempt at having Claude (running in the user's own Termux + proot-distro setup) edit
 * this app's own source and rebuild it. */
sealed class SelfModifyState {
    data object Idle : SelfModifyState()
    data class Running(val startedAtMillis: Long, val prompt: String) : SelfModifyState()
    data class Success(val finishedAtMillis: Long, val logTail: String) : SelfModifyState()
    data class Failed(val finishedAtMillis: Long, val exitCode: Int, val logTail: String) : SelfModifyState()
}

/** Persists the in-flight/last self-modify attempt across process death — a background build
 * can run for minutes, easily longer than this app stays alive in memory, and the result
 * PendingIntent still wakes [SelfModifyResultReceiver] even after the process is gone. The
 * StateFlow is what the open sheet actually collects for live updates. */
object SelfModifyStore {
    private const val PREFS_NAME = "self_modify"
    private const val KEY_KIND = "kind"
    private const val KEY_STARTED_AT = "started_at"
    private const val KEY_FINISHED_AT = "finished_at"
    private const val KEY_PROMPT = "prompt"
    private const val KEY_EXIT_CODE = "exit_code"
    private const val KEY_LOG_TAIL = "log_tail"

    private const val KIND_IDLE = "idle"
    private const val KIND_RUNNING = "running"
    private const val KIND_SUCCESS = "success"
    private const val KIND_FAILED = "failed"

    // Only the last this many characters of combined stdout+stderr are kept — Gradle output in
    // particular can be long, and this is meant for "did it work, roughly why not", not a full log.
    private const val LOG_TAIL_LIMIT = 4000

    private val _state = MutableStateFlow<SelfModifyState>(SelfModifyState.Idle)
    val state: StateFlow<SelfModifyState> = _state.asStateFlow()

    /** Call once, before setContent, so a result delivered while this process was dead isn't lost. */
    fun hydrate(context: Context) {
        _state.value = load(context.applicationContext)
    }

    fun markRunning(context: Context, prompt: String) {
        val value = SelfModifyState.Running(System.currentTimeMillis(), prompt)
        persist(context.applicationContext, value)
        _state.value = value
    }

    fun recordResult(context: Context, exitCode: Int, stdout: String, stderr: String) {
        val combined = (stdout + "\n" + stderr).trim()
        val logTail = combined.takeLast(LOG_TAIL_LIMIT)
        val value = if (exitCode == 0) {
            SelfModifyState.Success(System.currentTimeMillis(), logTail)
        } else {
            SelfModifyState.Failed(System.currentTimeMillis(), exitCode, logTail)
        }
        persist(context.applicationContext, value)
        _state.value = value
    }

    /** Manual escape hatch for when Termux never calls back (e.g. `allow-external-apps` isn't
     * set, or Termux got force-stopped mid-build) — only clears local state, does not and cannot
     * stop whatever is still running on the Termux side. */
    fun reset(context: Context) {
        persist(context.applicationContext, SelfModifyState.Idle)
        _state.value = SelfModifyState.Idle
    }

    private fun persist(context: Context, value: SelfModifyState) {
        val editor = prefs(context).edit()
        when (value) {
            is SelfModifyState.Idle -> {
                editor.putString(KEY_KIND, KIND_IDLE)
            }
            is SelfModifyState.Running -> {
                editor.putString(KEY_KIND, KIND_RUNNING)
                    .putLong(KEY_STARTED_AT, value.startedAtMillis)
                    .putString(KEY_PROMPT, value.prompt)
            }
            is SelfModifyState.Success -> {
                editor.putString(KEY_KIND, KIND_SUCCESS)
                    .putLong(KEY_FINISHED_AT, value.finishedAtMillis)
                    .putString(KEY_LOG_TAIL, value.logTail)
            }
            is SelfModifyState.Failed -> {
                editor.putString(KEY_KIND, KIND_FAILED)
                    .putLong(KEY_FINISHED_AT, value.finishedAtMillis)
                    .putInt(KEY_EXIT_CODE, value.exitCode)
                    .putString(KEY_LOG_TAIL, value.logTail)
            }
        }
        editor.apply()
    }

    private fun load(context: Context): SelfModifyState {
        val p = prefs(context)
        return when (p.getString(KEY_KIND, KIND_IDLE)) {
            KIND_RUNNING -> SelfModifyState.Running(
                startedAtMillis = p.getLong(KEY_STARTED_AT, 0L),
                prompt = p.getString(KEY_PROMPT, "") ?: ""
            )
            KIND_SUCCESS -> SelfModifyState.Success(
                finishedAtMillis = p.getLong(KEY_FINISHED_AT, 0L),
                logTail = p.getString(KEY_LOG_TAIL, "") ?: ""
            )
            KIND_FAILED -> SelfModifyState.Failed(
                finishedAtMillis = p.getLong(KEY_FINISHED_AT, 0L),
                exitCode = p.getInt(KEY_EXIT_CODE, -1),
                logTail = p.getString(KEY_LOG_TAIL, "") ?: ""
            )
            else -> SelfModifyState.Idle
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * Sends [prompt] to Claude (running in the user's own Termux + proot-distro Debian setup) via
 * Termux's RUN_COMMAND plugin API, to edit this app's own source and rebuild it. Returns false
 * if the request couldn't even be handed to Termux (not installed, or the RUN_COMMAND runtime
 * permission hasn't been granted) — the caller should show setup instructions in that case,
 * since Termux gives no structured way to tell "not installed" apart from "permission missing"
 * at this call site.
 *
 * RUN_COMMAND_PATH points at Termux's own `bash`, with the actual script as the first argument,
 * rather than at the script directly: this repo lives on a bind-mounted /sdcard path that can't
 * carry an executable bit (chmod on it is a silent no-op — the same reason this project's own
 * Gradle invocations everywhere else use `sh gradlew` instead of `./gradlew`), so Termux can't
 * execve the script itself. Running it as an argument to bash only needs read permission.
 */
fun launchSelfModify(context: Context, prompt: String): Boolean {
    val resultIntent = Intent(context, SelfModifyResultReceiver::class.java).apply {
        action = ACTION_SELF_MODIFY_RESULT
    }
    // Termux writes its result extras onto this Intent before firing it, which an immutable
    // PendingIntent can't accept — unlike every other PendingIntent in this app (TorService),
    // this one has to be mutable.
    val resultPendingIntent = PendingIntent.getBroadcast(
        context, 0, resultIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    val runCommandIntent = Intent().apply {
        setClassName("com.termux", "com.termux.app.RunCommandService")
        action = "com.termux.RUN_COMMAND"
        putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_BASH_PATH)
        putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf(SELF_MODIFY_SCRIPT_PATH, prompt))
        putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
        putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", resultPendingIntent)
    }

    return try {
        context.startService(runCommandIntent) != null
    } catch (t: Throwable) {
        Log.w("SelfModify", "failed to launch RUN_COMMAND (is Termux installed and permitted?)", t)
        false
    }
}

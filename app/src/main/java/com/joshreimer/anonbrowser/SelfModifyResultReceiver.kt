package com.joshreimer.anonbrowser

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives the result of a Termux RUN_COMMAND execution (see [launchSelfModify]) and records it
 * in [SelfModifyStore]. Termux's plugin-result contract nests stdout/stderr/exitCode under a
 * "result" Bundle extra (with "err"/"errmsg" instead if Termux itself couldn't launch the
 * command at all, e.g. `allow-external-apps` isn't set) — logged defensively here since this is
 * the first real-device integration point for that contract in this app.
 */
class SelfModifyResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "received result, extras=${intent.extras?.keySet()}")

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = intent.getBundleExtra("result")
                if (result == null) {
                    Log.w(TAG, "no 'result' bundle in RUN_COMMAND callback; treating as failure")
                    SelfModifyStore.recordResult(context, exitCode = -1, stdout = "", stderr = "No result from Termux.")
                    return@launch
                }

                val termuxErrorMessage = result.getString("errmsg")
                if (!termuxErrorMessage.isNullOrBlank()) {
                    // Termux couldn't even run the command (permission not granted,
                    // allow-external-apps not set, etc.) — this never reached our script.
                    Log.w(TAG, "Termux reported a launch error: $termuxErrorMessage")
                    SelfModifyStore.recordResult(context, exitCode = -1, stdout = "", stderr = termuxErrorMessage)
                    return@launch
                }

                val exitCode = if (result.containsKey("exitCode")) result.getInt("exitCode") else -1
                val stdout = result.getString("stdout") ?: ""
                val stderr = result.getString("stderr") ?: ""
                SelfModifyStore.recordResult(context, exitCode, stdout, stderr)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "SelfModifyReceiver"
    }
}

package com.joshreimer.anonbrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Foreground service so Android doesn't kill the tor child process once the app is
 * backgrounded (background network daemons without a foreground service get frozen/killed
 * within minutes on API 26+).
 */
class TorService : LifecycleService() {

    inner class LocalBinder : Binder() {
        val service: TorService get() = this@TorService
    }

    private val binder = LocalBinder()

    lateinit var torManager: TorManager
        private set

    override fun onCreate() {
        super.onCreate()
        torManager = TorManager(applicationContext)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting Tor…"))
        torManager.start(BridgePrefs.getActiveLines(applicationContext))

        lifecycleScope.launch {
            torManager.state.collect { state ->
                val text = when (state) {
                    is TorState.Stopped -> "Stopped"
                    is TorState.Starting -> "Bootstrapping… ${state.bootstrapPercent}%"
                    is TorState.Running -> "Connected — routing traffic through Tor"
                    is TorState.Failed -> "Failed: ${state.message}"
                }
                updateNotification(text)
            }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_QUIT) {
            torManager.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            // A browser meant for anonymity shouldn't leave WebView/process state lingering
            // after the user asks to quit — kill the whole app, not just this service.
            android.os.Process.killProcess(android.os.Process.myPid())
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        torManager.stop()
        super.onDestroy()
    }

    /** Persists the new bridge config and restarts tor with it applied. */
    fun applyBridgeSettings(enabled: Boolean, rawText: String) {
        BridgePrefs.save(applicationContext, enabled, rawText)
        val lines = if (enabled) {
            rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
        lifecycleScope.launch {
            torManager.restart(lines)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Tor status", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val quitIntent = PendingIntent.getService(
            this, 0,
            Intent(this, TorService::class.java).setAction(ACTION_QUIT),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Anon Browser")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Quit", quitIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "tor_status"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_QUIT = "com.joshreimer.anonbrowser.action.QUIT"
    }
}

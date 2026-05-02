package co.sirdab.printer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * A foreground service that keeps the app process alive and monitors the
 * embedded HTTP server.
 *
 * Responsibilities:
 *  1. Hold a foreground service notification so Android treats the process as
 *     high-priority and does not kill it under normal memory pressure.
 *  2. Watchdog: periodically ping localhost:8080/health and ask MainActivity
 *     to restart the HTTP server if it stops responding.
 *
 * START_STICKY ensures Android restarts this service automatically if it is
 * ever killed under extreme memory pressure.
 */
class KeepAliveService : Service() {

    companion object {
        const val CHANNEL_ID      = "printer_companion_channel"
        const val NOTIFICATION_ID = 1001

        private const val TAG = "KeepAliveService"

        /** Seconds before the first watchdog check (give MainActivity time to start). */
        private const val WATCHDOG_INITIAL_DELAY_S = 30L

        /** Seconds between watchdog health checks. */
        private const val WATCHDOG_INTERVAL_S = 30L

        private const val HEALTH_URL = "http://localhost:${PrintHttpServer.PORT}/health"
    }

    private lateinit var watchdogExecutor: ScheduledExecutorService

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        startWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        watchdogExecutor.shutdown()
        super.onDestroy()
        // Android will restart this service automatically (START_STICKY)
    }

    /**
     * Called when the user removes the app's task from the recents screen.
     * This is one of the few contexts where a background service is still
     * permitted to start an activity on Android 10+ — the callback is
     * triggered by an explicit user gesture, so the OS allows it.
     *
     * Note: with android:excludeFromRecents="true" on MainActivity, the task
     * won't appear in recents at all, so this method should rarely fire.
     * It acts as a belt-and-suspenders safety net.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.w(TAG, "Task removed from recents — scheduling MainActivity restart")
        // Small delay so the system finishes tearing down the old task
        android.os.Handler(mainLooper).postDelayed({
            try {
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(intent)
                Log.i(TAG, "MainActivity restarted after task removal")
            } catch (e: Exception) {
                Log.e(TAG, "Could not restart MainActivity after task removal: ${e.message}")
            }
        }, 1_000L)
    }

    // ── Watchdog ──────────────────────────────────────────────────────────────

    private fun startWatchdog() {
        watchdogExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "http-watchdog").apply { isDaemon = true }
        }
        watchdogExecutor.scheduleWithFixedDelay(
            ::checkHttpServer,
            WATCHDOG_INITIAL_DELAY_S,
            WATCHDOG_INTERVAL_S,
            TimeUnit.SECONDS
        )
        Log.d(TAG, "Watchdog started — checking every ${WATCHDOG_INTERVAL_S}s")
    }

    private fun checkHttpServer() {
        try {
            val conn = URL(HEALTH_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 2_000
            conn.readTimeout    = 2_000
            val code = conn.responseCode
            conn.disconnect()

            if (code == 200) {
                Log.d(TAG, "Watchdog: HTTP server OK")
                return
            }
            Log.w(TAG, "Watchdog: HTTP server returned $code — requesting restart")
        } catch (e: Exception) {
            Log.w(TAG, "Watchdog: HTTP server unreachable — requesting restart (${e.message})")
        }

        // Signal MainActivity to restart the HTTP server.
        // WeakReference means this is a no-op if the activity has been GC'd.
        val activity = MainActivity.instance?.get()
        if (activity != null) {
            activity.ensureHttpServerRunning()
        } else {
            // Activity is gone — try to restart it so it re-creates the HTTP server
            Log.w(TAG, "Watchdog: MainActivity not available — attempting restart")
            try {
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Watchdog: could not restart MainActivity: ${e.message}")
            }
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val tapPending   = PendingIntent.getActivity(this, 0, tapIntent, pendingFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_print)
            .setContentIntent(tapPending)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }
}

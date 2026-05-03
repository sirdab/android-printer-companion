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
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Foreground service that owns the HTTP server and keeps the process alive.
 *
 * Responsibilities:
 *  1. Hold a foreground service notification so Android treats the process as
 *     high-priority and does not kill it under normal memory pressure.
 *  2. Own and manage [PrintHttpServer] — the server lives here, not in
 *     MainActivity, so it survives independently of the Activity lifecycle.
 *  3. Own [ConfigManager] — shared with MainActivity via [instance].
 *  4. Watchdog: periodically ping /health and restart the HTTP server if it stops.
 *
 * [MainActivity] retains ownership of [PrinterClient] because the GAINSCHA SDK
 * requires a GTSPLWIFIActivity subclass.  [PrintHttpServer] reaches PrinterClient
 * via MainActivity.instance when processing print requests.
 *
 * START_STICKY ensures Android restarts this service automatically if killed.
 */
class KeepAliveService : Service() {

    companion object {
        const val CHANNEL_ID      = "printer_companion_channel"
        const val NOTIFICATION_ID = 1001

        private const val TAG = "KeepAliveService"
        private const val WATCHDOG_INITIAL_DELAY_S = 30L
        private const val WATCHDOG_INTERVAL_S = 30L
        private const val HEALTH_URL = "http://localhost:${PrintHttpServer.PORT}/health"

        /** Weak reference so other components can reach the running service instance. */
        var instance: WeakReference<KeepAliveService>? = null
    }

    lateinit var configManager: ConfigManager
    private lateinit var httpServer: PrintHttpServer
    private lateinit var watchdogExecutor: ScheduledExecutorService

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = WeakReference(this)

        configManager = ConfigManager(applicationContext)

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

        httpServer = PrintHttpServer(this)
        httpServer.start()
        Log.i(TAG, "HTTP server started on :${PrintHttpServer.PORT}")

        startWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        if (::httpServer.isInitialized) httpServer.stop()
        if (::watchdogExecutor.isInitialized) watchdogExecutor.shutdown()
        super.onDestroy()
    }

    /**
     * Restarts the HTTP server if it is no longer alive.
     * Called by the watchdog when a health check fails.
     */
    fun ensureHttpServerRunning() {
        if (!::httpServer.isInitialized || !httpServer.isAlive) {
            Log.w(TAG, "HTTP server not running — restarting")
            try {
                if (::httpServer.isInitialized) httpServer.stop()
                httpServer = PrintHttpServer(this)
                httpServer.start()
                Log.i(TAG, "HTTP server restarted successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart HTTP server", e)
            }
        }
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
            Log.w(TAG, "Watchdog: HTTP server returned $code — restarting")
        } catch (e: Exception) {
            Log.w(TAG, "Watchdog: HTTP server unreachable — restarting (${e.message})")
        }
        ensureHttpServerRunning()
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

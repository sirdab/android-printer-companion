package co.sirdab.printer

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.gainscha.gtspl_sdk.GTSPLWIFIActivity
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The companion app's single activity.
 *
 * Architecture rationale
 * ─────────────────────
 * The GAINSCHA SDK's entry point (GTSPLWIFIActivity) is an Activity subclass,
 * so SDK methods must be called on an Activity instance.  Rather than fighting
 * the SDK, we embrace this: MainActivity *is* the SDK object.
 *
 * Reliability is achieved through layered keep-alive mechanisms:
 *  1. KeepAliveService (foreground service) — Android will not kill a process
 *     that has a running foreground service, so the process stays resident even
 *     when Fully Kiosk is in the foreground.
 *  2. onBackPressed() — redirects Back to moveTaskToBack() instead of finish(),
 *     so the Activity is never destroyed by user navigation.
 *  3. singleTask launch mode (AndroidManifest) — prevents duplicate instances.
 *
 * The NanoHTTPD HTTP server and the single-thread print executor both live
 * inside this process.  Incoming print requests from Fully Kiosk Browser block
 * NanoHTTPD's per-request thread until the SDK returns; the executor ensures
 * only one print job runs at a time.
 */
class MainActivity : GTSPLWIFIActivity() {

    companion object {
        private const val TAG = "MainActivity"

        /**
         * Weak reference to the currently running instance.
         * Used by [KeepAliveService] to signal that the HTTP server should be
         * restarted without holding a strong reference that would prevent GC.
         */
        var instance: WeakReference<MainActivity>? = null
    }

    // ── Public references (accessed by PrintHttpServer and PrinterClient) ─────

    lateinit var configManager:  ConfigManager
    lateinit var printerClient: PrinterClient

    // ── Private ───────────────────────────────────────────────────────────────

    private lateinit var httpServer:       PrintHttpServer
    private lateinit var tvStatus:         TextView
    private lateinit var tvPrinterConfig:  TextView
    private lateinit var tvLastPrint:      TextView
    private lateinit var etPrinterIp:      EditText
    private lateinit var btnSaveConfig:    Button

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus        = findViewById(R.id.tvStatus)
        tvPrinterConfig = findViewById(R.id.tvPrinterConfig)
        tvLastPrint     = findViewById(R.id.tvLastPrint)
        etPrinterIp     = findViewById(R.id.etPrinterIp)
        btnSaveConfig   = findViewById(R.id.btnSaveConfig)

        // Register this instance so KeepAliveService watchdog can reach us
        instance = WeakReference(this)

        // Initialise core components
        configManager  = ConfigManager(applicationContext)
        val pdfRenderer = PdfPageRenderer(applicationContext)
        // 'this' extends GTSPLWIFIActivity — SDK methods are called on this object
        printerClient  = PrinterClient(this, applicationContext, configManager, pdfRenderer)

        // Ensure foreground service is running (idempotent — safe to call repeatedly)
        startKeepAliveService()

        // Ask Android to exempt this app from battery optimisation / Doze.
        // Without this, aggressive OEM power managers can suspend the watchdog
        // and foreground service after the screen has been off for a while.
        requestBatteryOptimizationExemption()

        // Start the HTTP server
        httpServer = PrintHttpServer(this)
        httpServer.start()

        // Pre-fill IP field with saved value
        etPrinterIp.setText(configManager.printerIp)

        btnSaveConfig.setOnClickListener {
            val ip = etPrinterIp.text.toString().trim()
            if (ip.isEmpty()) {
                Toast.makeText(this, "Enter a printer IP address", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            configManager.printerIp = ip
            refreshConfigDisplay()
            updateStatus(getString(R.string.status_ready))
            Toast.makeText(this, "Saved — printer IP: $ip", Toast.LENGTH_SHORT).show()
        }

        // Initial UI state
        refreshConfigDisplay()
        updateStatus(
            if (configManager.isConfigured()) getString(R.string.status_ready)
            else getString(R.string.status_no_config)
        )
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Called when the notification tap re-launches an already-running instance.
        // Refresh UI in case config was updated externally.
        refreshConfigDisplay()
    }

    override fun onDestroy() {
        instance = null
        if (::httpServer.isInitialized) httpServer.stop()
        if (::printerClient.isInitialized) printerClient.shutdown()
        super.onDestroy()
    }

    /**
     * Intercept Back to minimise instead of destroying.
     * This is critical: destroying the activity would stop the HTTP server
     * and make the SDK unavailable for future print requests.
     */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    // ── UI updates (called from PrintHttpServer on NanoHTTPD threads) ─────────

    /** Updates the main status line.  Safe to call from any thread. */
    fun updateStatus(message: String) {
        runOnUiThread {
            tvStatus.text = message
        }
    }

    /** Refreshes the printer config display.  Safe to call from any thread. */
    fun refreshConfigDisplay() {
        runOnUiThread {
            tvPrinterConfig.text = if (configManager.isConfigured()) {
                "${configManager.printerIp}:${configManager.printerPort}"
            } else {
                getString(R.string.status_not_configured_hint)
            }
        }
    }

    /** Records the result of the most recent print job in the UI. */
    fun recordLastPrint(success: Boolean, detail: String) {
        val time   = timeFormat.format(Date())
        val symbol = if (success) "✓" else "✗"
        val text   = "$symbol  $time  $detail"
        runOnUiThread {
            tvLastPrint.text = text
        }
    }

    // ── Watchdog callback (called by KeepAliveService on the watchdog thread) ──

    /**
     * Called by [KeepAliveService] when the HTTP health check fails.
     * Restarts [PrintHttpServer] if it is no longer alive.
     * Safe to call from any thread.
     */
    fun ensureHttpServerRunning() {
        runOnUiThread {
            if (!::httpServer.isInitialized || !httpServer.isAlive) {
                Log.w(TAG, "HTTP server not running — restarting")
                try {
                    if (::httpServer.isInitialized) httpServer.stop()
                    httpServer = PrintHttpServer(this)
                    httpServer.start()
                    updateStatus("HTTP server restarted")
                    Log.i(TAG, "HTTP server restarted successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restart HTTP server", e)
                    updateStatus("HTTP server restart failed: ${e.message}")
                }
            } else {
                Log.d(TAG, "ensureHttpServerRunning: server already alive")
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Requests that Android stop applying battery optimisations to this app.
     *
     * On API 23+ this opens the system dialog asking the user to allow
     * unrestricted background activity for this package.  The dialog is shown
     * at most once: if the user has already granted the exemption (or denied
     * it) we don't prompt again.
     *
     * This is safe to declare in the manifest (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
     * is whitelisted for direct-to-settings use) and is the standard approach
     * for kiosk / always-on apps.
     */
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Log.d(TAG, "Battery optimisation already disabled — skipping prompt")
            return
        }
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Some OEM firmwares don't support this intent.  Log and move on —
            // the foreground service still provides meaningful protection.
            Log.w(TAG, "Could not request battery optimisation exemption: ${e.message}")
        }
    }

    private fun startKeepAliveService() {
        val intent = Intent(this, KeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}

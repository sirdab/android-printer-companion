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
 * Architecture
 * ─────────────
 * The GAINSCHA SDK's entry point (GTSPLWIFIActivity) is an Activity subclass,
 * so SDK methods must be called on an Activity instance. MainActivity is therefore
 * the SDK host and owns [PrinterClient].
 *
 * [PrintHttpServer] and [ConfigManager] live in [KeepAliveService] and survive
 * independently of the Activity lifecycle. When MainActivity is in the background
 * or not yet started, /config and /health still work. /print and /status are
 * temporarily unavailable until MainActivity is ready.
 *
 * Reliability mechanisms:
 *  1. KeepAliveService (foreground service) — keeps the process alive.
 *  2. onBackPressed() — redirects Back to moveTaskToBack() instead of finish().
 *  3. singleTask launch mode — prevents duplicate instances.
 */
class MainActivity : GTSPLWIFIActivity() {

    companion object {
        private const val TAG = "MainActivity"

        /**
         * Weak reference used by [PrintHttpServer] to reach [PrinterClient]
         * without holding a strong reference that would prevent GC.
         */
        var instance: WeakReference<MainActivity>? = null
    }

    lateinit var printerClient: PrinterClient

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

        // Register this instance so PrintHttpServer can reach PrinterClient
        instance = WeakReference(this)

        // Ensure the foreground service is running — it owns the HTTP server
        // and ConfigManager. Safe to call repeatedly (idempotent).
        startKeepAliveService()

        // Give the service a moment to start, then finish initialising
        // (configManager will be available via KeepAliveService.instance)
        requestBatteryOptimizationExemption()

        val configManager  = getConfigManager()
        val pdfRenderer    = PdfPageRenderer(applicationContext)
        printerClient      = PrinterClient(this, applicationContext, configManager, pdfRenderer)

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

        refreshConfigDisplay()
        updateStatus(
            if (configManager.isConfigured()) getString(R.string.status_ready)
            else getString(R.string.status_no_config)
        )
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        refreshConfigDisplay()
    }

    override fun onDestroy() {
        instance = null
        if (::printerClient.isInitialized) printerClient.shutdown()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    // ── UI updates (called from PrintHttpServer on NanoHTTPD threads) ─────────

    fun updateStatus(message: String) {
        runOnUiThread { tvStatus.text = message }
    }

    fun refreshConfigDisplay() {
        val configManager = getConfigManager()
        runOnUiThread {
            tvPrinterConfig.text = if (configManager.isConfigured()) {
                "${configManager.printerIp}:${configManager.printerPort}"
            } else {
                getString(R.string.status_not_configured_hint)
            }
        }
    }

    fun recordLastPrint(success: Boolean, detail: String) {
        val time   = timeFormat.format(Date())
        val symbol = if (success) "✓" else "✗"
        val text   = "$symbol  $time  $detail"
        runOnUiThread { tvLastPrint.text = text }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Returns the [ConfigManager] from the running [KeepAliveService] if
     * available, or creates a local one as a fallback (same SharedPreferences
     * file, so all changes are shared within the process).
     */
    private fun getConfigManager(): ConfigManager {
        return KeepAliveService.instance?.get()?.configManager
            ?: ConfigManager(applicationContext)
    }

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

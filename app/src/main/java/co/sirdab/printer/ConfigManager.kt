package co.sirdab.printer

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists printer and label configuration across app restarts.
 *
 * All values can be overridden per-request via the /print endpoint,
 * or updated permanently via POST /config.
 *
 * Default label size matches the GS-2406T with standard 4"×6" gap labels.
 */
class ConfigManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Printer connection ────────────────────────────────────────────────────

    /** IP address of the GAINSCHA GS-2406T on the local WiFi network. */
    var printerIp: String
        get() = prefs.getString(KEY_PRINTER_IP, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PRINTER_IP, value).apply()

    /** TCP port the printer listens on (GAINSCHA default: 8899). */
    var printerPort: Int
        get() = prefs.getInt(KEY_PRINTER_PORT, 8899)
        set(value) = prefs.edit().putInt(KEY_PRINTER_PORT, value).apply()

    // ── Label geometry ────────────────────────────────────────────────────────

    /** Label width in mm.  4" = 101.6 mm, rounded up to 102. */
    var labelWidthMm: Int
        get() = prefs.getInt(KEY_LABEL_WIDTH_MM, 102)
        set(value) = prefs.edit().putInt(KEY_LABEL_WIDTH_MM, value).apply()

    /** Label height in mm.  6" = 152.4 mm, rounded up to 152. */
    var labelHeightMm: Int
        get() = prefs.getInt(KEY_LABEL_HEIGHT_MM, 152)
        set(value) = prefs.edit().putInt(KEY_LABEL_HEIGHT_MM, value).apply()

    /**
     * Gap between labels in mm.  Depends on the physical label roll.
     * Typical value: 2–3 mm.  If labels misfeed, adjust this first.
     */
    var gapMm: Int
        get() = prefs.getInt(KEY_GAP_MM, 3)
        set(value) = prefs.edit().putInt(KEY_GAP_MM, value).apply()

    // ── Print quality ─────────────────────────────────────────────────────────

    /**
     * Print speed (1–15 inches/sec).
     * Lower = darker and more reliable on worn-out ribbon/head.
     * 4 is a safe default for dark-store environments.
     */
    var printSpeed: Int
        get() = prefs.getInt(KEY_PRINT_SPEED, 4)
        set(value) = prefs.edit().putInt(KEY_PRINT_SPEED, value.coerceIn(1, 15)).apply()

    /**
     * Print density / darkness (0–15).
     * Higher = darker print.  8 is the midpoint; bump to 10–12 if barcodes
     * scan poorly or text is faint.
     */
    var printDensity: Int
        get() = prefs.getInt(KEY_PRINT_DENSITY, 8)
        set(value) = prefs.edit().putInt(KEY_PRINT_DENSITY, value.coerceIn(0, 15)).apply()

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns true if a printer IP has been configured. */
    fun isConfigured(): Boolean = printerIp.isNotEmpty()

    /** Serialises the current config for GET /config responses. */
    fun toMap(): Map<String, Any> = mapOf(
        "printer_ip"       to printerIp,
        "printer_port"     to printerPort,
        "label_width_mm"   to labelWidthMm,
        "label_height_mm"  to labelHeightMm,
        "gap_mm"           to gapMm,
        "print_speed"      to printSpeed,
        "print_density"    to printDensity
    )

    companion object {
        private const val PREFS_NAME          = "printer_config"
        private const val KEY_PRINTER_IP      = "printer_ip"
        private const val KEY_PRINTER_PORT    = "printer_port"
        private const val KEY_LABEL_WIDTH_MM  = "label_width_mm"
        private const val KEY_LABEL_HEIGHT_MM = "label_height_mm"
        private const val KEY_GAP_MM          = "gap_mm"
        private const val KEY_PRINT_SPEED     = "print_speed"
        private const val KEY_PRINT_DENSITY   = "print_density"
    }
}

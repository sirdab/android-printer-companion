package co.sirdab.printer

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Polls the GitHub Releases API for the latest published release and reports
 * whether it is newer than the installed app.
 *
 * Auth-free — works against the public sirdab/android-printer-companion repo.
 * Subject to the 60 req/hour anonymous GitHub rate limit, which is far above
 * what this app will ever send (one check per service start + every 6 hours).
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val API_URL =
        "https://api.github.com/repos/sirdab/android-printer-companion/releases/latest"

    data class Release(
        val tag: String,            // e.g. "v1.0.2"
        val versionName: String,    // e.g. "1.0.2"
        val apkUrl: String
    )

    fun fetchLatest(): Release? {
        return try {
            val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout    = 10_000
                setRequestProperty("Accept",     "application/vnd.github+json")
                setRequestProperty("User-Agent", "sirdab-printer-companion")
            }
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "GitHub releases API returned HTTP $code")
                conn.disconnect()
                return null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json = JSONObject(body)
            val tag    = json.getString("tag_name")
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name").endsWith(".apk", ignoreCase = true)) {
                    return Release(
                        tag         = tag,
                        versionName = tag.removePrefix("v"),
                        apkUrl      = asset.getString("browser_download_url")
                    )
                }
            }
            Log.w(TAG, "Latest release $tag has no APK asset")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            null
        }
    }

    /**
     * Compares dotted version strings as integers per component.
     * `1.0.10` is correctly newer than `1.0.9`.
     */
    fun isNewer(current: String, latest: String): Boolean {
        val a = current.split(".").map { it.toIntOrNull() ?: 0 }
        val b = latest.split(".").map  { it.toIntOrNull() ?: 0 }
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val ai = a.getOrElse(i) { 0 }
            val bi = b.getOrElse(i) { 0 }
            if (bi != ai) return bi > ai
        }
        return false
    }
}

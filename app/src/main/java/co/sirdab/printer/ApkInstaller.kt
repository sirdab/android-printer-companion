package co.sirdab.printer

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads an APK from a URL and triggers Android's system install dialog
 * via FileProvider. The user must tap "Install" once on the system dialog —
 * Android does not allow regular apps to install silently.
 *
 * On Android 8+ the source app must hold REQUEST_INSTALL_PACKAGES *and* the
 * user must have granted "Install unknown apps" for it. If the latter is
 * missing, Android redirects to the settings page on first attempt.
 */
object ApkInstaller {

    private const val TAG = "ApkInstaller"
    private const val APK_FILENAME = "update.apk"

    fun downloadAndInstall(context: Context, apkUrl: String): Boolean {
        return try {
            val outFile = File(context.filesDir, APK_FILENAME).apply {
                if (exists()) delete()
            }

            val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout          = 10_000
                readTimeout             = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "sirdab-printer-companion")
            }
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "APK download failed: HTTP $code")
                conn.disconnect()
                return false
            }
            conn.inputStream.use { input ->
                outFile.outputStream().use { input.copyTo(it) }
            }
            conn.disconnect()
            Log.i(TAG, "APK downloaded (${outFile.length()} bytes) — launching install")

            val authority = "${context.packageName}.fileprovider"
            val uri       = FileProvider.getUriForFile(context, authority, outFile)
            val intent    = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "APK install failed", e)
            false
        }
    }
}

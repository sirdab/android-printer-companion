package co.sirdab.printer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads an APK from a URL and installs it via Android's PackageInstaller
 * API (the system installer service), avoiding the legacy ACTION_VIEW path
 * that gets dispatched to Play Services / Play Store on modern Android.
 *
 * The user still sees the system install-confirmation dialog (REQUEST_INSTALL_PACKAGES
 * is granted but we lack privileged INSTALL_PACKAGES). They tap Install — the
 * actual install runs in the system_server, completely outside Play Services.
 */
object ApkInstaller {

    private const val TAG = "ApkInstaller"
    private const val APK_FILENAME   = "update.apk"
    private const val INSTALL_ACTION = "co.sirdab.printer.INSTALL_RESULT"

    fun downloadAndInstall(context: Context, apkUrl: String): Boolean {
        return try {
            val apkFile = File(context.filesDir, APK_FILENAME).apply {
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
                apkFile.outputStream().use { input.copyTo(it) }
            }
            conn.disconnect()
            Log.i(TAG, "APK downloaded (${apkFile.length()} bytes)")

            commitInstallSession(context, apkFile)
            true
        } catch (e: Exception) {
            Log.e(TAG, "APK install failed", e)
            false
        }
    }

    /**
     * Streams the APK into a PackageInstaller session and commits it.
     * The system replies asynchronously to [InstallResultReceiver] — typically
     * with STATUS_PENDING_USER_ACTION, which holds the system install dialog
     * intent that the receiver then launches.
     */
    private fun commitInstallSession(context: Context, apkFile: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(context.packageName)
        }
        val sessionId = installer.createSession(params)

        installer.openSession(sessionId).use { session ->
            apkFile.inputStream().use { input ->
                session.openWrite(APK_FILENAME, 0, apkFile.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }

            val callback = Intent(INSTALL_ACTION).setPackage(context.packageName)
            val flags    = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    PendingIntent.FLAG_MUTABLE else 0
            val pending  = PendingIntent.getBroadcast(context, sessionId, callback, flags)

            session.commit(pending.intentSender)
            Log.i(TAG, "Install session $sessionId committed — awaiting user confirmation")
        }
    }
}

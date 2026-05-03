package co.sirdab.printer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log

/**
 * Handles async results from PackageInstaller sessions.
 *
 * The status we always expect first is STATUS_PENDING_USER_ACTION — the system
 * is asking us to launch the install confirmation dialog Activity. We pull
 * EXTRA_INTENT out of the broadcast and start it.
 *
 * After the user taps Install (or Cancel), we get a second broadcast with the
 * final status (SUCCESS / FAILURE / CONFLICT / ABORTED / etc.) which we just log.
 */
class InstallResultReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "InstallResultReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status  = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
                }
                if (confirmIntent == null) {
                    Log.w(TAG, "Pending user action but no EXTRA_INTENT")
                    return
                }
                confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirmIntent)
                Log.i(TAG, "Launched system install confirmation")
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Install successful")
            }

            else -> {
                Log.w(TAG, "Install ended with status=$status — $message")
            }
        }
    }
}

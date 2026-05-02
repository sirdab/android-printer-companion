package co.sirdab.printer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Starts the printer companion automatically after device boot.
 *
 * Two-stage start:
 *  1. Start KeepAliveService as a foreground service — this is always permitted
 *     from a BOOT_COMPLETED receiver and keeps the process alive.
 *  2. Attempt to start MainActivity — permitted on most Android versions from
 *     a BOOT_COMPLETED receiver, though some OEM firmware (especially tablets
 *     running Fully Kiosk) may suppress it.  If the activity doesn't start
 *     automatically, store staff can tap the persistent notification.
 *
 * QUICKBOOT_POWERON handles certain Chinese tablet firmware variants that fire
 * a different action on boot (e.g. Rockchip-based devices).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON") {
            return
        }

        Log.i("BootReceiver", "Boot complete — starting printer companion (action=$action)")

        // Step 1: start foreground service (keeps process priority high)
        val serviceIntent = Intent(context, KeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        // Step 2: start the activity (best-effort — may be suppressed on Android 12+
        // without user-level permissions, but works reliably on kiosk firmware)
        try {
            val activityIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(activityIntent)
        } catch (e: Exception) {
            // On some devices/versions this throws.  The foreground service above
            // ensures the process stays alive; staff can tap the notification to
            // bring up the activity when needed.
            Log.w("BootReceiver", "Could not auto-start MainActivity: ${e.message}")
        }
    }
}

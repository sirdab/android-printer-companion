package co.sirdab.printer

import android.content.Context
import com.gainscha.gtspl_sdk.GTSPLWIFIActivity

/**
 * A GTSPLWIFIActivity subclass instantiated as a plain object (not through
 * the Android Activity system).
 *
 * The GAINSCHA SDK stores all printer state (socket, streams, buffers) as
 * instance fields on GTSPLWIFIActivity, and its GTSPL_* methods only use
 * those fields plus a Context parameter supplied by the caller. They do not
 * call any Activity lifecycle methods or use any Activity-specific APIs.
 *
 * By attaching an ApplicationContext we satisfy any Context needs; the
 * Activity lifecycle is never invoked, so there are no lifecycle side-effects.
 *
 * This lets [KeepAliveService] own a permanent [PrinterClient] without
 * depending on [MainActivity] being alive.
 */
class SdkHost(appContext: Context) : GTSPLWIFIActivity() {
    init {
        attachBaseContext(appContext)
    }
}

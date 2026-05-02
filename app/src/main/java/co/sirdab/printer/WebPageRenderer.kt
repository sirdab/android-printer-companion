package co.sirdab.printer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Renders a URL (HTML or PDF viewer) to a bitmap using an offscreen WebView.
 *
 * Used when the URL returns HTML (e.g. OTO's app.tryoto.com/OTOAWB viewer)
 * rather than a raw PDF file.
 *
 * The WebView must be created and operated on the main thread.
 * A CountDownLatch is used to block the caller's thread until rendering completes.
 *
 * Label dimensions: 4" × 6" at 203 DPI → 812 × 1218 px
 */
class WebPageRenderer(private val context: Context) {

    companion object {
        private const val TAG = "WebPageRenderer"

        private const val LABEL_WIDTH_PX  = 812
        private const val LABEL_HEIGHT_PX = 1218

        /** Max time to wait for the page to finish loading. */
        private const val LOAD_TIMEOUT_SEC = 30L

        /** Extra settle time after onPageFinished (JS may still be rendering). */
        private const val SETTLE_DELAY_MS  = 3000L
    }

    data class RenderedPage(
        val filename:   String,
        val file:       File,
        val widthPx:    Int,
        val widthBytes: Int,
        val heightDots: Int
    )

    /**
     * Loads [url] in an offscreen WebView, waits for it to render,
     * captures the result as a PNG, and returns a single [RenderedPage].
     *
     * Must NOT be called on the main thread.
     */
    fun render(url: String, jobId: String): RenderedPage {
        val latch = CountDownLatch(1)
        var error: String? = null
        var bitmap: Bitmap? = null

        Handler(Looper.getMainLooper()).post {
            setupAndLoad(url, jobId, latch,
                onBitmap = { bitmap = it },
                onError  = { error = it }
            )
        }

        val completed = latch.await(LOAD_TIMEOUT_SEC + (SETTLE_DELAY_MS / 1000) + 5, TimeUnit.SECONDS)
        if (!completed) throw RuntimeException("WebView render timed out for $url")
        if (error != null) throw RuntimeException("WebView render failed: $error")

        val bmp = bitmap ?: throw RuntimeException("WebView produced no bitmap for $url")
        return saveBitmap(bmp, jobId)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupAndLoad(
        url: String,
        jobId: String,
        latch: CountDownLatch,
        onBitmap: (Bitmap) -> Unit,
        onError: (String) -> Unit
    ) {
        val webView = WebView(context)
        webView.settings.apply {
            javaScriptEnabled    = true
            domStorageEnabled    = true
            loadWithOverviewMode = true
            useWideViewPort      = true
        }

        // Fix layout to label size so the capture is the right dimensions
        val widthSpec  = android.view.View.MeasureSpec.makeMeasureSpec(LABEL_WIDTH_PX,  android.view.View.MeasureSpec.EXACTLY)
        val heightSpec = android.view.View.MeasureSpec.makeMeasureSpec(LABEL_HEIGHT_PX, android.view.View.MeasureSpec.EXACTLY)
        webView.measure(widthSpec, heightSpec)
        webView.layout(0, 0, LABEL_WIDTH_PX, LABEL_HEIGHT_PX)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                Log.d(TAG, "Page finished, settling for ${SETTLE_DELAY_MS}ms (job=$jobId)")
                // Wait for JS to finish rendering before capturing
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        val bmp = Bitmap.createBitmap(LABEL_WIDTH_PX, LABEL_HEIGHT_PX, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        val canvas = Canvas(bmp)
                        view.draw(canvas)
                        onBitmap(bmp)
                        Log.d(TAG, "Bitmap captured (job=$jobId)")
                    } catch (e: Exception) {
                        onError("Capture failed: ${e.message}")
                    } finally {
                        webView.destroy()
                        latch.countDown()
                    }
                }, SETTLE_DELAY_MS)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    onError("Page load error: ${error.description}")
                    webView.destroy()
                    latch.countDown()
                }
            }
        }

        webView.loadUrl(url)
        Log.d(TAG, "Loading URL in WebView: $url (job=$jobId)")
    }

    private fun saveBitmap(bitmap: Bitmap, jobId: String): RenderedPage {
        val outputDir = context.getExternalFilesDir(null)
            ?: throw RuntimeException("External files directory unavailable")

        val filename = "awb_${jobId}_web_p0.png"
        val outFile  = File(outputDir, filename)

        FileOutputStream(outFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()

        val widthBytes = (LABEL_WIDTH_PX + 7) / 8

        Log.d(TAG, "Saved WebView render: $filename (${LABEL_WIDTH_PX}×${LABEL_HEIGHT_PX}px, ${widthBytes} bytes/row)")

        return RenderedPage(
            filename   = filename,
            file       = outFile,
            widthPx    = LABEL_WIDTH_PX,
            widthBytes = widthBytes,
            heightDots = LABEL_HEIGHT_PX
        )
    }
}

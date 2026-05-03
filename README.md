# Sirdab Printer Companion

An Android app that runs on warehouse tablets and exposes a local HTTP API for printing AWB shipping labels on a GAINSCHA GS-2406T label printer over WiFi.

Fully Kiosk Browser (running the Sirdab warehouse webapp) calls `http://localhost:8080/print` with a PDF URL — the app downloads the PDF, renders it, and sends it to the printer via the GAINSCHA SDK.

---

## How it works

```
Fully Kiosk Browser          Companion App (localhost:8080)       Printer
─────────────────────        ──────────────────────────────       ────────
POST /print {pdfUrl}  ──▶    Download PDF
                             Render to bitmap (203 DPI)
                             Connect via TCP (WiFi)        ──▶    Print label
         ◀──────────────     { ok: true, pages: 1 }
```

The app runs as a foreground service, auto-starts on boot, and self-heals if the HTTP server or printer connection drops.

---

## Requirements

- Android 5.0+ (API 21), tablet with WiFi
- GAINSCHA GS-2406T label printer on the same WiFi network
- Fully Kiosk Browser (kiosk mode) for the warehouse webapp
- GAINSCHA SDK AAR: `Android_GPL_AARv2.2.14.aar` (place in `app/libs/`)

---

## Build

### Debug APK

```bash
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

### Release APK

Signing credentials are read from `keystore.properties` (not committed — see below).

```bash
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

---

## Signing setup

Create `keystore.properties` in the project root (gitignored):

```properties
storeFile=sirdab-printer.jks
storePassword=<password>
keyAlias=sirdab-printer
keyPassword=<password>
```

Place `sirdab-printer.jks` in the `app/` directory. Back up both files securely — losing the keystore means you cannot issue updates to existing installations.

---

## First-time tablet setup

Each tablet connects to a different printer, so the printer IP is configured
directly on the device. This only needs to be done once per tablet.

1. Enable USB debugging: **Settings → About tablet** → tap **Build number** 7 times → **Settings → Developer options** → enable **USB debugging**
2. Connect via USB, then run: `adb install app-release.apk`
3. Open the app once (required by Android to exit the "stopped state" so boot auto-start works)
4. Tap **Allow** on the battery optimisation prompt
5. Enter the printer IP (the IP of the GAINSCHA printer on the local WiFi) → tap **Save**
6. Turn off USB debugging: **Settings → Developer options** → disable **USB debugging** (or toggle Developer options off entirely)
7. From this point the app auto-starts on reboot and requires no further interaction

The printer IP is persisted in SharedPreferences and survives reboots and APK updates.

### Remote configuration via curl

Advanced settings (label dimensions, print speed, density) can be pushed
without touching the tablet UI — useful for bulk adjustments across tablets:

```bash
curl -X POST http://<tablet-ip>:8080/config \
  -H "Content-Type: application/json" \
  -d '{
    "printer_port":    8899,
    "label_width_mm":  102,
    "label_height_mm": 152,
    "gap_mm":          3,
    "print_speed":     4,
    "print_density":   8
  }'
```

---

## HTTP API

All endpoints are on `localhost:8080`. All responses are JSON with CORS headers so `fetch()` from Fully Kiosk Browser works without restriction.

### `POST /print`

Submit a print job.

**Request body:**
| Field | Type | Required | Description |
|---|---|---|---|
| `pdfUrl` | string | ✓ | URL of the PDF to print |
| `printerIp` | string | | Overrides stored config for this job |
| `printerPort` | int | | Overrides stored config for this job |
| `copies` | int | | Copies per page (default 1, max 10) |

**Success `200`:**
```json
{ "ok": true, "pages": 1 }
```

**Error responses:**

| Status | `error` code | Cause |
|---|---|---|
| 400 | `missing_pdfUrl` | `pdfUrl` not provided |
| 422 | `pdf_error` | PDF could not be downloaded or rendered |
| 503 | `printer_unreachable` | Cannot connect to printer after 3 attempts |
| 503 | `printer_error` | Printer reported hardware error (paper jam, out of paper, etc.) |
| 504 | `job_timeout` | Job exceeded 90s timeout |

### `GET /status`

Check printer state without printing.

**Response `200`:**
```json
{
  "ok": true,
  "configured": true,
  "printer": {
    "state": "ready",
    "description": "Ready",
    "raw": "00"
  }
}
```

Possible `state` values: `ready`, `printing`, `paused`, `out_of_paper`, `out_of_ribbon`, `paper_jam`, `head_open`, `unreachable`, `not_configured`, `error`, `unknown`.

### `GET /config`

Read current configuration.

### `POST /config`

Update configuration (partial updates supported — only supplied fields are changed). See fields above in the curl example.

### `GET /health`

Liveness probe. Returns immediately without touching the printer. Used by the internal watchdog.

```json
{ "ok": true, "service": "printer-companion", "version": "1.0.0" }
```

---

## Configuration defaults

| Setting | Default | Notes |
|---|---|---|
| `printer_port` | 8899 | GAINSCHA default TCP port |
| `label_width_mm` | 102 | 4" label (101.6 mm) |
| `label_height_mm` | 152 | 6" label (152.4 mm) |
| `gap_mm` | 3 | Gap between labels — adjust if labels misfeed |
| `print_speed` | 4 | 1–15 ips. Lower = darker, more reliable |
| `print_density` | 8 | 0–15. Increase to 10–12 if barcodes scan poorly |

---

## Architecture

```
MainActivity (extends GTSPLWIFIActivity)
│   Hosts the HTTP server and printer SDK instance
│   WeakReference exposed to KeepAliveService for watchdog callbacks
│
├── PrintHttpServer (NanoHTTPD, port 8080)
│   Parses incoming requests and delegates to PrinterClient
│
├── PrinterClient
│   Single-thread executor serialises print jobs
│   Downloads PDF → renders to bitmap → sends via GAINSCHA SDK
│   3× WiFi connection retry with 1.5s back-off
│
├── PdfPageRenderer
│   Uses Android PdfRenderer to rasterise PDF pages at 203 DPI
│   Falls back to WebPageRenderer (WebView) if URL returns HTML
│
├── ConfigManager
│   SharedPreferences-backed config (printer IP, label size, speed, density)
│
├── KeepAliveService (foreground service, START_STICKY)
│   Holds process priority high so Android doesn't kill the activity
│   HTTP watchdog: pings /health every 30s, restarts server on failure
│   onTaskRemoved: restarts MainActivity if task is swiped away
│
└── BootReceiver
    Starts KeepAliveService and MainActivity on device boot
```

---

## Reliability features

| Mechanism | What it protects against |
|---|---|
| Foreground service (`START_STICKY`) | Android killing the process under memory pressure |
| `BootReceiver` | Tablet reboot |
| `android:excludeFromRecents` | Accidental swipe-to-close from recents |
| `onTaskRemoved` in service | Task removal on firmware that ignores `excludeFromRecents` |
| `onBackPressed` → `moveTaskToBack` | Back button destroying the activity |
| HTTP watchdog (30s interval) | HTTP server silently dying |
| WiFi connect retry (3×, 1.5s) | Transient WiFi disconnects during printing |
| Battery optimisation exemption | Doze mode / OEM power manager suspending the watchdog |

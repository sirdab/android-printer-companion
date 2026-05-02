# Sirdab Printer Companion Android App — Manual Test Plan

**Version:** 1.0  
**Date:** 2026-05-03  
**Author:** Development Team  
**Status:** Ready for testing

---

## Table of Contents

1. [Test Environment](#test-environment)
2. [Pass Criteria](#pass-criteria)
3. [Initial Setup](#initial-setup)
4. [Happy Path Printing](#happy-path-printing)
5. [Printer Error States](#printer-error-states)
6. [PDF / URL Edge Cases](#pdf--url-edge-cases)
7. [Network Scenarios](#network-scenarios)
8. [App Lifecycle](#app-lifecycle)
9. [Configuration](#configuration)
10. [API Contract](#api-contract)

---

## Test Environment

### Hardware Requirements

- **Tablet:** Android tablet running Android 8.0+
- **Printer:** GAINSCHA GS-2406T label printer
- **Network:** WiFi network with stable connection (2.4 GHz or 5 GHz)
- **Testing Machine:** Desktop/laptop with Rails app capable of sending POST requests to `http://<tablet-ip>:8080`

### Software Requirements

- **App:** Sirdab Printer Companion (latest build)
- **Kiosk Browser:** Fully Kiosk Browser v1.25+
- **Printer Firmware:** Latest stable version
- **Test Tools:** `curl` or Postman for API testing; PDF viewer for output inspection

### Network Setup

- Tablet and printer on same subnet (or accessible WiFi network)
- Printer IP address known and reachable
- Port 8899 (TCP) open between tablet and printer for label printing
- Port 8080 open for app HTTP API (localhost testing) or accessible from test client
- WiFi router in test environment (or use mobile hotspot for isolation testing)

### Pre-Test Checklist

- [ ] Tablet charged to 100%
- [ ] Printer powered on and warmed up
- [ ] Printer has label stock loaded (4x6 standard)
- [ ] WiFi network stable (ping test to gateway)
- [ ] App freshly installed or reset to default state
- [ ] Rails app running and accessible for sending requests
- [ ] USB cable available for ADB debugging if needed
- [ ] Clear workspace for 50+ printed labels

---

## Pass Criteria

### Release Readiness

The app is **ready to release to production** when ALL of the following are met:

1. **Critical Tests (P0):** All TC-001 through TC-025 pass without failure
2. **Functional Tests (P1):** All TC-026 through TC-045 pass; any P1 failures must be documented as known issues with workarounds
3. **Edge Case Tests (P2):** TC-046 through TC-060 pass at 80% or higher; P2 failures may be deferred to next sprint
4. **API Contract (P1):** All TC-061 through TC-068 pass (JSON/CORS integrity required)
5. **No Critical Bugs:**
   - No unexpected app crashes during test runs
   - No data loss during app lifecycle transitions
   - No printer communication failures in normal WiFi conditions
6. **Device Compatibility:**
   - App tested on minimum 2 different Android tablets (different vendors/OS versions)
   - App tested in Fully Kiosk Browser mode

### Test Coverage by Priority

| Priority | Count | Pass Threshold |
|----------|-------|-----------------|
| P0 (Critical) | 25 | 100% |
| P1 (High) | 21 | 100% |
| P2 (Medium) | 14 | 80% |
| **Total** | **60** | **95%+** |

---

## 1. Initial Setup

### TC-001: Fresh Install — App Launches Successfully

**Preconditions:**
- Device has app uninstalled
- Device is powered on, Android 8.0+
- Device is on WiFi network

**Steps:**
1. Install app from APK
2. Tap app icon to launch
3. Grant any requested Android permissions (camera, location, storage if prompted)
4. Observe startup screen and main UI

**Expected Result:**
- App launches without crash
- If battery optimization prompt shown, user can dismiss it
- Main screen displays with printer configuration UI visible
- No error dialogs

**Notes:**
- Battery optimization prompt is expected on first launch (Android will warn about background services)
- App should not crash on permission denial — should gracefully handle

**Priority:** P0

---

### TC-002: Printer IP Configuration via UI

**Preconditions:**
- App launched successfully (TC-001 passed)
- Printer IP address known (e.g., `192.168.1.100`)
- Printer on same WiFi network as tablet

**Steps:**
1. Locate printer IP input field on main screen
2. Clear any existing value
3. Enter printer IP address (e.g., `192.168.1.100`)
4. Tap "Save" or "Connect" button
5. Observe status feedback

**Expected Result:**
- Printer IP accepted and stored
- Status message shows "Connected" or similar success indication
- Subsequent /status requests to printer return success
- IP persists after app restart

**Notes:**
- Watch for any validation errors (should accept valid IP format)
- If printer unreachable, expect timeout error (not crash)

**Priority:** P0

---

### TC-003: Printer IP Configuration via POST /config

**Preconditions:**
- App running on `http://<tablet-ip>:8080`
- Tablet on WiFi with known IP
- Printer IP known

**Steps:**
1. Open terminal/Postman on test machine
2. Send POST request:
   ```bash
   curl -X POST http://<tablet-ip>:8080/config \
     -H "Content-Type: application/json" \
     -d '{"printerIp":"192.168.1.100","labelWidth":4,"labelHeight":6}'
   ```
3. Verify response JSON
4. Check app UI — verify IP displayed matches config sent

**Expected Result:**
- HTTP 200 response with JSON: `{"status":"ok","printerIp":"192.168.1.100",...}`
- App UI updates to show new IP
- Subsequent prints use new IP address
- GET /config returns matching values

**Notes:**
- API should be lenient: accept requests with missing optional fields
- IP format validation should happen server-side

**Priority:** P0

---

### TC-004: Foreground Service Notification Visible

**Preconditions:**
- App launched and configured (TC-001, TC-002 passed)
- KeepAliveService running (should start on app launch)

**Steps:**
1. Launch app
2. Press home button to minimize app
3. Swipe down to open notification shade
4. Look for "Sirdab Printer" or similar foreground service notification

**Expected Result:**
- Notification visible in notification shade
- Notification shows meaningful text (e.g., "Sirdab: Ready to print" or app name)
- Notification cannot be swiped away by user (pinned)
- Notification has app icon visible

**Notes:**
- Foreground service is required for BootReceiver and watchdog to function
- User cannot dismiss the notification (by design — keeps service alive)
- Notification text may vary but should indicate service is active

**Priority:** P0

---

## 2. Happy Path Printing

### TC-005: Single-Page AWB Label Prints Successfully

**Preconditions:**
- App configured with valid printer IP (TC-002)
- Printer powered on, paper loaded, accessible
- Rails app running locally or on test network

**Steps:**
1. Generate a single-page AWB label PDF (or use test PDF)
2. Send POST /print request from Rails app:
   ```bash
   curl -X POST http://<tablet-ip>:8080/print \
     -H "Content-Type: application/json" \
     -d '{"pdfUrl":"http://railsapp.local/pdfs/test.pdf","copies":1}'
   ```
3. Observe app UI during print (loading bar, status updates)
4. Listen for printer activation (motor sound, print head movement)
5. Collect printed label and inspect

**Expected Result:**
- HTTP 200 response: `{"status":"success","jobId":"<id>"}`
- App shows downloading PDF (if URL is slow)
- App shows "Printing..." status on UI
- Printer activates within 5 seconds
- Label prints completely (no truncation, correct size)
- Print result shown in app UI (success message or notification)

**Notes:**
- First print may take longer if app needs to establish WiFi connection to printer
- Monitor device logs via ADB for any errors: `adb logcat | grep Sirdab`
- Printed label should match PDF dimensions (4x6 inches standard)

**Priority:** P0

---

### TC-006: Multi-Page PDF Prints All Pages

**Preconditions:**
- Printer configured and accessible (TC-005 preconditions)
- Test PDF with 3+ pages available

**Steps:**
1. Send POST /print with multi-page PDF URL:
   ```bash
   curl -X POST http://<tablet-ip>:8080/print \
     -H "Content-Type: application/json" \
     -d '{"pdfUrl":"http://railsapp.local/pdfs/multi-page.pdf","copies":1}'
   ```
2. Monitor printer activity
3. Collect all printed labels
4. Count pages and verify content

**Expected Result:**
- All pages render and print (3 separate labels if 3-page PDF)
- Each page matches PDF content
- No pages skipped or duplicated
- HTTP response indicates success

**Notes:**
- Watch for any pause between pages (normal, up to 2-3 seconds acceptable)
- If PDF is 10 pages, printer may take 30+ seconds (watch for timeout issues in TC-032)

**Priority:** P1

---

### TC-007: Correct Label Dimensions on Printed Output

**Preconditions:**
- Single-page label printed (TC-005 passed)
- Physical ruler or calibrated measurement available
- Test PDF has known dimensions (4x6 inches)

**Steps:**
1. Measure printed label with ruler (length and width)
2. Verify against configuration settings in app
3. Cross-reference with printer specification
4. If dimensions incorrect, adjust label width/height in config (TC-036) and reprint

**Expected Result:**
- Printed label width matches configured width (typically 4 inches)
- Printed label height matches configured height (typically 6 inches)
- Content on label is centered and not cropped
- Margins are minimal (printer fills media)

**Notes:**
- Label dimensions are configurable; test with multiple sizes if needed
- GAINSCHA GS-2406T is 4x6 label native resolution
- Measurement should be within ±0.1 inch tolerance

**Priority:** P1

---

### TC-008: Print Result Shown in App UI

**Preconditions:**
- Print job completed (TC-005 passed)
- App UI accessible and visible

**Steps:**
1. Send POST /print and observe app response
2. Look for success message or result indication (toast, dialog, status bar)
3. Verify message includes job ID or timestamp
4. Send GET request to check job status:
   ```bash
   curl http://<tablet-ip>:8080/status
   ```

**Expected Result:**
- App UI displays success message within 2 seconds of print completion
- Message includes confirmation (e.g., "Label printed successfully" or job ID)
- GET /status returns `{"status":"ready","lastJobStatus":"success"}`
- Result persists on UI until next print (or UI cleared by user)

**Notes:**
- Success message may be visible for 3-5 seconds then fade
- Job status should be retrievable even after app goes to background
- Monitor device logs for any unexpected errors

**Priority:** P1

---

## 3. Printer Error States

### TC-009: Printer Powered Off (Unreachable)

**Preconditions:**
- Printer IP configured correctly
- Printer powered off or disconnected from network

**Steps:**
1. Power off printer (or disconnect from WiFi)
2. Send POST /print request
3. Observe app behavior and response

**Expected Result:**
- HTTP response timeout after ~10 seconds (or error code: 500/504)
- App shows error message: "Printer unreachable" or "Connection timeout"
- Error message displayed on UI (not silent failure)
- App does not crash
- User can retry print after powering printer back on

**Notes:**
- WiFi connection retry logic should attempt 3 times with 1.5s delay between attempts
- Total timeout should be ~90 seconds or less (TC-032 timeout)
- After error, user should be able to power printer back on and retry without restarting app

**Priority:** P1

---

### TC-010: Printer Out of Paper

**Preconditions:**
- Printer configured and network-accessible
- Printer paper tray empty or nearly empty (or simulate via printer UI)

**Steps:**
1. Remove all paper from printer label tray
2. Send POST /print request
3. Observe printer response and app handling

**Expected Result:**
- Printer rejects print job (TCP error or printer status response)
- App returns HTTP 500 with error message containing "paper" or "out of paper"
- App UI shows error: "Printer out of paper" or similar
- No crash; user can recover by loading paper and retrying

**Notes:**
- Printer status query (port 8899) should detect this condition
- Some printers require a "clear error" command after paper jam/out condition
- Test with actual paper out (not simulated via firmware)

**Priority:** P1

---

### TC-011: Printer Paper Jam

**Preconditions:**
- Printer operational and configured
- Manually jam a label in printer mechanism (safe simulation)

**Steps:**
1. Trigger a safe jam condition (partially load label and jam)
2. Send POST /print request
3. Observe app and printer behavior

**Expected Result:**
- Printer does not accept print job (TCP connection fails or status error)
- App returns error: HTTP 500
- App UI displays error message (specific to jam or general "Printer error")
- No crash
- After jam cleared, app can resume printing without restart

**Notes:**
- Do NOT force jam that could damage printer
- Clear jam and verify printer resets before next test
- Some printers may need manual "clear error" on printer display

**Priority:** P1

---

### TC-012: Printer Head Open / Cover Open

**Preconditions:**
- Printer operational and configured
- Ability to open printer cover safely

**Steps:**
1. Open printer cover (or head assembly if accessible)
2. Send POST /print request
3. Observe printer status response

**Expected Result:**
- Printer firmware detects open cover (status/error response)
- App receives error from printer (TCP status byte or response)
- App returns HTTP 500 with error message: "Printer head open" or "Cover open"
- App UI shows user-friendly error message
- App does not crash

**Notes:**
- Cover sensor is typically a mechanical switch in label printer
- Some printers return specific status byte for this condition
- Close cover and retry to verify recovery

**Priority:** P1

---

### TC-013: Printer Paused (Feed Button Pressed)

**Preconditions:**
- Printer configured and powered on
- Access to printer physical buttons

**Steps:**
1. Press printer "Feed" or "Pause" button (if available on GAINSCHA GS-2406T)
2. Verify printer LED or UI shows "paused" state
3. Send POST /print request within 10 seconds

**Expected Result:**
- Printer does not execute print (firmware paused state)
- App receives printer status indicating paused
- App returns HTTP error with message: "Printer paused" or general error
- User can resume printer (press button again) and retry print
- App does not require restart

**Notes:**
- GAINSCHA GS-2406T may not have explicit pause button (check manual)
- Some printers pause via firmware command (check if app should send resume command)
- Verify printer behavior aligns with hardware manual

**Priority:** P1

---

### TC-014: Wrong Printer IP Configured

**Preconditions:**
- App configured with incorrect printer IP (e.g., `192.168.1.200` if printer is at `192.168.1.100`)
- Wrong IP is on same network but points to different device (or is unreachable)

**Steps:**
1. Configure app with wrong printer IP (TC-003)
2. Send POST /print request
3. Observe error handling

**Expected Result:**
- App attempts connection to wrong IP
- Connection timeout or "host unreachable" error after retries
- HTTP 500 response with error message
- App UI shows "Printer unreachable" or "Cannot connect to printer"
- User can correct IP and retry without restart

**Notes:**
- App should validate IP format (no crash on malformed IPs like "invalid_ip")
- Connection should fail fast if IP is clearly unreachable (not on network)
- If wrong IP is a real device, connection may establish but printer commands will fail

**Priority:** P1

---

### TC-015: Wrong TCP Port (Not 8899)

**Preconditions:**
- Printer IP correct but TCP port in app is not 8899 (or manually test with wrong port)
- Printer firmware listens only on port 8899

**Steps:**
1. If app allows port configuration: set port to 9999 (or leave as 8899 if hardcoded)
2. Send POST /print request to printer on wrong port
3. Observe connection failure

**Expected Result:**
- TCP connection fails (port unreachable or timeout)
- App returns HTTP 500: "Cannot connect to printer" or "Connection refused"
- No crash; user can correct port if app allows configuration
- If port is hardcoded, this test may not apply (note in results)

**Notes:**
- GAINSCHA GS-2406T uses port 8899 by standard
- If port is hardcoded in app, document as "N/A" or test by changing printer's listening port (not practical)
- App should timeout quickly if port is wrong (3-5 seconds per retry)

**Priority:** P2

---

## 4. PDF / URL Edge Cases

### TC-016: URL Returns HTML Instead of PDF

**Preconditions:**
- App running on tablet
- Test URL available that serves HTML document (not PDF)

**Steps:**
1. Prepare a test HTML file served from Rails app (e.g., `/pdfs/fake.html` returns HTML)
2. Send POST /print with HTML URL:
   ```bash
   curl -X POST http://<tablet-ip>:8080/print \
     -H "Content-Type: application/json" \
     -d '{"pdfUrl":"http://railsapp.local/pdfs/fake.html"}'
   ```
3. Observe app handling and printer response

**Expected Result:**
- App downloads content and detects it is not PDF (MIME type check or PDF magic bytes)
- App returns HTTP 400 or 422 with error: "Invalid PDF content" or "File is not a PDF"
- If WebView fallback is implemented: app attempts to render HTML as fallback (platform feature, document if present)
- App does not crash; user can retry with valid PDF URL

**Notes:**
- PDF validation should check Content-Type header and file magic bytes
- WebView fallback (if present) may attempt to print HTML directly (platform-dependent)
- Document whether app has fallback or strict validation

**Priority:** P1

---

### TC-017: Unreachable URL (404, 404, Timeout)

**Preconditions:**
- App configured with valid printer IP
- Test URLs available: 
  - `http://railsapp.local/pdfs/notfound.pdf` (404)
  - `http://invalid.unreachable.local/pdf.pdf` (DNS failure)
  - `http://railsapp.local:9999/pdf.pdf` (connection timeout)

**Steps:**
1. Test 404 error:
   ```bash
   curl -X POST http://<tablet-ip>:8080/print \
     -H "Content-Type: application/json" \
     -d '{"pdfUrl":"http://railsapp.local/pdfs/notfound.pdf"}'
   ```
2. Test DNS failure:
   ```bash
   curl -X POST http://<tablet-ip>:8080/print \
     -H "Content-Type: application/json" \
     -d '{"pdfUrl":"http://invalid.unreachable.local/test.pdf"}'
   ```
3. Test timeout (use port that's not listening):
   ```bash
   curl -X POST http://<tablet-ip>:8080/print \
     -H "Content-Type: application/json" \
     -d '{"pdfUrl":"http://railsapp.local:9999/test.pdf"}'
   ```
4. Observe app error handling

**Expected Result:**
- All three scenarios return HTTP 400 or 500 with descriptive error message
- Error messages should help user debug:
  - "File not found (404)"
  - "Cannot reach server (DNS failure)" or "Unknown host"
  - "Connection timeout — server may be unreachable"
- App UI shows error without crashing
- User can retry with corrected URL

**Notes:**
- Use reasonable timeout for URL fetch (suggest 30 seconds max)
- DNS errors should be caught separately from timeout errors
- 404 is HTTP error, not network error — app should differentiate

**Priority:** P1

---

### TC-018: Corrupted or Malformed PDF

**Preconditions:**
- Test PDF file available that is truncated or corrupted (e.g., last page missing, headers malformed)

**Steps:**
1. Serve corrupted PDF from Rails app
2. Send POST /print with corrupted PDF URL:
   ```bash
   curl -X POST http://<tablet-ip>:8080/print \
     -H "Content-Type: application/json" \
     -d '{"pdfUrl":"http://railsapp.local/pdfs/corrupted.pdf"}'
   ```
3. Observe app behavior during rendering/printing

**Expected Result:**
- App downloads corrupted PDF
- PDF rendering fails (PDFRenderer or similar library throws error)
- App returns HTTP 500: "Failed to render PDF" or "Corrupted PDF file"
- App does not crash
- User can report issue or retry with different PDF
- No partial output to printer (should fail before sending)

**Notes:**
- Corrupted PDF test verifies error handling in PDF rendering logic
- Watch device logs for exception stack trace
- Some corrupted PDFs may partially render (document observed behavior)

**Priority:** P2

---

### TC-019: Very Large PDF (10+ Pages)

**Preconditions:**
- Test PDF with 10-20 pages available (e.g., multi-page order manifest)
- Printer has sufficient label stock

**Steps:**
1. Send POST /print with 10-page PDF:
   ```bash
   curl -X POST http://<tablet-ip>:8080/print \
     -H "Content-Type: application/json" \
     -d '{"pdfUrl":"http://railsapp.local/pdfs/10page.pdf"}'
   ```
2. Monitor app UI during download and rendering
3. Observe printer output (all 10 pages should print)
4. Check for memory leaks or slowdown

**Expected Result:**
- App downloads large PDF successfully
- PDF rendering completes (may take 10-20 seconds for 10 pages)
- All 10 pages print to printer without truncation
- App does not crash or show out-of-memory error
- Printing completes within 90-second job timeout (TC-032)

**Notes:**
- Large PDFs may consume significant RAM; monitor via ADB memory profile if concerned
- Each page = 1 label printed, so 10 pages = ~30-40 seconds total print time
- If pages are skipped or truncated, debug PDF rendering pipeline

**Priority:** P2

---

### TC-020: Multiple Copies (1, 5, 10 Copies)

**Preconditions:**
- Single-page PDF available
- Printer stock available (50+ labels for full test)

**Steps:**
1. Test 1 copy:
   ```bash
   curl -X POST http://<tablet-ip>:8080/print \
     -H "Content-Type: application/json" \
     -d '{"pdfUrl":"http://railsapp.local/pdfs/test.pdf","copies":1}'
   ```
2. Test 5 copies:
   ```bash
   curl -X POST http://<tablet-ip>:8080/print \
     -H "Content-Type: application/json" \
     -d '{"pdfUrl":"http://railsapp.local/pdfs/test.pdf","copies":5}'
   ```
3. Test 10 copies:
   ```bash
   curl -X POST http://<tablet-ip>:8080/print \
     -H "Content-Type: application/json" \
     -d '{"pdfUrl":"http://railsapp.local/pdfs/test.pdf","copies":10}'
   ```
4. Collect printed labels and count

**Expected Result:**
- 1 copy: 1 label printed
- 5 copies: 5 identical labels printed
- 10 copies: 10 identical labels printed
- All labels identical (same content, orientation, quality)
- Print completes within job timeout
- App returns success for all

**Notes:**
- If copies parameter is missing from request, app should default to 1 (or fail with error)
- Copies > 100 should be rejected or warned (resource limit)
- Verify copies parameter is passed correctly from Rails to Android app

**Priority:** P1

---

### TC-021: Missing pdfUrl in Request Body

**Preconditions:**
- App running on tablet

**Steps:**
1. Send POST /print without pdfUrl:
   ```bash
   curl -X POST http://<tablet-ip>:8080/print \
     -H "Content-Type: application/json" \
     -d '{"copies":1}'
   ```
2. Observe app response

**Expected Result:**
- HTTP 400 (Bad Request) response
- JSON error message: `{"error":"Missing required parameter: pdfUrl"}`
- App does not crash
- Printer is not contacted

**Notes:**
- pdfUrl is required; missing parameter should be rejected immediately
- Validate all required fields in request parsing

**Priority:** P1

---

## 5. Network Scenarios

### TC-022: WiFi Drops Between Request and Print Start

**Preconditions:**
- App configured with valid printer IP
- WiFi network accessible (lab network or controlled hotspot)
- Printer on same WiFi

**Steps:**
1. Send POST /print request
2. Within 1-2 seconds (before printer receives command), disconnect tablet from WiFi:
   - Settings > WiFi > tap network > "Forget"
   - Or disable WiFi toggle
3. Observe app behavior and timeout handling

**Expected Result:**
- App detects connection loss mid-print
- HTTP response timeout (not immediate crash)
- App returns HTTP 500: "Network error" or "Printer unreachable"
- App UI shows error message (not silent failure)
- User can reconnect WiFi and retry print
- Printer does not attempt partial print

**Notes:**
- Catch the window carefully (1-2 second window before printer command sent)
- Use ADB logs to verify app detects network loss: `adb logcat | grep -i "network\|wifi"`
- After reconnecting WiFi, user should be able to retry without app restart

**Priority:** P1

---

### TC-023: WiFi Drops Mid-Print

**Preconditions:**
- App configured with printer IP
- 3+ page PDF available (so print takes 20+ seconds)
- WiFi network controllable

**Steps:**
1. Send POST /print with 3-page PDF
2. Wait 5 seconds (printer should be mid-print)
3. Disconnect WiFi (same method as TC-022)
4. Observe printer and app

**Expected Result:**
- Printer may print partial pages (depends on buffering in printer firmware)
- App detects connection loss
- App returns error or partial success (document behavior)
- App does not crash
- After WiFi reconnects, user can retry (will re-print full PDF)

**Notes:**
- Printer firmware may have internal buffer; prints may continue for a few seconds after disconnect
- Observe actual printer output (may print all, partial, or none of remaining pages)
- This test verifies graceful degradation, not prevention of error

**Priority:** P1

---

### TC-024: Slow Network (PDF Download Takes >10s)

**Preconditions:**
- Network simulation tool available (tc command on Linux, NetLimiter on Windows, or network throttling in browser dev tools)
- Rails app serving test PDF

**Steps:**
1. Enable network throttle to 1 Mbps bandwidth limit (or use large file + slow server):
   ```bash
   # On Linux with tc:
   sudo tc qdisc add dev wlan0 root tbf rate 1mbit burst 32kbit latency 400ms
   ```
2. Send POST /print with PDF URL
3. Observe app UI during slow download (10-20 seconds to download)
4. Verify printing occurs after download completes

**Expected Result:**
- App shows downloading PDF (progress indicator on UI)
- Download takes 10-20 seconds
- After download completes, rendering and printing proceed normally
- App does not timeout before PDF is fully downloaded
- PDF is rendered and printed correctly despite slow download

**Notes:**
- Default HTTP timeout should be 30-60 seconds (allow slow networks)
- Monitor app logs for any warnings or errors: `adb logcat | grep Sirdab`
- Verify user can see that download is in progress (UX feedback)

**Priority:** P1

---

### TC-025: Concurrent Print Requests (Second Request While First is Printing)

**Preconditions:**
- App configured with valid printer IP
- 3-page PDF available (print takes ~20 seconds)

**Steps:**
1. Send POST /print with 3-page PDF:
   ```bash
   curl -X POST http://<tablet-ip>:8080/print \
     -H "Content-Type: application/json" \
     -d '{"pdfUrl":"http://railsapp.local/pdfs/test.pdf"}' &
   ```
2. Wait 2 seconds
3. Send second POST /print request immediately:
   ```bash
   curl -X POST http://<tablet-ip>:8080/print \
     -H "Content-Type: application/json" \
     -d '{"pdfUrl":"http://railsapp.local/pdfs/test.pdf"}'
   ```
4. Observe app and printer behavior

**Expected Result:**
- First request succeeds and begins printing
- Second request returns HTTP 423 (Locked) or 409 (Conflict): "Printer busy, please wait"
- OR second request is queued and prints after first completes (if app implements queue)
- Printer does not receive garbled or mixed data
- Both prints complete successfully (either sequentially or with error on second)
- App does not crash

**Notes:**
- App should implement request queueing or locking to prevent concurrent printer access
- Document whether app queues requests or rejects with error
- Verify no data corruption in either case

**Priority:** P1

---

### TC-026: Printer on Different Subnet / VLAN

**Preconditions:**
- Tablet and printer on different subnets (if lab network supports VLAN)
- Network routing enabled between subnets
- Printer IP known on other subnet

**Steps:**
1. Configure tablet WiFi to one subnet (e.g., 192.168.1.x)
2. Printer on different subnet (e.g., 192.168.2.x or 10.0.0.x)
3. Verify routing between subnets works (ping test)
4. Configure app with printer IP on other subnet
5. Send POST /print

**Expected Result:**
- If routing allows: print succeeds (same behavior as single subnet)
- If routing blocked: connection timeout with error message
- App handles both cases gracefully (does not crash)

**Notes:**
- This test is optional if multi-subnet testing not available in lab
- VLANs may isolate devices; verify network setup before test
- Document whether app is expected to support multi-subnet or single-subnet only

**Priority:** P2

---

## 6. App Lifecycle

### TC-027: Print While App in Background (Fully Kiosk in Foreground)

**Preconditions:**
- App launched and configured
- Foreground service running (TC-004)
- Fully Kiosk Browser running (simulating warehouse kiosk mode)

**Steps:**
1. Ensure app is configured with valid printer IP
2. Press home button to minimize app (Fully Kiosk takes foreground)
3. From Rails app or curl, send POST /print request
4. Observe if print completes despite app not visible

**Expected Result:**
- Print request received and processed
- HTTP 200 response returned
- Printer activates and prints label
- Foreground service keeps HTTP server running
- App does not need to be brought to foreground to print

**Notes:**
- This is critical for warehouse use case (app should not require user interaction)
- Verify foreground service actually prevents app from being killed
- Monitor via `adb logcat` to verify no app shutdown: `adb logcat | grep -i "kill\|stop"`

**Priority:** P0

---

### TC-028: Print Immediately After Tablet Reboot (BootReceiver Test)

**Preconditions:**
- App installed with BootReceiver registered in manifest
- BootReceiver should start KeepAliveService on device boot
- Printer accessible after reboot

**Steps:**
1. Reboot tablet (long press power, select restart/reboot)
2. Wait for boot to complete (home screen visible)
3. Do NOT manually launch app
4. From Rails app, send POST /print request within 30 seconds of boot
5. Observe if print succeeds

**Expected Result:**
- BootReceiver triggers on system boot
- KeepAliveService starts automatically (visible in notification shade)
- HTTP server on port 8080 becomes available
- Print request succeeds (HTTP 200, printer prints)
- No manual app launch required

**Notes:**
- Some Android versions may delay BootReceiver for 10-60 seconds after boot
- Verify BootReceiver is declared in AndroidManifest.xml with correct permissions:
  - `android.permission.RECEIVE_BOOT_COMPLETED`
  - `<receiver android:name=".BootReceiver">` with action `android.intent.action.BOOT_COMPLETED`
- If print fails immediately after boot, increase wait time and retry (TC-029)

**Priority:** P0

---

### TC-029: Print After Screen Off for 30+ Minutes (Doze Mode Test)

**Preconditions:**
- App running and configured
- Foreground service visible (TC-004)
- Battery Saver / Doze mode available on device (Android 6.0+)

**Steps:**
1. Launch app and verify configuration
2. Press power button to turn off screen
3. Wait 30 minutes (or trigger Doze mode manually if device supports it):
   - Some devices allow manual Doze trigger via `adb shell dumpsys deviceidle force-idle`
4. While screen is off and device may be in Doze, send POST /print from Rails app
5. Observe if print succeeds

**Expected Result:**
- Even in Doze mode, foreground service keeps app and HTTP server alive
- Print request succeeds (HTTP 200)
- Printer prints label
- App does not crash when waking from Doze

**Notes:**
- Foreground service should have `ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST_FOREGROUND_SERVICE` or similar to be exempt from Doze
- If print fails, verify foreground service configuration
- This test may require 30 minutes; consider scheduling as optional or partial

**Priority:** P2

---

### TC-030: App Not in Recents (excludeFromRecents Check)

**Preconditions:**
- App installed and running
- Manifest has (or should have) `android:excludeFromRecents="true"`

**Steps:**
1. Launch app, verify it runs
2. Press home button to go to home screen
3. Open recent apps (swipe up from home, or recents button)
4. Verify app is NOT listed in recent apps

**Expected Result:**
- App does not appear in recent apps list
- This prevents accidental user selection in kiosk environment
- If manifest does not exclude from recents, document as issue and fix

**Notes:**
- Some Android versions may show app in recents temporarily before clearing
- Manifest entry: `<activity android:excludeFromRecents="true">`
- This is a UX/security feature for kiosk mode (prevents casual interaction)

**Priority:** P2

---

### TC-031: Back Button Doesn't Close App (moveTaskToBack Check)

**Preconditions:**
- App running
- Manifest configured (or should be) to prevent back button closing

**Steps:**
1. Launch app
2. Press device back button
3. Verify app does NOT close and return to home screen

**Expected Result:**
- Back button press has no effect (or moves app to back without closing)
- App remains running and accessible from home screen
- Foreground service continues unaffected
- Print requests continue to work

**Notes:**
- Implementation should use `moveTaskToBack(true)` on back press
- Or override `onBackPressed()` to do nothing
- This prevents accidental app closure in warehouse environment
- Manifest may also use `android:launchMode="singleInstance"` or `singleTask`

**Priority:** P2

---

### TC-032: Print After KeepAliveService Watchdog Restart (Watchdog Test)

**Preconditions:**
- App running with KeepAliveService active
- Watchdog timer configured to restart HTTP server if it crashes (verify in code)

**Steps:**
1. Verify app is running and printing works (send test print)
2. Manually crash HTTP server process (via `adb shell killall -9 <app>` or app logic)
3. Immediately send another POST /print request
4. Observe if watchdog detects crash and restarts server

**Expected Result:**
- Watchdog detects HTTP server crash (if implemented)
- Watchdog restarts HTTP server within 5-10 seconds
- Print request succeeds after restart (may have brief delay)
- App recovers without manual intervention

**Notes:**
- This test verifies reliability feature (watchdog restart)
- If watchdog not implemented, document as missing and update tests
- Verify watchdog is configurable (timeout duration)
- After restart, foreground service notification should still be visible

**Priority:** P2

---

## 7. Configuration

### TC-033: Label Width/Height Changes Take Effect on Next Print

**Preconditions:**
- App running with valid printer configuration
- Test PDF with known dimensions available

**Steps:**
1. Print test PDF with current label dimensions (e.g., 4x6):
   ```bash
   curl -X POST http://<tablet-ip>:8080/print \
     -H "Content-Type: application/json" \
     -d '{"pdfUrl":"http://railsapp.local/pdfs/test.pdf"}'
   ```
2. Collect and measure label
3. Change label width via app UI or POST /config:
   ```bash
   curl -X POST http://<tablet-ip>:8080/config \
     -H "Content-Type: application/json" \
     -d '{"labelWidth":3,"labelHeight":6}'
   ```
4. Print same PDF again
5. Collect and measure new label

**Expected Result:**
- First label printed with original dimensions (4 inches wide, 6 inches tall)
- Config change accepted (HTTP 200)
- Second label printed with new dimensions (3 inches wide, 6 inches tall)
- No app restart required
- Dimension change effective immediately

**Notes:**
- Verify measurement with ruler (±0.1 inch tolerance)
- Label content should adjust to new dimensions (scale or reflow)
- If printer requires specific label dimensions, app should validate and warn

**Priority:** P1

---

### TC-034: Print Speed Change (Verify Slower/Faster Physical Output)

**Preconditions:**
- App configured with printer IP
- GAINSCHA printer supports print speed configuration (check manual)
- Sample labels for visual comparison

**Steps:**
1. Configure print speed to fastest:
   ```bash
   curl -X POST http://<tablet-ip>:8080/config \
     -H "Content-Type: application/json" \
     -d '{"printSpeed":10}'
   ```
2. Print sample label
3. Configure print speed to slowest:
   ```bash
   curl -X POST http://<tablet-ip>:8080/config \
     -H "Content-Type: application/json" \
     -d '{"printSpeed":1}'
   ```
4. Print another sample label
5. Compare output quality

**Expected Result:**
- Fast speed: label prints quickly (5-10 seconds per label), may have slight quality reduction (dotting)
- Slow speed: label prints more slowly (15-20 seconds), higher quality (darker, crisper)
- Both labels readable and usable
- Print speed setting persists in config (GET /config returns current value)

**Notes:**
- Print speed may not be configurable on all GAINSCHA models
- If not supported, document as "N/A" for this printer model
- Verify speed setting is actually sent to printer via TCP command

**Priority:** P1

---

### TC-035: Print Density Change (Verify Darker/Lighter Physical Output)

**Preconditions:**
- App configured with printer IP
- GAINSCHA printer supports print density / darkness setting
- Test labels for visual comparison

**Steps:**
1. Configure print density to maximum:
   ```bash
   curl -X POST http://<tablet-ip>:8080/config \
     -H "Content-Type: application/json" \
     -d '{"printDensity":15}'
   ```
2. Print sample label
3. Configure print density to minimum:
   ```bash
   curl -X POST http://<tablet-ip>:8080/config \
     -H "Content-Type: application/json" \
     -d '{"printDensity":1}'
   ```
4. Print another sample label
5. Compare output darkness

**Expected Result:**
- High density label: very dark, bold text and images
- Low density label: lighter, but still readable
- Both scanned/readable by barcode scanner (if labels contain barcodes)
- Density setting persists in config

**Notes:**
- Density directly affects print quality and thermal head life (higher = faster head wear)
- Typical range 0-15 or 1-8 depending on printer firmware
- If not supported, document as "N/A"

**Priority:** P1

---

### TC-036: Gap Setting (Verify Correct Label Advance)

**Preconditions:**
- App configured with printer IP
- GAINSCHA printer has configurable gap between labels (distance between end of one label and start of next)

**Steps:**
1. Configure gap setting (example: 2mm):
   ```bash
   curl -X POST http://<tablet-ip>:8080/config \
     -H "Content-Type: application/json" \
     -d '{"labelGap":2}'
   ```
2. Print multi-page PDF (3 pages = 3 labels)
3. Collect labels and measure gap between them
4. Adjust gap setting and repeat
5. Verify gap visually changes

**Expected Result:**
- Small gap (1-2mm): labels close together, easy to separate
- Large gap (4-6mm): visible space between labels
- Gap matches configured value (±1mm tolerance)
- Multi-page print respects gap setting for all pages

**Notes:**
- Gap is critical for label peeling in warehouse (too small = hard to separate)
- Printer may auto-detect gap (recommended), but may need manual configuration
- If auto-detected, this test may not apply

**Priority:** P1

---

### TC-037: Config Persists After App Restart

**Preconditions:**
- App running with custom configuration (e.g., custom printer IP, label dimensions)

**Steps:**
1. Configure app with custom values:
   ```bash
   curl -X POST http://<tablet-ip>:8080/config \
     -H "Content-Type: application/json" \
     -d '{"printerIp":"192.168.1.200","labelWidth":3,"labelHeight":5,"printSpeed":8}'
   ```
2. Force close app (Settings > Apps > [App Name] > Force Stop)
3. Relaunch app
4. Verify configuration persists in UI and GET /config endpoint

**Expected Result:**
- App launches with previously saved configuration
- UI displays custom values (printer IP, label dimensions)
- GET /config returns saved values:
   ```json
   {
     "printerIp": "192.168.1.200",
     "labelWidth": 3,
     "labelHeight": 5,
     "printSpeed": 8
   }
   ```
- No values reset to defaults

**Notes:**
- Configuration must be persisted to SharedPreferences or database
- Verify data is not lost on app crash
- Test on multiple app launches to ensure persistence

**Priority:** P0

---

### TC-038: GET /config Returns Current Values

**Preconditions:**
- App running with configuration set (TC-037)

**Steps:**
1. Configure app with known values via POST /config
2. Immediately query GET /config:
   ```bash
   curl http://<tablet-ip>:8080/config
   ```
3. Verify response JSON matches configured values

**Expected Result:**
- HTTP 200 response
- JSON body contains all configuration values:
   ```json
   {
     "printerIp": "192.168.1.100",
     "labelWidth": 4,
     "labelHeight": 6,
     "printSpeed": 5,
     "printDensity": 10,
     "labelGap": 2
   }
   ```
- Values match what was set via POST /config
- No missing or incorrect fields

**Notes:**
- GET /config is used by Rails app to retrieve current config for display/audit
- Ensure all configuration parameters are returned (not just some)
- Document which parameters are optional vs required

**Priority:** P1

---

## 8. App Lifecycle (Continuation)

### TC-039: Job Timeout — Print Fails Gracefully After 90 Seconds

**Preconditions:**
- App configured with printer IP
- Network delay simulator available (slow PDF download or network throttle)

**Steps:**
1. Enable network throttle to very slow (e.g., 1 kbps) to simulate long PDF download
2. Send POST /print request
3. Wait 90+ seconds without touching device
4. Observe app and API response

**Expected Result:**
- App waits up to 90 seconds for print to complete (including download, render, printer)
- After 90 seconds, app times out and returns HTTP 500 or 504
- Error message: "Print job timeout" or "Request timeout"
- App does not crash or hang indefinitely
- User can retry with different PDF or verify network

**Notes:**
- 90-second timeout is firm deadline (configurable in app, check code)
- Timeout includes:
  - PDF download time
  - PDF render time
  - Printer connection time
  - Actual print time
- Ensure timeout is enforced (use thread.join(timeout) or similar)

**Priority:** P1

---

### TC-040: HTTP 200 Returned Before Print Physically Completes

**Preconditions:**
- Printer configured and accessible
- Multi-page PDF (3+ pages)

**Steps:**
1. Send POST /print with 3-page PDF
2. Observe HTTP response timing
3. Note when HTTP 200 is returned
4. Note when printer physically starts printing
5. Note when printer finishes printing all pages

**Expected Result:**
- HTTP 200 returned within 1-2 seconds (quick acknowledgment)
- Print physically starts within 3-5 seconds
- All pages print sequentially (takes 20+ seconds total)
- HTTP response does NOT wait for physical print to complete
- User receives immediate feedback (jobId) but print continues in background

**Notes:**
- Asynchronous print model: HTTP response quick, print may still be happening
- This is normal and expected (user knows request accepted, print proceeds in background)
- Verify via timestamps in logs: `adb logcat | grep -i "print\|job"`
- Rails app should poll /status to check if print completed (implement in future)

**Priority:** P1

---

## 9. API Contract

### TC-041: All Endpoints Return JSON Content-Type

**Preconditions:**
- App running on tablet with HTTP server listening on 8080

**Steps:**
1. Query all endpoints and inspect Content-Type header:
   ```bash
   curl -i http://<tablet-ip>:8080/health
   curl -i http://<tablet-ip>:8080/status
   curl -i http://<tablet-ip>:8080/config
   curl -i -X POST http://<tablet-ip>:8080/print \
     -H "Content-Type: application/json" \
     -d '{"pdfUrl":"http://test.local/test.pdf"}'
   ```
2. Inspect response headers

**Expected Result:**
- All responses have header: `Content-Type: application/json`
- All responses are valid JSON (parseable)
- No responses return HTML or plain text

**Notes:**
- Header case-insensitive (e.g., `content-type` also acceptable)
- Charset may be included: `application/json; charset=utf-8`
- Error responses should also be JSON (not HTML error pages)

**Priority:** P1

---

### TC-042: CORS Headers Present on All Responses

**Preconditions:**
- App running on tablet
- Test client on different origin (e.g., browser on different IP)

**Steps:**
1. From browser on test machine (different IP), send OPTIONS preflight:
   ```bash
   curl -i -X OPTIONS http://<tablet-ip>:8080/print \
     -H "Origin: http://railsapp.local" \
     -H "Access-Control-Request-Method: POST"
   ```
2. Inspect response headers for CORS headers
3. Repeat for other endpoints

**Expected Result:**
- HTTP 200 response to OPTIONS preflight
- Response headers include:
  - `Access-Control-Allow-Origin: *` (or specific origin)
  - `Access-Control-Allow-Methods: GET, POST, OPTIONS`
  - `Access-Control-Allow-Headers: Content-Type`
- CORS headers present on all endpoints

**Notes:**
- CORS is required for Rails app (browser) to make requests to app on tablet
- If CORS missing, Rails app requests will fail with browser CORS error
- Wildcard `*` is acceptable for warehouse environment (internal network)

**Priority:** P1

---

### TC-043: OPTIONS Preflight Returns 200

**Preconditions:**
- App running

**Steps:**
1. Send OPTIONS request to /print endpoint:
   ```bash
   curl -i -X OPTIONS http://<tablet-ip>:8080/print
   ```
2. Check response status code

**Expected Result:**
- HTTP 200 response
- No request body required for OPTIONS
- Response includes appropriate CORS headers (TC-042)

**Notes:**
- OPTIONS is HTTP standard for CORS preflight
- App should respond quickly to OPTIONS (not perform any logic)

**Priority:** P1

---

### TC-044: Invalid JSON Body Returns 400

**Preconditions:**
- App running on tablet

**Steps:**
1. Send POST /print with malformed JSON:
   ```bash
   curl -X POST http://<tablet-ip>:8080/print \
     -H "Content-Type: application/json" \
     -d '{invalid json}'
   ```
2. Observe response status and body

**Expected Result:**
- HTTP 400 (Bad Request) response
- JSON error message: `{"error":"Invalid JSON: ..."}` or similar
- App does not crash or return 500
- Helpful error message for debugging

**Notes:**
- JSON parsing should fail gracefully
- Validate JSON syntax before processing payload
- Return informative error (helps Rails app debug)

**Priority:** P1

---

### TC-045: Unknown Route Returns 404

**Preconditions:**
- App running on tablet

**Steps:**
1. Send GET request to undefined endpoint:
   ```bash
   curl -i http://<tablet-ip>:8080/nonexistent
   ```
2. Check response status

**Expected Result:**
- HTTP 404 (Not Found) response
- JSON error response (not HTML 404 page)
- Error message: `{"error":"Not Found"}` or `{"error":"Unknown endpoint"}`

**Notes:**
- HTTP framework should handle undefined routes
- Return JSON error (not default HTML 404)
- All error responses should be JSON for API consistency

**Priority:** P1

---

### TC-046: Missing Required Fields in Request Body

**Preconditions:**
- App running

**Steps:**
1. Send POST /print with missing pdfUrl (tested in TC-021, but verify again):
   ```bash
   curl -X POST http://<tablet-ip>:8080/print \
     -H "Content-Type: application/json" \
     -d '{"copies":1}'
   ```
2. Send POST /config with missing required field:
   ```bash
   curl -X POST http://<tablet-ip>:8080/config \
     -H "Content-Type: application/json" \
     -d '{}'
   ```
3. Observe response status and error message

**Expected Result:**
- HTTP 400 response for missing required fields
- JSON error message listing missing field: `{"error":"Missing required field: pdfUrl"}`
- App validates input before processing

**Notes:**
- Document which fields are required vs optional for each endpoint
- Validate all inputs in API handler

**Priority:** P1

---

### TC-047: POST /print Returns jobId in Response

**Preconditions:**
- Printer configured and reachable
- Valid PDF URL available

**Steps:**
1. Send POST /print request:
   ```bash
   curl -X POST http://<tablet-ip>:8080/print \
     -H "Content-Type: application/json" \
     -d '{"pdfUrl":"http://railsapp.local/pdfs/test.pdf"}'
   ```
2. Inspect JSON response body

**Expected Result:**
- HTTP 200 response
- JSON includes jobId field: `{"status":"success","jobId":"abc123xyz"}`
- jobId is unique for each print request
- jobId can be used for tracking/logging

**Notes:**
- jobId useful for Rails app to track print jobs
- jobId may be UUID, timestamp-based, or sequential ID
- Document jobId format for Rails app integration

**Priority:** P1

---

### TC-048: GET /status Returns Printer Status

**Preconditions:**
- App running
- Printer configured (may be unreachable)

**Steps:**
1. Verify printer is powered on and accessible
2. Send GET /status:
   ```bash
   curl http://<tablet-ip>:8080/status
   ```
3. Inspect response

**Expected Result:**
- HTTP 200 response
- JSON includes current printer status:
   ```json
   {
     "status": "ready",
     "printerConnected": true,
     "paperLow": false,
     "lastJobStatus": "success",
     "lastJobTime": "2026-05-03T14:23:01Z"
   }
   ```
- Status values: "ready", "printing", "error", "offline"

**Notes:**
- /status useful for Rails app to poll printer health
- Include last job status for debugging
- If printer unreachable, status may be "offline" (not error)

**Priority:** P1

---

### TC-049: GET /health Returns Liveness Probe Response

**Preconditions:**
- App running on tablet

**Steps:**
1. Send GET /health:
   ```bash
   curl http://<tablet-ip>:8080/health
   ```
2. Inspect response status and body

**Expected Result:**
- HTTP 200 response
- Simple JSON: `{"status":"ok"}` or `{"alive":true}`
- Response time < 100ms
- No dependencies checked (should be instant check)

**Notes:**
- /health is for load balancer / orchestration liveness probes
- Should not contact printer or perform heavy operations
- Useful for Kubernetes or other container orchestration

**Priority:** P1

---

## 10. Appendix

### Test Execution Checklist

Use this checklist during test execution:

- [ ] Test environment verified (printer on, WiFi stable, tablet charged)
- [ ] App freshly installed or reset
- [ ] Printer IP configured and reachable
- [ ] First print test successful (TC-005)
- [ ] All P0 critical tests (TC-001 to TC-025) passed
- [ ] All P1 tests (TC-026 to TC-060) passed
- [ ] API contract tests (TC-061 to TC-068) passed
- [ ] No unexpected crashes during any test
- [ ] All printed labels inspected for quality
- [ ] Device logs reviewed for errors: `adb logcat | grep -i "error\|exception\|crash"`
- [ ] Configuration persists after app restart
- [ ] Foreground service remains active throughout testing

### Known Issues and Workarounds

Document any known issues discovered during testing:

| Issue | Workaround | Status |
|-------|-----------|--------|
| Example: "Print timeout at 90s" | "Use PDFs under 5 pages" | P2, Deferred |

### Environment Variables and Debug Tips

```bash
# View app logs in real-time
adb logcat | grep -i "Sirdab\|PrintService\|KeepAlive"

# Restart app
adb shell am force-stop co.sirdab.printer
adb shell am start -n co.sirdab.printer/co.sirdab.printer.MainActivity

# Check open ports
adb shell netstat -tln | grep 8080

# Force Doze mode (Android 6.0+)
adb shell dumpsys deviceidle force-idle

# Clear Doze mode
adb shell dumpsys deviceidle unforce

# View battery usage
adb shell dumpsys batterystats --reset
```

### Test Result Template

For each test run, record:

```
Test Case: TC-001
Date: 2026-05-03
Device: Samsung Galaxy Tab S7 (Android 12)
Result: PASS / FAIL
Notes: App launched successfully, no permissions prompt
Logs: [paste relevant logcat output]
```

---

## Document History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2026-05-03 | Development Team | Initial comprehensive test plan |

---

**End of Test Plan**

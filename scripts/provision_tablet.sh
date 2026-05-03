#!/usr/bin/env bash
#
# Configures a freshly-installed tablet so the Sirdab Printer Companion app
# can self-update from GitHub releases and run reliably under Fully Kiosk.
#
# Run this AFTER you've installed the APK with `adb install -r app-release.apk`.
#
# Usage:
#   ./scripts/provision_tablet.sh
#
# What it does (one-time per tablet):
#   1. Grants REQUEST_INSTALL_PACKAGES — needed for in-app self-updates.
#   2. Disables Google Play Protect's pre-install scan — Play Protect runs in
#      Play Services, which Fully Kiosk's Advanced Kiosk Protection kills,
#      leaving the install in limbo.
#   3. Adds the app to the deviceidle whitelist — no Doze, no battery
#      optimisation. Equivalent to the in-app battery-optimisation prompt
#      that Fully Kiosk blocks from showing.
#
# After running:
#   - Open the Companion app once on the tablet
#   - Enter the printer IP and tap Save
#   - Disable USB debugging when done

set -euo pipefail

PKG="co.sirdab.printer"

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found on PATH. Install with: brew install --cask android-platform-tools" >&2
  exit 1
fi

device_count=$(adb devices | tail -n +2 | grep -cw "device" || true)
if [[ "$device_count" -eq 0 ]]; then
  echo "no authorized device — plug in the tablet, accept the RSA prompt on screen, retry" >&2
  exit 1
fi
if [[ "$device_count" -gt 1 ]]; then
  echo "more than one device connected — adb will not know which to target" >&2
  exit 1
fi

if ! adb shell pm list packages | grep -q "^package:${PKG}$"; then
  echo "$PKG is not installed yet. Run: adb install -r app-release.apk first." >&2
  exit 1
fi

echo "── grant REQUEST_INSTALL_PACKAGES (so future updates can self-install)"
adb shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow

echo "── disable Play Protect pre-install verification"
adb shell settings put global package_verifier_enable 0
adb shell settings put global verifier_verify_adb_installs 0
adb shell settings put secure package_verifier_user_consent -1

echo "── add to deviceidle whitelist (no Doze / battery optimisation)"
adb shell dumpsys deviceidle whitelist "+$PKG" >/dev/null

cat <<'EOF'

✓ provisioning done. remaining manual steps on the tablet:

  1. open the Sirdab Printer Companion app once
  2. enter the printer IP, tap Save
  3. disable USB debugging in Developer options when done
EOF

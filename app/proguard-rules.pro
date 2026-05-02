# ProGuard / R8 rules for Sirdab Printer Companion
#
# minifyEnabled is currently false for the release build, so these rules
# are not active.  They are kept here so that if minification is ever
# enabled, the app will continue to work correctly.

# ── GAINSCHA SDK ─────────────────────────────────────────────────────────────
# The SDK is a closed-source AAR that uses reflection internally.
# Keep all public SDK classes and their members.
-keep class com.gainscha.** { *; }
-dontwarn com.gainscha.**

# ── NanoHTTPD ─────────────────────────────────────────────────────────────────
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# ── App models (serialised to/from JSON in HTTP responses) ───────────────────
-keep class co.sirdab.printer.models.** { *; }

# ── Standard Android / Kotlin ────────────────────────────────────────────────
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception

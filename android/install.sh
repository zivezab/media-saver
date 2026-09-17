#!/usr/bin/env bash
#
# Build (if needed) and install Media Saver onto a connected Android phone.
#
#   ./install.sh              install the debug APK, building it if missing
#   ./install.sh --build      force a rebuild first
#   ./install.sh --reinstall  uninstall first, then install (keeps nothing)
#   ./install.sh --devices    just list what adb can see, and exit
#   ./install.sh --emulator   also accept an emulator (skipped by default)
#
set -euo pipefail
cd "$(dirname "$0")"

APK="app/build/outputs/apk/debug/app-debug.apk"
PACKAGE="com.mediasaver"
MIN_SDK=29

BUILD=0; REINSTALL=0; LIST_ONLY=0; ALLOW_EMULATOR=0
for arg in "$@"; do
  case "$arg" in
    --build) BUILD=1 ;;
    --reinstall) REINSTALL=1 ;;
    --devices) LIST_ONLY=1 ;;
    --emulator) ALLOW_EMULATOR=1 ;;
    -h|--help) sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown option: $arg (try --help)" >&2; exit 2 ;;
  esac
done

say()  { printf '%s\n' "$*"; }
step() { printf '\n==> %s\n' "$*"; }
die()  { printf '\nError: %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- toolchain
# adb lives inside the SDK and is usually not on PATH, which is the most common
# reason this fails. Look in the places it actually gets installed.
SDK_CANDIDATES=(
  "${ANDROID_HOME:-}"
  "${ANDROID_SDK_ROOT:-}"
  "/opt/homebrew/share/android-commandlinetools"
  "/usr/local/share/android-commandlinetools"
  "$HOME/Library/Android/sdk"
  "$HOME/Android/Sdk"
)

ADB=""
if command -v adb >/dev/null 2>&1; then
  ADB="$(command -v adb)"
else
  for root in "${SDK_CANDIDATES[@]}"; do
    [ -n "$root" ] || continue
    if [ -x "$root/platform-tools/adb" ]; then
      ADB="$root/platform-tools/adb"
      export ANDROID_HOME="$root"
      break
    fi
  done
fi

[ -n "$ADB" ] || die "adb not found.

Install the platform tools, then run this again:
    brew install --cask android-platform-tools
or, if you already have the SDK somewhere unusual:
    export ANDROID_HOME=/path/to/sdk"

say "adb: $ADB"

# ------------------------------------------------------------------ devices
step "Looking for a phone"
"$ADB" start-server >/dev/null 2>&1 || true

devices_raw="$("$ADB" devices | tail -n +2 | sed '/^[[:space:]]*$/d')"

# Emulators are skipped unless asked for. This script is for putting the app on
# a phone, and treating an emulator as one caused two quiet failures: with only
# an emulator attached it installed there and reported success, and with a phone
# attached as well it refused to choose. An emulator is often not even yours -
# another project can be using it.
skipped_emulators=0
if [ "$ALLOW_EMULATOR" = 0 ]; then
  skipped_emulators="$(printf '%s\n' "$devices_raw" | grep -c '^emulator-' || true)"
  devices_raw="$(printf '%s\n' "$devices_raw" | grep -v '^emulator-' | sed '/^[[:space:]]*$/d' || true)"
fi

if [ "$LIST_ONLY" = 1 ]; then
  say "${devices_raw:-（none）}"
  exit 0
fi

if [ -z "$devices_raw" ]; then
  if [ "$skipped_emulators" -gt 0 ]; then
    say "Skipped $skipped_emulators emulator(s). Pass --emulator to install onto one."
  fi
  die "No phone found.

On the phone:
  1. Settings > About phone > tap 'Build number' seven times
  2. Settings > System > Developer options > turn on 'USB debugging'
  3. Plug it into this Mac with a cable that carries data
  4. Unlock the phone and tap 'Allow' on the 'Allow USB debugging?' prompt

Then run this again. './install.sh --devices' shows what adb can see."
fi

if printf '%s' "$devices_raw" | grep -q "unauthorized"; then
  die "The phone is connected but has not authorised this Mac.

Unlock the phone and tap 'Allow' on the 'Allow USB debugging?' prompt.
If no prompt appears, revoke and retry:
  Developer options > 'Revoke USB debugging authorisations', then replug."
fi

if printf '%s' "$devices_raw" | grep -q "offline"; then
  die "The phone is listed as offline. Unplug it, replug it, and unlock the screen."
fi

ready_count="$(printf '%s\n' "$devices_raw" | grep -c "device$" || true)"
[ "$ready_count" -ge 1 ] || die "No usable device. adb reports:
$devices_raw"

if [ "$ready_count" -gt 1 ]; then
  say "More than one device is attached:"
  printf '%s\n' "$devices_raw"
  die "Disconnect the others (or stop the emulator) and run this again."
fi

SERIAL="$(printf '%s\n' "$devices_raw" | grep "device$" | head -1 | awk '{print $1}')"
ADBS=("$ADB" -s "$SERIAL")

model="$("${ADBS[@]}" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || true)"
release="$("${ADBS[@]}" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r' || true)"
sdk="$("${ADBS[@]}" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' || true)"
abis="$("${ADBS[@]}" shell getprop ro.product.cpu.abilist 2>/dev/null | tr -d '\r' || true)"

say "Found: ${model:-unknown} — Android ${release:-?} (API ${sdk:-?})"
say "CPU:   ${abis:-unknown}"

# Check the two things that make an install fail with an unhelpful message.
if [ -n "$sdk" ] && [ "$sdk" -lt "$MIN_SDK" ] 2>/dev/null; then
  die "This phone runs API $sdk, and the app needs API $MIN_SDK (Android 10) or newer.

API 29 is what lets the app save files without asking for storage permission.
Supporting older Android means lowering minSdk in app/build.gradle.kts and
adding the legacy storage path."
fi

case "$abis" in
  *arm64-v8a*) : ;;
  "") say "Warning: could not read the CPU ABI; continuing." ;;
  *) die "This phone is $abis, but the APK only contains arm64-v8a.

Add your ABI to abiFilters in app/build.gradle.kts and rebuild:
    ndk { abiFilters += listOf(\"arm64-v8a\", \"armeabi-v7a\") }" ;;
esac

# ------------------------------------------------------------------- build
if [ "$BUILD" = 1 ] || [ ! -f "$APK" ]; then
  step "Building the APK"

  if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME:-}/bin/javac" ]; then
    for jdk in \
      /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
      /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
      /Library/Java/JavaVirtualMachines/*/Contents/Home; do
      if [ -x "$jdk/bin/javac" ]; then export JAVA_HOME="$jdk"; break; fi
    done
  fi
  [ -n "${JAVA_HOME:-}" ] || die "No JDK found. Install one with:  brew install openjdk@21"
  say "JDK: $JAVA_HOME"

  ./gradlew :app:assembleDebug || die "The build failed; the Gradle output above says why."
fi

[ -f "$APK" ] || die "APK not found at $APK. Run './install.sh --build'."
say ""
say "APK: $APK ($(ls -lh "$APK" | awk '{print $5}'))"

# ----------------------------------------------------------------- install
if [ "$REINSTALL" = 1 ]; then
  step "Removing the existing copy"
  "${ADBS[@]}" uninstall "$PACKAGE" >/dev/null 2>&1 || true
fi

# Wait up to ~20s for the phone to be reachable again. A USB hiccup - the cable
# moving, the phone switching USB mode, the screen locking - drops adb for a
# moment, and anything run during it fails with no reason given.
wait_for_phone() {
  local i
  for i in $(seq 1 20); do
    [ "$("${ADBS[@]}" get-state 2>/dev/null)" = "device" ] && return 0
    sleep 1
  done
  return 1
}

try_install() {
  set +e
  output="$("${ADBS[@]}" install -r "$APK" 2>&1)"
  status=$?
  set -e
  printf '%s\n' "$output"
  [ $status -eq 0 ] && ! printf '%s' "$output" | grep -q "Failure"
}

step "Installing (this takes a moment; the APK is large)"
if ! try_install; then
  # No "Failure [REASON]" means adb lost the phone rather than the phone saying
  # no, so it is worth one more go once the connection is back.
  if ! printf '%s' "$output" | grep -q "INSTALL_FAILED\|Failure \["; then
    say ""
    say "The install failed without a reason, which usually means the USB"
    say "connection dropped for a moment. Retrying..."
    wait_for_phone || die "Lost the phone. Check the cable, unlock the phone, and run this again."
    try_install && installed=1
  fi
fi

if [ "${installed:-0}" != 1 ] && { [ $status -ne 0 ] || printf '%s' "$output" | grep -q "Failure"; }; then
  case "$output" in
    *INSTALL_FAILED_UPDATE_INCOMPATIBLE*|*INSTALL_FAILED_VERSION_DOWNGRADE*)
      die "A different build of $PACKAGE is already installed.
Run './install.sh --reinstall' to replace it." ;;
    *INSTALL_FAILED_INSUFFICIENT_STORAGE*)
      die "Not enough free space on the phone. The app needs roughly 300 MB installed." ;;
    *INSTALL_FAILED_USER_RESTRICTED*|*INSTALL_FAILED_ABORTED*)
      say ""
      say "The phone refused the install over USB."
      say ""
      say "On Xiaomi/Redmi/POCO (and some Oppo, Vivo, Realme) phones this is a vendor"
      say "restriction rather than a problem with the app. To allow it, in Developer"
      say "options turn on both:"
      say "  - Install via USB"
      say "  - USB debugging (Security settings)"
      say "Both usually require being signed into a Mi account, and the phone may need"
      say "mobile data switched on before the toggles will stick. Watch the phone's"
      say "screen while installing too - it often shows a confirmation prompt that"
      say "times out on its own."
      ;;
    *)
      say ""
      say "The install did not go through (see adb's message above)."
      ;;
  esac

  step "Copying the APK onto the phone instead"
  wait_for_phone || die "Lost the phone. Check the cable, unlock the phone, and run this again."
  set +e
  push_output="$("${ADBS[@]}" push "$APK" /sdcard/Download/media-saver.apk 2>&1)"
  push_status=$?
  set -e
  if [ $push_status -eq 0 ]; then
    say "Copied to Downloads as media-saver.apk"
    say ""
    say "Finish on the phone:"
    say "  1. Open Files (or My Files) and go to Downloads"
    say "  2. Tap media-saver.apk"
    say "  3. Allow that app to install unknown apps when asked, then tap Install"
    "${ADBS[@]}" shell am start -a android.intent.action.VIEW \
      -d "file:///sdcard/Download/media-saver.apk" \
      -t "application/vnd.android.package-archive" >/dev/null 2>&1 || true
    say ""
    say "(If a package installer just opened on the phone, you can tap Install there.)"
    exit 0
  fi

  printf '%s\n' "$push_output" | tail -3
  die "Could not copy the APK to the phone either, so the USB connection itself
is failing. Try another cable or port, keep the phone unlocked, and run this again."
fi

step "Starting the app"
"${ADBS[@]}" shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 \
  || say "Installed, but could not launch it automatically — open it from the app drawer."

say ""
say "Done. Media Saver is on ${model:-your phone}."
say "Share a link into it from any app, or paste one."
say ""
say "The first launch unpacks its Python runtime and updates yt-dlp, so give it"
say "a few seconds before the first lookup."

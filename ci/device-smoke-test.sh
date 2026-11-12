#!/usr/bin/env bash
# Runs on an Android emulator in CI: installs the release APK, takes one photo online and one
# offline (Wi-Fi and mobile data off), and checks both were saved. Prints the app's log on failure.
set -u
APK="$1"
PKG=com.gpsphoto.app

ui_dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml ui.xml >/dev/null 2>&1; }

# Taps the on-screen element whose accessibility label is $1.
tap() {
  ui_dump
  local nums
  nums=$(grep -o "content-desc=\"$1\"[^>]*bounds=\"[^\"]*\"" ui.xml | grep -o 'bounds="[^"]*"' | head -1 | grep -o '[0-9]\+')
  if [ -z "$nums" ]; then
    echo "!! Could not find '$1' on screen. Visible labels:"
    grep -o 'content-desc="[^"]\+"\|text="[^"]\+"' ui.xml | sort -u | head -40
    return 1
  fi
  set -- $nums
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}

saved_count() {
  adb shell content query --uri content://media/external/images/media --projection _display_name \
    --where "_display_name LIKE 'GPS_%'" 2>/dev/null | grep -c "Row:"
}

screen_text() { ui_dump; grep -o 'text="[^"]\+"' ui.xml | sort -u | tr '\n' ' '; echo; }

adb install -r -g "$APK" || exit 1
adb emu geo fix 39.2083 -6.7924 10
adb logcat -c
adb shell am start -W -n "$PKG/.MainActivity"
sleep 15
adb emu geo fix 39.2083 -6.7924 10
echo "== Screen after launch: $(screen_text)"

before=$(saved_count)
tap "Take photo" || true
sleep 10
online=$(saved_count)
echo "== Online capture: saved photos $before -> $online"
echo "== Screen: $(screen_text)"

adb shell svc wifi disable
adb shell svc data disable
sleep 5
adb emu geo fix 39.2084 -6.7925 10
tap "Take photo" || true
sleep 12
offline=$(saved_count)
echo "== Offline capture: saved photos $online -> $offline"
echo "== Screen: $(screen_text)"
adb shell content query --uri content://media/external/images/media --projection _display_name:relative_path:_size \
  --where "_display_name LIKE 'GPS_%'"

echo "== App process alive: $(adb shell pidof $PKG || echo NO)"
echo "== Crash log:"
adb logcat -d -b crash | tail -60
echo "== App log:"
adb logcat -d | grep -iE "GpsCamera|AndroidRuntime|$PKG|CameraX|ImageCapture|MediaProvider.*GPS_" | tail -120

[ "$online" -gt "$before" ] && [ "$offline" -gt "$online" ]

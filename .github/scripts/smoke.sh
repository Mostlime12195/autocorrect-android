#!/bin/bash
# Emulator smoke test: install, enable + select the IME, open the settings screen, tap keys on the keyboard,
# check the text arrived and nothing crashed. Leaves screenshots + logcat in smoke/.
set -u
APK="$1"
PKG=dev.mostlime.autocorrect
IME=$PKG/.ime.AutocorrectIME
mkdir -p smoke
adb install -r -g "$APK"
# the input method service may take a moment to see a freshly installed IME
for i in 1 2 3 4 5 6 7 8 9 10; do
  adb shell ime enable $IME 2>&1 | grep -q "cannot" || break
  sleep 3
done
adb shell ime set $IME
adb shell ime list -s
adb logcat -c
adb shell am start -W -n $PKG/.ui.SettingsActivity
sleep 8
read W H < <(adb shell wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1)
DPI=$(adb shell wm density | sed -n 's/.*: \([0-9]*\).*/\1/p' | tail -1)
echo "screen ${W}x${H} @ ${DPI}dpi"
# focus the "Try the keyboard here" box (first EditText) via its bounds in a UI dump
adb shell uiautomator dump /sdcard/ui.xml >/dev/null
adb pull /sdcard/ui.xml smoke/ui0.xml >/dev/null
B=$(grep -o 'resource-id="dev.mostlime.autocorrect:id/try_it"[^>]*bounds="[^"]*"' smoke/ui0.xml | grep -o 'bounds="[^"]*"' | grep -o '[0-9]*' | tr '\n' ' ')
set -- $B
adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
sleep 4
adb exec-out screencap -p > smoke/keyboard.png
# wait (up to 4 min) for the model download + session load, so the typing below runs the LM
for i in $(seq 1 48); do
  adb logcat -d 2>/dev/null | grep -q "AcEngine: session .* ready" && break
  sleep 5
done
adb logcat -d | grep "AcEngine: session" | tail -2
# IME window frame -> key geometry (KeyboardView: strip 44dp, rows clamp(keyW*1.25, 46dp, 58dp))
TOP=$(adb shell dumpsys window windows | grep -A40 'InputMethod' | grep -o 'frame=\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | head -1 | sed 's/frame=\[[0-9]*,\([0-9]*\)\].*/\1/')
[ -z "$TOP" ] && TOP=$(adb shell dumpsys input_method | grep -o 'mInputShown=[a-z]*')
echo "ime top: $TOP"
python3 - "$W" "$DPI" "$TOP" > smoke/taps.txt <<'PY'
import sys
W, dpi, top = int(sys.argv[1]), int(sys.argv[2]), int(sys.argv[3])
d = dpi / 160; kw = W / 10; rh = min(max(kw * 1.25, 46 * d), 58 * d); strip = 44 * d
rows = ['qwertyuiop', 'asdfghjkl', 'zxcvbnm']; off = [0, 0.5, 1.5]
def key(c):
    if c == ' ': return (5.0 * kw, top + strip + 3.5 * rh)
    for r, row in enumerate(rows):
        if c in row: return ((off[r] + row.index(c) + 0.5) * kw, top + strip + (r + 0.5) * rh)
for c in "hello wrold thsi is a tesy ":
    x, y = key(c); print(int(x), int(y))
print('WAIT')
for c in "i wnat to go hoem now and hvae some fod ":
    x, y = key(c); print(int(x), int(y))
PY
while read X Y; do
  if [ "$X" = WAIT ]; then sleep 20; continue; fi
  adb shell input tap $X $Y < /dev/null; sleep 0.4
done < smoke/taps.txt
sleep 20
adb exec-out screencap -p > smoke/typed.png
# same keyboard with gesture navigation (3-button is the emulator default)
adb shell cmd overlay enable com.android.internal.systemui.navbar.gestural || true
sleep 4
adb exec-out screencap -p > smoke/gesture_nav.png
adb shell cmd overlay enable com.android.internal.systemui.navbar.threebutton || true
adb shell uiautomator dump /sdcard/ui.xml >/dev/null
adb pull /sdcard/ui.xml smoke/ui1.xml >/dev/null
TEXT=$(grep -o 'text="[^"]*" resource-id="dev.mostlime.autocorrect:id/try_it"' smoke/ui1.xml | sed 's/text="\([^"]*\)".*/\1/')
echo "typed text: '$TEXT'"
adb logcat -d > smoke/logcat.txt
grep -E "AcEngine|AcIME|AcLog|ModelStore" smoke/logcat.txt | tail -30
echo "per-word timings:"; grep "AcIME: word" smoke/logcat.txt | sed 's/.*AcIME: //'
if grep -q "FATAL EXCEPTION" smoke/logcat.txt; then grep -A30 "FATAL EXCEPTION" smoke/logcat.txt | head -60; echo "CRASH"; exit 1; fi
case "$TEXT" in *ello*) echo "OK: keyboard typed into the field";; *) echo "no typed text found"; exit 1;; esac

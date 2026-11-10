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
# (retry: right after start the window can still be a system dialog / not drawn yet)
B=""
for i in 1 2 3 4 5 6 7 8; do
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null
  adb pull /sdcard/ui.xml smoke/ui0.xml >/dev/null
  B=$(grep -o 'resource-id="dev.mostlime.autocorrect:id/try_it"[^>]*bounds="[^"]*"' smoke/ui0.xml | grep -o 'bounds="[^"]*"' | grep -o '[0-9]*' | tr '\n' ' ')
  [ -n "$B" ] && break
  echo "test box not on screen yet (try $i)"; grep -o 'text="[^"]*"' smoke/ui0.xml | head -5
  adb exec-out screencap -p > smoke/no_box_$i.png
  sleep 5
done
[ -n "$B" ] || { echo "no test box found"; exit 1; }
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
# suggest-only is the default mode: the first sentences must keep their typos
case "$TEXT" in *"wrold thsi"*) echo "OK: default (suggest-only) left the typos as typed";; *) echo "WARN: default-mode text changed or missing";; esac

# auto-correct mode: switch it in settings, type typos, they should be fixed
tap_text() {  # tap the first UI element whose text starts with $1 (scrolling the settings list down to find it)
  for i in 1 2 3 4 5 6 7 8; do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null; adb pull /sdcard/ui.xml smoke/ui_s.xml >/dev/null
    B=$(grep -o "text=\"$1[^\"]*\"[^>]*bounds=\"[^\"]*\"" smoke/ui_s.xml | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]*' | tr '\n' ' ')
    if [ -n "$B" ]; then set -- $B; adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )); return 0; fi
    adb shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 3)) 300; sleep 1
  done
  echo "not found: $1"; return 1
}
adb shell input keyevent KEYCODE_BACK; sleep 2
# settings are in sub-screens: Correction -> Correction mode (the test box stays above them)
tap_text "Correction" && sleep 2
if tap_text "Correction mode"; then
  sleep 2; tap_text "Auto-correct" && echo "auto-correct mode on"; sleep 2
  for i in 1 2 3 4 5 6 7 8; do adb shell input swipe $((W / 2)) $((H / 3)) $((W / 2)) $((H * 3 / 4)) 200; done
  sleep 1
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null; adb pull /sdcard/ui.xml smoke/ui2.xml >/dev/null
  B=$(grep -o 'resource-id="dev.mostlime.autocorrect:id/try_it"[^>]*bounds="[^"]*"' smoke/ui2.xml | grep -o 'bounds="[^"]*"' | grep -o '[0-9]*' | tr '\n' ' ')
  set -- $B
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )); sleep 3
  adb shell input keyevent KEYCODE_MOVE_END
  adb shell input keyevent $(printf '67 %.0s' $(seq 1 150))
  python3 - "$W" "$DPI" "$TOP" > smoke/taps2.txt <<'PY'
import sys
W, dpi, top = int(sys.argv[1]), int(sys.argv[2]), int(sys.argv[3])
d = dpi / 160; kw = W / 10; rh = min(max(kw * 1.25, 46 * d), 58 * d); strip = 44 * d
rows = ['qwertyuiop', 'asdfghjkl', 'zxcvbnm']; off = [0, 0.5, 1.5]
def key(c):
    if c == ' ': return (5.0 * kw, top + strip + 3.5 * rh)
    for r, row in enumerate(rows):
        if c in row: return ((off[r] + row.index(c) + 0.5) * kw, top + strip + (r + 0.5) * rh)
for c in "i wnat to go hoem now ":
    x, y = key(c); print(int(x), int(y))
PY
  while read X Y; do adb shell input tap $X $Y < /dev/null; sleep 0.4; done < smoke/taps2.txt
  sleep 20
  adb exec-out screencap -p > smoke/auto_correct.png
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null; adb pull /sdcard/ui.xml smoke/ui3.xml >/dev/null
  TEXT2=$(grep -o 'text="[^"]*" resource-id="dev.mostlime.autocorrect:id/try_it"' smoke/ui3.xml | sed 's/text="\([^"]*\)".*/\1/')
  echo "auto-correct text: '$TEXT2'"
  case "$TEXT2" in *"want to go home"*) echo "OK: auto-correct fixed the typos";; *) echo "WARN: auto-correct text not fixed or missing";; esac
fi


# RAM of the keyboard process (PSS), idle and mid-typing, with the model the emulator picked (m1 Lite on this small
# AVD) and then with m2
pss() { echo "PSS $1: $(adb shell dumpsys meminfo dev.mostlime.autocorrect:ime | grep -E 'TOTAL PSS:|^ *TOTAL ' | head -1 | tr -s ' ')"; }
focus_box() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null; adb pull /sdcard/ui.xml smoke/ui_f.xml >/dev/null
  B=$(grep -o 'resource-id="dev.mostlime.autocorrect:id/try_it"[^>]*bounds="[^"]*"' smoke/ui_f.xml | grep -o 'bounds="[^"]*"' | grep -o '[0-9]*' | tr '\n' ' ')
  [ -n "$B" ] && { set -- $B; adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )); }
}
type_taps() { while read X Y; do adb shell input tap $X $Y < /dev/null; sleep 0.3; done < smoke/taps2.txt; }
measure() {
  focus_box; sleep 5
  pss "$1 idle"
  type_taps & TP=$!
  sleep 5; pss "$1 mid-typing"
  wait $TP; sleep 2; pss "$1 after typing"
}
MODEL1=$(adb logcat -d | grep -o "AcEngine: session [a-z0-9_]* ready" | tail -1 | awk '{print $3}')
measure "${MODEL1:-model}"
# switch off the Lite model -> m2 (downloads ~80 MB), then measure again
adb shell input keyevent KEYCODE_BACK; sleep 1
# a fresh settings activity on its main screen (the earlier phase left it in a sub-screen)
adb shell am start -S -n dev.mostlime.autocorrect/.ui.SettingsActivity >/dev/null; sleep 5
if tap_text "Model" && sleep 2 && tap_text "Lite model"; then
  for i in $(seq 1 30); do
    adb logcat -d | grep -q "AcEngine: session m2s_int4 ready" && break
    adb shell input keyevent KEYCODE_BACK; sleep 1; focus_box; sleep 10
  done
  adb logcat -d | grep "AcEngine: session m2s_int4 ready" | tail -1
  adb logcat -d | grep -q "AcEngine: session m2s_int4 ready" && measure "m2s_int4"
fi

adb logcat -d > smoke/logcat.txt
grep -E "AcEngine|AcIME|AcLog|ModelStore" smoke/logcat.txt | tail -30
echo "per-word timings:"; grep "AcIME: word" smoke/logcat.txt | sed 's/.*AcIME: //'
if grep -q "FATAL EXCEPTION" smoke/logcat.txt; then grep -A30 "FATAL EXCEPTION" smoke/logcat.txt | head -60; echo "CRASH"; exit 1; fi
case "$TEXT" in *ello*) echo "OK: keyboard typed into the field";; *) echo "no typed text found"; exit 1;; esac

#!/usr/bin/env bash
# Drives CUTTINX on an emulator and keeps real screenshots / screen recordings (for the promo videos).
set -u
PKG=com.superessence.cuttinx
OUT=${OUT:-shots}; mkdir -p "$OUT"
UI="python3 tools/screenshots/ui.py"
shot() { adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }
rec_start() { adb shell "screenrecord --bit-rate 12000000 --time-limit $2 /sdcard/$1.mp4" & RECPID=$!; }
rec_end() { wait $RECPID 2>/dev/null; sleep 1; adb pull "/sdcard/$1.mp4" "$OUT/$1.mp4" >/dev/null && echo "rec $1"; }
tap() { $UI tap "$@"; sleep "${WAIT:-2}"; }
dismiss() { for t in "Passer" "Plus tard" "Fermer" "Compris" "OK"; do $UI tap "$t" exact >/dev/null 2>&1 && sleep 1; done; true; }

adb install -r -g "$APK"
for p in CAMERA RECORD_AUDIO POST_NOTIFICATIONS; do adb shell pm grant $PKG android.permission.$p 2>/dev/null; done
adb shell settings put global window_animation_scale 1

# launch animation
rec_start intro 9
adb shell am start -n $PKG/.MainActivity
rec_end intro
sleep 3; dismiss; sleep 1
shot home
$UI list > "$OUT/home.txt"

# camera: two takes, with the prompter
tap "Filmer"; sleep 4; dismiss
shot camera
WAIT=1 tap "Prompteur"
rec_start camera_rec 12
WAIT=1 tap "Enregistrer"; sleep 5
shot camera_recording
WAIT=1 tap "Arrêter la prise"; sleep 2
WAIT=1 tap "Enregistrer"; sleep 4
WAIT=1 tap "Arrêter la prise"
rec_end camera_rec
sleep 2; shot camera_takes
tap "Monter"; sleep 10; dismiss
shot editor
$UI list > "$OUT/editor.txt"

# toolbar: scroll to the one-gesture and Plus tools
W=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | cut -dx -f1); H=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | cut -dx -f2)
Y=$((H * 93 / 100))
rec_start toolbar 8
for i in 1 2 3 4; do adb shell input swipe $((W * 85 / 100)) $Y $((W * 15 / 100)) $Y 600; sleep 1; done
rec_end toolbar
shot toolbar_plus
tap "Sur la musique"; sleep 2; shot plus_dialog; dismiss
for i in 1 2 3 4; do adb shell input swipe $((W * 15 / 100)) $Y $((W * 85 / 100)) $Y 300; done; sleep 1

# filters and effects with their pictures (computed on the takes)
tap "Filtres"; sleep 6; shot filters
tap "Cinéma"; sleep 5; shot filters_cinema
adb shell input keyevent KEYCODE_BACK; sleep 2
tap "Effets"; sleep 6; shot effects
adb shell input keyevent KEYCODE_BACK; sleep 2
tap "Labo"; sleep 3; shot labo
adb shell input keyevent KEYCODE_BACK; sleep 2
tap "Studio"; sleep 3; shot studio
adb shell input keyevent KEYCODE_BACK; sleep 2
tap "Exporter"; sleep 3; shot export
adb shell input keyevent KEYCODE_BACK; sleep 2

# home, settings
adb shell input keyevent KEYCODE_BACK; sleep 3; dismiss
tap "Menu"; tap "Paramètres"; sleep 2; shot settings
adb shell input swipe $((W / 2)) $((H * 80 / 100)) $((W / 2)) $((H * 25 / 100)) 500; sleep 1; shot settings_2
ls -la "$OUT"

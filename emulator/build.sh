#!/usr/bin/env bash
# Builds the WinPocket APK: fetches the pinned Winlator sources, applies the WinPocket overlay
# (name, icon, colours, French translation) and runs the Android build.
# Needs: JDK 17+, Android SDK with NDK 24.0.8215888 and CMake 3.22.1.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck disable=SC1091
source "$here/upstream.properties"
work="$here/build"
src="$work/winlator-app"
mkdir -p "$work"

if [ ! -d "$src/.git" ]; then
  git init -q "$src"
  git -C "$src" remote add origin "$UPSTREAM_URL"
fi
git -C "$src" fetch -q --depth 1 origin "$UPSTREAM_COMMIT"
git -C "$src" checkout -q -f FETCH_HEAD
git -C "$src" clean -q -fd

# ---- WinPocket overlay
cp -R "$here/overlay/." "$src/"
res="$src/app/src/main/res"
for f in "$res"/values*/strings.xml; do
  sed -i 's#<string name="app_name">Winlator</string>#<string name="app_name">WinPocket</string>#' "$f"
  sed -i 's/Keeps Winlator running/Keeps WinPocket running/; s/Winlator is running in the background/WinPocket is running in the background/' "$f"
done
sed -i 's#<item name="colorAccent">\#0288d1</item>#<item name="colorAccent">\#3D8BFF</item>#' "$res/values/styles.xml"
sed -i 's/versionName "\([0-9.]*\)"/versionName "\1-winpocket"/' "$src/app/build.gradle"

# ---- build
cd "$src"
chmod +x gradlew
./gradlew --no-daemon assembleDebug
mkdir -p "$here/dist"
cp app/build/outputs/apk/debug/*.apk "$here/dist/WinPocket.apk"
echo "APK : $here/dist/WinPocket.apk"

#!/usr/bin/env bash
# Build Box Tools without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner (debug key).
set -euo pipefail

# Get absolute path of script directory (works portably in Bash & Zsh on macOS/Linux)
P="$(cd -P -- "$(dirname -- "${BASH_SOURCE[0]:-$0}")" && pwd -P)"

export JAVA_HOME=${JAVA_HOME:?set JAVA_HOME to a JDK 21 (e.g. Temurin) before building}
export PATH=$JAVA_HOME/bin:$PATH

# Determine Android SDK path (checks ANDROID_HOME or OS-specific defaults)
if [ -n "${ANDROID_HOME:-}" ]; then
    SDK_ROOT="$ANDROID_HOME"
elif [[ "$OSTYPE" == "darwin"* ]]; then
    SDK_ROOT="$HOME/Library/Android/sdk"
else
    SDK_ROOT="$HOME/Android/Sdk"
fi

BT="$SDK_ROOT/build-tools/36.0.0"
JAR="$SDK_ROOT/platforms/android-36/android.jar"

O="$P/out"
rm -rf "$O" && mkdir -p "$O/flat" "$O/classes"

"$BT/aapt2" compile --dir "$P/res" -o "$O/flat/res.zip"
"$BT/aapt2" link -I "$JAR" --manifest "$P/AndroidManifest.xml" -o "$O/base.apk" "$O/flat/res.zip"

javac -nowarn --release 11 -classpath "$JAR" -d "$O/classes" $(find "$P/src" -name '*.java') >"$O/javac.log" 2>&1 || { cat "$O/javac.log"; exit 1; }
grep -v -- '^warning' "$O/javac.log" || true

"$BT/d8" --release --min-api 29 --lib "$JAR" --output "$O" $(find "$O/classes" -name '*.class')

(cd "$O" && zip -q -j base.apk classes.dex)

"$BT/zipalign" -f -p 4 "$O/base.apk" "$O/aligned.apk"
"$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android --out "$O/minicarhome.apk" "$O/aligned.apk"
"$BT/apksigner" verify "$O/minicarhome.apk" && echo "built: $O/minicarhome.apk"

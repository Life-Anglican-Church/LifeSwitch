#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_JAR:?Set the Android 35 android.jar path}"
: "${ANDROID_BUILD_TOOLS:?Set the Android build-tools 35.0.0 directory}"
: "${LIFESWITCH_KEYSTORE:?Set the APK signing keystore path}"
: "${LIFESWITCH_KEYSTORE_PASSWORD:?Set the APK signing keystore password}"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/classes" "$work/dex" "$work/generated" ../releases
"$ANDROID_BUILD_TOOLS/aapt2" compile --no-crunch --dir res -o "$work/resources.zip"
"$ANDROID_BUILD_TOOLS/aapt2" link -o "$work/unsigned.apk" --manifest AndroidManifest.xml \
    -I "$ANDROID_JAR" --java "$work/generated" "$work/resources.zip"
java_sources=(src/org/lifeproduction/lifeswitch/MainActivity.java
              "$work/generated/org/lifeproduction/lifeswitch/R.java")
if [[ -n ${LIFESWITCH_ECJ_JAR:-} ]]; then
    java -jar "$LIFESWITCH_ECJ_JAR" -source 8 -target 8 -classpath "$ANDROID_JAR" -d "$work/classes" "${java_sources[@]}"
else
    javac --release 8 -classpath "$ANDROID_JAR" -d "$work/classes" "${java_sources[@]}"
fi
(cd "$work/classes" && zip -qr "$work/classes.jar" .)
"$ANDROID_BUILD_TOOLS/d8" --lib "$ANDROID_JAR" --min-api 26 --output "$work/dex" "$work/classes.jar"
(cd "$work/dex" && zip -q "$work/unsigned.apk" classes.dex)
"$ANDROID_BUILD_TOOLS/zipalign" -f 4 "$work/unsigned.apk" "$work/aligned.apk"
"$ANDROID_BUILD_TOOLS/apksigner" sign --ks "$LIFESWITCH_KEYSTORE" --ks-key-alias lifeswitch \
    --ks-pass env:LIFESWITCH_KEYSTORE_PASSWORD --v4-signing-enabled false \
    --out ../releases/LifeSWITCH-Android-0.1.0-beta.2.apk "$work/aligned.apk"
"$ANDROID_BUILD_TOOLS/apksigner" verify --verbose ../releases/LifeSWITCH-Android-0.1.0-beta.2.apk

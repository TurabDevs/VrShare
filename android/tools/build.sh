#!/bin/bash
# Manual VrShare APK build (no Gradle: the sandbox corrupts Java loopback TCP,
# which breaks the Gradle daemon protocol).
#
# Pipeline: resolve deps -> aapt2 compile/link -> kotlinc -> d8 -> zipalign -> apksigner
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PROJ="$HERE/.."
APP="$PROJ/app/src/main"
DEPS="$HOME/workspace/.sdktmp/deps"
OUT="$PROJ/build/manual"
SDK="$HOME/Android/Sdk"
BT="$SDK/build-tools/34.0.0"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"

export JAVA_HOME="$HOME/jdk17"
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "$OUT"/{gen,classes,dex,res-merged,aar-res}

echo "== 1. extract AARs =="
> "$OUT/aar-res-list.txt"
> "$OUT/cp.txt"
# AAR manifests must not contribute components (we hand-merge if they do).
> "$OUT/aar-manifest-check.txt"
for aar in "$DEPS"/aars/*.aar; do
  name="$(basename "$aar" .aar)"
  d="$OUT/aar-res/$name"
  mkdir -p "$d"
  unzip -q -o "$aar" -d "$d"
  if [ -f "$d/classes.jar" ]; then
    cp "$d/classes.jar" "$OUT/$name-classes.jar"
    echo "$OUT/$name-classes.jar" >> "$OUT/cp.txt"
  fi
  if [ -d "$d/res" ]; then
    echo "$d/res" >> "$OUT/aar-res-list.txt"
  fi
  if [ -f "$d/AndroidManifest.xml" ]; then
    if grep -qE "<(activity|service|receiver|provider)" "$d/AndroidManifest.xml"; then
      echo "COMPONENTS in $name" >> "$OUT/aar-manifest-check.txt"
    fi
  fi
done
# These three AARs' components are hand-merged into our AndroidManifest.xml
# (see the androidx.startup provider + ProfileInstallReceiver there).
ALLOWED_COMPONENT_AARS="lifecycle-process profileinstaller startup-runtime"
if grep -vE "lifecycle-process|profileinstaller|startup-runtime|emoji2" "$OUT/aar-manifest-check.txt" | grep -q COMPONENTS; then
  echo "AAR manifests declare unexpected components - hand merge needed:"
  cat "$OUT/aar-manifest-check.txt"; exit 1
fi
# plain jars (kotlin-stdlib already resolved from Maven; do NOT add kotlinc's own copy twice)
for jar in "$DEPS"/jars/*.jar; do
  [ -f "$jar" ] || continue
  echo "$jar" >> "$OUT/cp.txt"
done
echo "classpath entries: $(wc -l < "$OUT/cp.txt")"

echo "== 2. compile resources (each res tree separately; link merges with overlay semantics) =="
"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/compiled-app.zip"
R_ARGS=()
i=0
while read -r rdir; do
  [ -n "$rdir" ] || continue
  if [ -z "$(ls -A "$rdir" 2>/dev/null)" ]; then continue; fi
  i=$((i+1))
  "$BT/aapt2" compile --dir "$rdir" -o "$OUT/compiled-lib-$i.zip"
  R_ARGS+=(-R "$OUT/compiled-lib-$i.zip")
done < "$OUT/aar-res-list.txt"
R_ARGS+=(-R "$OUT/compiled-app.zip") # app last: wins on conflicts

echo "== 3. link (generate R.java + base APK) =="
"$BT/aapt2" link -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$APP/AndroidManifest.xml" \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code 1 --version-name 1.0.0 \
  --java "$OUT/gen" \
  "${R_ARGS[@]}" \
  --auto-add-overlay

echo "== 4. kotlinc =="
find "$APP/java" -name '*.kt' > "$OUT/sources.txt"
find "$OUT/gen" -name '*.java' >> "$OUT/sources.txt"
CP="$ANDROID_JAR:$(paste -sd: "$OUT/cp.txt")"
"$HOME/workspace/.sdktmp/kotlinc/bin/kotlinc" \
  -cp "$CP" \
  -d "$OUT/classes" \
  -jvm-target 17 \
  -no-reflect \
  @"$OUT/sources.txt" > "$OUT/kotlinc.log" 2>&1 || {
    echo "KOTLINC FAILED:"; grep -E "error:" "$OUT/kotlinc.log" | head -20; exit 1
  }
grep -cE "warning:" "$OUT/kotlinc.log" | xargs echo "kotlinc warnings:"
echo "classes: $(find "$OUT/classes" -name '*.class' | wc -l)"

echo "== 5. d8 (dex) =="
mapfile -t CP_JARS < "$OUT/cp.txt"
"$JAVA_HOME/bin/jar" cf "$OUT/app-classes.jar" -C "$OUT/classes" .
# NOTE: build-tools 34's bundled d8 (8.2.2-dev) crashes with an NPE on
# appcompat's ActionBarDrawerToggle$1; use standalone R8 8.13.25 instead.
D8="$JAVA_HOME/bin/java -cp $HOME/workspace/.sdktmp/r8.jar com.android.tools.r8.D8"
$D8 --lib "$ANDROID_JAR" --min-api 26 \
  --output "$OUT/dex" \
  "$OUT/app-classes.jar" "${CP_JARS[@]}"

echo "== 6. package + align + sign =="
cp "$OUT/base.apk" "$OUT/app-unsigned.apk"
(cd "$OUT/dex" && zip -q -j "$OUT/app-unsigned.apk" classes.dex classes2.dex 2>/dev/null || zip -q -j "$OUT/app-unsigned.apk" classes*.dex)
if [ ! -f "$HOME/.android/debug.keystore" ]; then
  mkdir -p "$HOME/.android"
  keytool -genkeypair -keystore "$HOME/.android/debug.keystore" \
    -alias androiddebugkey -storepass android -keypass android \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=Android Debug,O=Android,C=US" -noprompt
fi
"$BT/zipalign" -p -f 4 "$OUT/app-unsigned.apk" "$OUT/app-debug.apk"
"$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" \
  --ks-pass pass:android --key-pass pass:android \
  --out "$OUT/VrShare-debug.apk" "$OUT/app-debug.apk"
"$BT/apksigner" verify --print-certs "$OUT/VrShare-debug.apk" | head -3
ls -la "$OUT/VrShare-debug.apk"
echo "BUILD OK"

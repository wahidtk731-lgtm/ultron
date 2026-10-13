#!/usr/bin/env bash
# ==============================================================================
# Ultron Assistant - One-Command APK Builder (Termux & Linux)
# ==============================================================================

set -e

# Unset JAVA_HOME so Termux dalvikvm + ecj runs reliably without jrt provider error
unset JAVA_HOME

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

echo "=================================================================="
echo "  🤖 Compiling Ultron Assistant Android APK"
echo "=================================================================="

mkdir -p bin obj gen ~/.android-sdk

# 0. Fetch Voice Assets (Vosk + JNA + Model) if needed
bash scripts/fetch_voice_assets.sh

# 1. Locate android.jar
ANDROID_JAR="$HOME/.android-sdk/android.jar"
if [ ! -f "$ANDROID_JAR" ]; then
    echo "[-] Downloading Android platform SDK jar (~20MB)..."
    curl -L "https://raw.githubusercontent.com/Sable/android-platforms/master/android-30/android.jar" -o "$ANDROID_JAR"
fi

# 2. Generate R.java
echo "[1/6] Generating R.java with AAPT..."
aapt package -f -m \
    -J gen \
    -M android/app/src/main/AndroidManifest.xml \
    -S android/app/src/main/res \
    -I "$ANDROID_JAR"

# 3. Compile Java sources
echo "[2/6] Compiling Java classes..."
rm -rf obj/*
EXTRA_CP="vendor/vosk/classes.jar:vendor/jna/classes.jar"
if [ "${USE_JAVAC:-0}" = "1" ] && command -v javac >/dev/null 2>&1; then
    javac -source 7 -target 7 -d obj -cp "$ANDROID_JAR:gen:$EXTRA_CP" \
        $(find android/app/src/main/java gen -name "*.java")
else
    # Termux dalvikvm + ecj compiler (Target Java 7 for 100% reliable Dalvik bytecode on Android 9+)
    dalvikvm -Xmx256m -cp /data/data/com.termux/files/usr/share/dex/ecj.jar \
        org.eclipse.jdt.internal.compiler.batch.Main -proc:none -7 \
        -cp "$ANDROID_JAR:gen:$EXTRA_CP" -d obj \
        $(find android/app/src/main/java gen -name "*.java")
fi

# 4. Dex bytecode
echo "[3/6] Converting classes to DEX bytecode..."
if command -v d8 >/dev/null 2>&1; then
    d8 --min-api 21 --lib "$ANDROID_JAR" --output bin/ $(find obj -name "*.class") vendor/vosk/classes.jar vendor/jna/classes.jar
else
    dx --dex --output=bin/classes.dex obj vendor/vosk/classes.jar vendor/jna/classes.jar
fi

# 5. Package resources and assets (including bundled Vosk model)
echo "[4/6] Staging assets and packaging APK..."
rm -rf build/assets-stage
mkdir -p build/assets-stage/vosk-model
cp -r android/app/src/main/assets/* build/assets-stage/
cp -r vendor/model/vosk-model-small-en-us-0.15/* build/assets-stage/vosk-model/
echo "vosk-model-small-en-us-0.15" > build/assets-stage/vosk-model/ULTRON_MODEL_VERSION

aapt package -f \
    -M android/app/src/main/AndroidManifest.xml \
    -S android/app/src/main/res \
    -A build/assets-stage \
    -I "$ANDROID_JAR" \
    -F bin/UltronAssistant-unaligned.apk

(cd bin && aapt add UltronAssistant-unaligned.apk classes.dex)

# Add native libraries (arm64-v8a and armeabi-v7a)
echo "Adding native libraries (arm64-v8a, armeabi-v7a)..."
rm -rf build/native-stage
mkdir -p build/native-stage/lib/arm64-v8a build/native-stage/lib/armeabi-v7a
cp vendor/native/arm64-v8a/*.so build/native-stage/lib/arm64-v8a/
cp vendor/native/armeabi-v7a/*.so build/native-stage/lib/armeabi-v7a/
(cd build/native-stage && aapt add ../../bin/UltronAssistant-unaligned.apk \
    lib/arm64-v8a/libvosk.so lib/arm64-v8a/libjnidispatch.so \
    lib/armeabi-v7a/libvosk.so lib/armeabi-v7a/libjnidispatch.so)

# 6. Sign APK
echo "[5/6] Signing APK (v1 JAR SHA-256 + SHA-1)..."
python3 scripts/sign_apk.py bin/UltronAssistant-unaligned.apk bin/UltronAssistant-signed.apk

# 7. Align APK
echo "[6/6] Zipaligning APK (4-byte alignment)..."
zipalign -f -p 4 bin/UltronAssistant-signed.apk bin/UltronAssistant.apk
zipalign -c -v 4 bin/UltronAssistant.apk

# Verify signature integrity with Dalvik JarFile
echo "Verifying cryptographic signatures..."
dalvikvm -Xmx256m -cp bin/TestVerify.dex TestVerify bin/UltronAssistant.apk | grep "VERIFY COMPLETED SUCCESSFULLY"

# 8. Copy to internal storage Project folder and repository root
cp bin/UltronAssistant.apk UltronAssistant.apk
PROJECT_DIR="/storage/emulated/0/Project"
if [ -d "$PROJECT_DIR" ]; then
    cp bin/UltronAssistant.apk "$PROJECT_DIR/UltronAssistant.apk"
    if [ -d "$PROJECT_DIR/UltronAssistant-APK" ]; then
        cp bin/UltronAssistant.apk "$PROJECT_DIR/UltronAssistant-APK/UltronAssistant.apk"
    fi
    echo "✅ Exported to: $PROJECT_DIR/UltronAssistant.apk"
    echo "✅ Exported to: $PROJECT_DIR/UltronAssistant-APK/UltronAssistant.apk"
fi

echo "=================================================================="
echo "🎉 UltronAssistant.apk successfully compiled & signed!"
ls -lh bin/UltronAssistant.apk
echo "=================================================================="

#!/usr/bin/env bash
# ==============================================================================
# Ultron Assistant - One-Command APK Builder (Termux & Linux)
# ==============================================================================

set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

echo "=================================================================="
echo "  🤖 Compiling Ultron Assistant Android APK"
echo "=================================================================="

mkdir -p bin obj gen ~/.android-sdk

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
if command -v javac >/dev/null 2>&1; then
    javac -source 7 -target 7 -d obj -cp "$ANDROID_JAR:gen" \
        $(find android/app/src/main/java gen -name "*.java")
else
    # Termux dalvikvm + ecj compiler (Target Java 7 for 100% reliable Dalvik bytecode on Android 9+)
    dalvikvm -Xmx256m -cp /data/data/com.termux/files/usr/share/dex/ecj.jar \
        org.eclipse.jdt.internal.compiler.batch.Main -proc:none -7 \
        -cp "$ANDROID_JAR:gen" -d obj \
        $(find android/app/src/main/java gen -name "*.java")
fi

# 4. Dex bytecode
echo "[3/6] Converting classes to DEX bytecode..."
if command -v d8 >/dev/null 2>&1; then
    d8 --min-api 21 --output bin/ $(find obj -name "*.class")
else
    dx --dex --output=bin/classes.dex obj
fi

# 5. Package resources and assets
echo "[4/6] Packaging resources and assets..."
aapt package -f \
    -M android/app/src/main/AndroidManifest.xml \
    -S android/app/src/main/res \
    -A android/app/src/main/assets \
    -I "$ANDROID_JAR" \
    -F bin/UltronAssistant-unaligned.apk

(cd bin && aapt add UltronAssistant-unaligned.apk classes.dex)

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

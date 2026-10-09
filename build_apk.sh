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
    javac -source 11 -target 11 -d obj -cp "$ANDROID_JAR:gen" \
        $(find android/app/src/main/java gen -name "*.java")
else
    # Termux dalvikvm + ecj compiler
    mkdir -p "$HOME/stubs/java/lang/invoke"
    if [ ! -f "$HOME/stubs/java/lang/invoke/LambdaMetafactory.class" ]; then
        cat << 'EOF' > "$HOME/stubs/java/lang/invoke/LambdaMetafactory.java"
package java.lang.invoke;
public final class LambdaMetafactory {
    public static CallSite metafactory(MethodHandles.Lookup caller, String invokedName, MethodType invokedType, MethodType samMethodType, MethodHandle implMethod, MethodType instantiatedMethodType) throws LambdaConversionException { return null; }
    public static CallSite altMetafactory(MethodHandles.Lookup caller, String invokedName, MethodType invokedType, Object... args) throws LambdaConversionException { return null; }
}
EOF
        dalvikvm -Xmx256m -cp /data/data/com.termux/files/usr/share/dex/ecj.jar \
            org.eclipse.jdt.internal.compiler.batch.Main -proc:none -8 \
            -cp "$ANDROID_JAR" -d "$HOME/stubs" "$HOME/stubs/java/lang/invoke/LambdaMetafactory.java"
    fi

    dalvikvm -Xmx256m -cp /data/data/com.termux/files/usr/share/dex/ecj.jar \
        org.eclipse.jdt.internal.compiler.batch.Main -proc:none -8 \
        -cp "$ANDROID_JAR:$HOME/stubs:gen" -d obj \
        $(find android/app/src/main/java gen -name "*.java")
fi

# 4. Dex bytecode
echo "[3/6] Converting classes to DEX bytecode..."
if command -v d8 >/dev/null 2>&1; then
    d8 --min-api 26 --output bin/ $(find obj -name "*.class")
else
    dx --dex --min-sdk-version=26 --output=bin/classes.dex obj
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

# 6. Align APK
echo "[5/6] Zipaligning APK..."
zipalign -f -p 4 bin/UltronAssistant-unaligned.apk bin/UltronAssistant-aligned.apk

# 7. Sign APK
echo "[6/6] Signing APK..."
python3 scripts/sign_apk.py bin/UltronAssistant-aligned.apk bin/UltronAssistant.apk

# 8. Copy to /sdcard/Download
if [ -d "/sdcard/Download" ]; then
    cp bin/UltronAssistant.apk /sdcard/Download/UltronAssistant.apk
    echo "✅ Exported to: /sdcard/Download/UltronAssistant.apk"
fi

echo "=================================================================="
echo "🎉 UltronAssistant.apk successfully compiled & signed!"
ls -lh bin/UltronAssistant.apk
echo "=================================================================="

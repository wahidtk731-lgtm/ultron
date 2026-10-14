#!/usr/bin/env bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$DIR"

echo "=== Building Ultron Native SAM Synthesizer Libraries ==="
mkdir -p vendor/native/arm64-v8a vendor/native/armeabi-v7a

JNI_INC="/data/data/com.termux/files/usr/include"
if [ ! -f "$JNI_INC/jni.h" ]; then
    JNI_INC="$(dirname "$(find /usr -name jni.h 2>/dev/null | head -1)")"
fi

if command -v clang >/dev/null 2>&1; then
    echo "[-] Compiling libultronsam.so (arm64-v8a)..."
    clang -O2 -fPIC -shared -fcommon \
        -I"$JNI_INC" \
        native/sam/jni_sam.c native/sam/reciter.c native/sam/render.c native/sam/sam.c native/sam/debug.c \
        -o vendor/native/arm64-v8a/libultronsam.so

    echo "[-] Compiling libultronsam.so (armeabi-v7a)..."
    clang -target armv7a-linux-androideabi21 -shared -fPIC -nostdlib -Wl,-shared \
        -I"$JNI_INC" \
        native/sam/aeabi_stub.c native/sam/jni_sam.c native/sam/reciter.c native/sam/render.c native/sam/sam.c native/sam/debug.c \
        -fcommon \
        -o vendor/native/armeabi-v7a/libultronsam.so

    echo "[+] SAM libraries built successfully:"
    ls -lh vendor/native/*/libultronsam.so
else
    echo "[!] clang not found, skipping SAM native compilation"
fi

#!/usr/bin/env bash
# ==============================================================================
# Ultron Assistant - Fetch Voice Assets (Vosk + JNA + Model)
# Idempotent script filling ./vendor for offline speech recognition.
# Runs only at build time / CI. The Android app NEVER downloads anything.
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
VENDOR_DIR="${ROOT_DIR}/vendor"
TMP_DIR="${VENDOR_DIR}/.tmp"

# Model configuration: Change MODEL_NAME to another variant if desired
MODEL_NAME="${VOSK_MODEL_NAME:-vosk-model-small-en-us-0.15}"
VOSK_VERSION="${VOSK_VERSION:-0.3.47}"

# Verified Maven Central URLs
MAVEN_BASE="https://repo1.maven.org/maven2"
VOSK_AAR_URL="${MAVEN_BASE}/com/alphacephei/vosk-android/${VOSK_VERSION}/vosk-android-${VOSK_VERSION}.aar"

# Read JNA version from POM if not set
if [ -z "${JNA_VERSION:-}" ]; then
    POM_URL="${MAVEN_BASE}/com/alphacephei/vosk-android/${VOSK_VERSION}/vosk-android-${VOSK_VERSION}.pom"
    JNA_VERSION="$(curl -sSL "${POM_URL}" | grep -A 2 "<groupId>net.java.dev.jna</groupId>" | grep "<version>" | sed -e 's/.*<version>//' -e 's/<\/version>.*//' | tr -d '[:space:]')"
    if [ -z "${JNA_VERSION}" ]; then
        JNA_VERSION="5.13.0"
    fi
fi
JNA_AAR_URL="${MAVEN_BASE}/net/java/dev/jna/jna/${JNA_VERSION}/jna-${JNA_VERSION}.aar"

# Check if already completed and verified
if [ -f "${VENDOR_DIR}/vosk/classes.jar" ] && \
   [ -f "${VENDOR_DIR}/jna/classes.jar" ] && \
   [ -f "${VENDOR_DIR}/native/arm64-v8a/libvosk.so" ] && \
   [ -f "${VENDOR_DIR}/native/arm64-v8a/libjnidispatch.so" ] && \
   [ -f "${VENDOR_DIR}/native/armeabi-v7a/libvosk.so" ] && \
   [ -f "${VENDOR_DIR}/native/armeabi-v7a/libjnidispatch.so" ] && \
   [ -d "${VENDOR_DIR}/model/${MODEL_NAME}" ] && \
   [ -f "${VENDOR_DIR}/.fetch_complete_${MODEL_NAME}_${VOSK_VERSION}" ]; then
    echo "[✓] Voice assets already fetched and verified in ${VENDOR_DIR}"
    exit 0
fi

echo "=================================================================="
echo "  🎙️ Fetching Offline Voice Assets (Vosk ${VOSK_VERSION}, JNA ${JNA_VERSION}, Model ${MODEL_NAME})"
echo "=================================================================="

mkdir -p "${VENDOR_DIR}" "${TMP_DIR}"

# 1. Download Vosk Android AAR
VOSK_AAR="${TMP_DIR}/vosk-android-${VOSK_VERSION}.aar"
echo "[-] Downloading Vosk Android AAR (${VOSK_VERSION})..."
curl -sSL -f "${VOSK_AAR_URL}" -o "${VOSK_AAR}"
EXPECTED_VOSK_MD5="$(curl -sSL -f "${VOSK_AAR_URL}.md5" | tr -d '[:space:]')"
ACTUAL_VOSK_MD5="$(md5sum "${VOSK_AAR}" | awk '{print $1}')"
if [ "${ACTUAL_VOSK_MD5}" != "${EXPECTED_VOSK_MD5}" ]; then
    echo "[!] Checksum mismatch for Vosk AAR: expected ${EXPECTED_VOSK_MD5}, got ${ACTUAL_VOSK_MD5}" >&2
    exit 1
fi
echo "[✓] Vosk AAR verified (MD5: ${ACTUAL_VOSK_MD5})"

# 2. Download JNA AAR
JNA_AAR="${TMP_DIR}/jna-${JNA_VERSION}.aar"
echo "[-] Downloading JNA AAR (${JNA_VERSION})..."
curl -sSL -f "${JNA_AAR_URL}" -o "${JNA_AAR}"
EXPECTED_JNA_MD5="$(curl -sSL -f "${JNA_AAR_URL}.md5" | tr -d '[:space:]')"
ACTUAL_JNA_MD5="$(md5sum "${JNA_AAR}" | awk '{print $1}')"
if [ "${ACTUAL_JNA_MD5}" != "${EXPECTED_JNA_MD5}" ]; then
    echo "[!] Checksum mismatch for JNA AAR: expected ${EXPECTED_JNA_MD5}, got ${ACTUAL_JNA_MD5}" >&2
    exit 1
fi
echo "[✓] JNA AAR verified (MD5: ${ACTUAL_JNA_MD5})"

# 3. Model info from model-list.json
MODEL_LIST_URL="https://alphacephei.com/vosk/models/model-list.json"
echo "[-] Querying model metadata for ${MODEL_NAME}..."
MODEL_INFO_FILE="${TMP_DIR}/model-list.json"
curl -sSL -f "${MODEL_LIST_URL}" -o "${MODEL_INFO_FILE}"

MODEL_DATA="$(python3 -c '
import sys, json
model_name = sys.argv[1]
with open(sys.argv[2], "r") as f:
    models = json.load(f)
found = [m for m in models if m.get("name") == model_name]
if not found:
    sys.exit(1)
m = found[0]
print("{} {} {}".format(m["url"], m["size"], m["md5"]))
' "${MODEL_NAME}" "${MODEL_INFO_FILE}")"

if [ -z "${MODEL_DATA}" ]; then
    echo "[!] Model ${MODEL_NAME} not found in model-list.json!" >&2
    exit 1
fi

MODEL_URL="$(echo "${MODEL_DATA}" | awk '{print $1}')"
EXPECTED_MODEL_SIZE="$(echo "${MODEL_DATA}" | awk '{print $2}')"
EXPECTED_MODEL_MD5="$(echo "${MODEL_DATA}" | awk '{print $3}')"

echo "[-] Downloading model ${MODEL_NAME} (~${EXPECTED_MODEL_SIZE} bytes)..."
MODEL_ZIP="${TMP_DIR}/${MODEL_NAME}.zip"
curl -sSL -f "${MODEL_URL}" -o "${MODEL_ZIP}"

ACTUAL_MODEL_SIZE="$(wc -c < "${MODEL_ZIP}" | tr -d '[:space:]')"
if [ "${ACTUAL_MODEL_SIZE}" != "${EXPECTED_MODEL_SIZE}" ]; then
    echo "[!] Size mismatch for ${MODEL_NAME}: expected ${EXPECTED_MODEL_SIZE}, got ${ACTUAL_MODEL_SIZE}" >&2
    exit 1
fi

ACTUAL_MODEL_MD5="$(md5sum "${MODEL_ZIP}" | awk '{print $1}')"
if [ "${ACTUAL_MODEL_MD5}" != "${EXPECTED_MODEL_MD5}" ]; then
    echo "[!] Checksum mismatch for ${MODEL_NAME}: expected ${EXPECTED_MODEL_MD5}, got ${ACTUAL_MODEL_MD5}" >&2
    exit 1
fi
echo "[✓] Model archive verified (MD5: ${ACTUAL_MODEL_MD5})"

# 4. Extract and stage into vendor layout
echo "[-] Extracting components into ${VENDOR_DIR}..."
mkdir -p "${VENDOR_DIR}/vosk" "${VENDOR_DIR}/jna" \
         "${VENDOR_DIR}/native/arm64-v8a" "${VENDOR_DIR}/native/armeabi-v7a" \
         "${VENDOR_DIR}/model"

# Unpack Vosk AAR
unzip -q -o "${VOSK_AAR}" classes.jar -d "${VENDOR_DIR}/vosk"
unzip -q -o "${VOSK_AAR}" "jni/arm64-v8a/libvosk.so" -d "${TMP_DIR}/vosk_jni"
cp "${TMP_DIR}/vosk_jni/jni/arm64-v8a/libvosk.so" "${VENDOR_DIR}/native/arm64-v8a/"
unzip -q -o "${VOSK_AAR}" "jni/armeabi-v7a/libvosk.so" -d "${TMP_DIR}/vosk_jni"
cp "${TMP_DIR}/vosk_jni/jni/armeabi-v7a/libvosk.so" "${VENDOR_DIR}/native/armeabi-v7a/"

# Unpack JNA AAR
unzip -q -o "${JNA_AAR}" classes.jar -d "${VENDOR_DIR}/jna"
unzip -q -o "${JNA_AAR}" "jni/arm64-v8a/libjnidispatch.so" -d "${TMP_DIR}/jna_jni"
cp "${TMP_DIR}/jna_jni/jni/arm64-v8a/libjnidispatch.so" "${VENDOR_DIR}/native/arm64-v8a/"
unzip -q -o "${JNA_AAR}" "jni/armeabi-v7a/libjnidispatch.so" -d "${TMP_DIR}/jna_jni"
cp "${TMP_DIR}/jna_jni/jni/armeabi-v7a/libjnidispatch.so" "${VENDOR_DIR}/native/armeabi-v7a/"

# Unpack model
rm -rf "${VENDOR_DIR}/model/${MODEL_NAME}"
unzip -q -o "${MODEL_ZIP}" -d "${VENDOR_DIR}/model"

# Cleanup temp downloads
rm -rf "${TMP_DIR}"

# Write completion marker
touch "${VENDOR_DIR}/.fetch_complete_${MODEL_NAME}_${VOSK_VERSION}"

echo "=================================================================="
echo "  [✓] All offline voice assets extracted successfully!"
echo "=================================================================="

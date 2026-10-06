#!/usr/bin/env bash
# ==============================================================================
# Ultron Assistant - Zero-Bloat APK Build & Export Tool
# ==============================================================================

set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

echo "=================================================================="
echo "  🤖 Ultron Assistant - Android APK Converter"
echo "=================================================================="
echo ""
echo "Ultron has been ported to Android with:"
echo "  • Pixel-perfect Google Gemini Stadium Capsule HUD"
echo "  • Native Offline Speech Recognition (Zero Vosk download needed)"
echo "  • Native Offline Text-to-Speech Engine"
echo "  • Phonetic Normalizer ('all thrown' -> 'hey ultron')"
echo "  • Android Application Launcher & Intent Engine"
echo ""
echo "Select how you would like to package/install the APK:"
echo ""
echo "1) Instant Install (PWA / WebAPK - ZERO build tools required):"
echo "   Run: python3 serve_pwa.py"
echo "   Open http://localhost:8085 in Chrome on Android / ChromeOS and tap 'Install App'."
echo ""
echo "2) Cloud GitHub Actions Build (ZERO local disk space):"
echo "   Push your code to GitHub: git push origin main"
echo "   GitHub Actions will automatically build 'UltronAssistant.apk' in the cloud"
echo "   and make it downloadable under your repository's Actions/Releases tab!"
echo ""
echo "3) Android Studio / Gradle Export:"
echo "   The complete Android project is ready in: android/"
echo "   You can open the android/ folder in any Android Studio or run gradle to build."
echo ""
echo "=================================================================="

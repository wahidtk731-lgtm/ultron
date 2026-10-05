#!/usr/bin/env python3
"""
Microphone Diagnostic & Volume Meter Tool for Ultron
Tests whether audio input is reaching the Python environment.
"""

import sys
import time
import numpy as np

def test_microphone():
    print("=" * 60)
    print("🎙️ ULTRON MICROPHONE DIAGNOSTIC TOOL")
    print("=" * 60)

    try:
        import sounddevice as sd
    except Exception as e:
        print(f"[-] Failed to import sounddevice: {e}")
        return

    # 1. Print devices
    print("\n[1] Detecting Available Audio Input Devices:")
    devices = sd.query_devices()
    input_devs = []
    for idx, d in enumerate(devices):
        if d.get("max_input_channels", 0) > 0:
            default_marker = " (Default)" if idx == sd.default.device[0] else ""
            print(f"    [{idx}] {d['name']} - Channels: {d['max_input_channels']} - SR: {d['default_samplerate']}Hz{default_marker}")
            input_devs.append(idx)

    if not input_devs:
        print("[-] No audio input devices detected by PortAudio/ALSA.")
        return

    # 2. Live volume meter test for 10 seconds
    print("\n[2] Testing Live Microphone Audio Level (Speak into your mic):")
    print("    Press Ctrl+C to stop.\n")

    max_seen = 0
    zero_count = 0

    def callback(indata, frames, time_info, status):
        nonlocal max_seen, zero_count
        if status:
            print(f"\n[!] Status: {status}", file=sys.stderr)
        
        # Calculate RMS volume level
        rms = np.sqrt(np.mean(indata**2))
        peak = np.max(np.abs(indata))
        if peak > max_seen:
            max_seen = peak

        bars = int(peak * 40)
        meter = "█" * min(bars, 40) + " " * max(0, 40 - bars)

        if peak < 0.001:
            zero_count += 1
        else:
            zero_count = 0

        sys.stdout.write(f"\rLevel: [{meter}] Peak: {peak:.3f}")
        sys.stdout.flush()

    try:
        with sd.InputStream(samplerate=16000, channels=1, dtype="float32", callback=callback):
            for _ in range(100):  # Run for ~10 seconds
                time.sleep(0.1)
    except KeyboardInterrupt:
        pass
    except Exception as e:
        print(f"\n[-] Failed to open audio stream: {e}")
        return

    print("\n\n" + "=" * 60)
    print("📊 DIAGNOSTIC RESULTS:")
    if max_seen > 0.02:
        print(f"✅ Microphone is WORKING! Detected audio input (Peak: {max_seen:.3f}).")
    elif max_seen > 0:
        print(f"⚠️ Microphone detected very quiet input (Peak: {max_seen:.3f}). Check your mic volume.")
    else:
        print("❌ Microphone returned pure SILENCE (0.000 level).")
        print("\n💡 HOW TO FIX ON CHROMEOS:")
        print("   1. Open ChromeOS Settings (gear icon).")
        print("   2. Go to 'Developers' (or 'About ChromeOS' -> 'Linux').")
        print("   3. Click on 'Linux development environment'.")
        print("   4. Toggle ON 'Allow Linux to access your microphone'.")
        print("   5. After enabling, rerun: python3 test_mic.py")
    print("=" * 60)

if __name__ == "__main__":
    test_microphone()

#!/usr/bin/env python3
"""
Ultron Assistant - System & Assistant Settings Panel
Provides a sleek, native Settings GUI for Linux & ChromeOS environments.
"""

import os
import sys
import shutil
import subprocess
import tkinter as tk
from tkinter import ttk, messagebox

BG_DARK = "#1E1F22"
BG_CARD = "#2B2D31"
ACCENT_BLUE = "#4285F4"
TEXT_WHITE = "#FFFFFF"
TEXT_MUTED = "#9AA0A6"

class UltronSettingsGUI:
    def __init__(self):
        self.root = tk.Tk()
        self.root.title("Ultron & System Settings")
        self.root.geometry("520x460")
        self.root.configure(bg=BG_DARK)
        self.root.resizable(False, False)

        # Center on screen
        try:
            sw = self.root.winfo_screenwidth()
            sh = self.root.winfo_screenheight()
            x = (sw - 520) // 2
            y = (sh - 460) // 2
            self.root.geometry(f"520x460+{x}+{y}")
        except Exception:
            pass

        self._build_ui()

    def _build_ui(self):
        # Header with Google Colors
        header_frame = tk.Frame(self.root, bg=BG_DARK)
        header_frame.pack(fill="x", padx=20, pady=(15, 10))

        lbl_title = tk.Label(
            header_frame,
            text="⚙️ Ultron Assistant Settings",
            font=("DejaVu Sans", 14, "bold"),
            bg=BG_DARK,
            fg=TEXT_WHITE
        )
        lbl_title.pack(anchor="w")

        lbl_desc = tk.Label(
            header_frame,
            text="Configure assistant voice, input, applications, and system preferences.",
            font=("DejaVu Sans", 9),
            bg=BG_DARK,
            fg=TEXT_MUTED
        )
        lbl_desc.pack(anchor="w", pady=(2, 0))

        # Divider
        div = tk.Frame(self.root, bg="#3A3D44", height=1)
        div.pack(fill="x", padx=20, pady=5)

        # Main Container
        container = tk.Frame(self.root, bg=BG_DARK)
        container.pack(fill="both", expand=True, padx=20, pady=10)

        # Section 1: Voice & Audio
        sec_voice = tk.LabelFrame(
            container,
            text=" Voice & Sound ",
            font=("DejaVu Sans", 10, "bold"),
            bg=BG_CARD,
            fg=ACCENT_BLUE,
            padx=12,
            pady=10
        )
        sec_voice.pack(fill="x", pady=6)

        lbl_voice = tk.Label(
            sec_voice,
            text="Selected Voice: Microsoft Brian (Natural AI Male Voice)",
            font=("DejaVu Sans", 9),
            bg=BG_CARD,
            fg=TEXT_WHITE
        )
        lbl_voice.pack(anchor="w")

        btn_test_voice = tk.Button(
            sec_voice,
            text="🔊 Test Audio Playback",
            font=("DejaVu Sans", 8, "bold"),
            bg=ACCENT_BLUE,
            fg=TEXT_WHITE,
            activebackground="#3367D6",
            relief="flat",
            bd=0,
            padx=10,
            pady=4,
            cursor="hand2",
            command=self._test_voice
        )
        btn_test_voice.pack(anchor="w", pady=(8, 2))

        # Section 2: Microphone & Speech Input
        sec_mic = tk.LabelFrame(
            container,
            text=" Microphone & Listening ",
            font=("DejaVu Sans", 10, "bold"),
            bg=BG_CARD,
            fg=ACCENT_BLUE,
            padx=12,
            pady=10
        )
        sec_mic.pack(fill="x", pady=6)

        lbl_mic_info = tk.Label(
            sec_mic,
            text="Wake words: 'Hey Ultron' or 'Ultron'\nListening Mode: Screen Edge Ambient Bar",
            font=("DejaVu Sans", 9),
            bg=BG_CARD,
            fg=TEXT_WHITE,
            justify="left"
        )
        lbl_mic_info.pack(anchor="w")

        btn_test_mic = tk.Button(
            sec_mic,
            text="🎙️ Run Mic Diagnostic Tool",
            font=("DejaVu Sans", 8),
            bg="#3E4249",
            fg=TEXT_WHITE,
            activebackground="#4E525A",
            relief="flat",
            bd=0,
            padx=10,
            pady=4,
            cursor="hand2",
            command=self._run_mic_diagnostic
        )
        btn_test_mic.pack(anchor="w", pady=(8, 2))

        # Section 3: Applications & ChromeOS System
        sec_sys = tk.LabelFrame(
            container,
            text=" Quick App Launch & System Controls ",
            font=("DejaVu Sans", 10, "bold"),
            bg=BG_CARD,
            fg=ACCENT_BLUE,
            padx=12,
            pady=10
        )
        sec_sys.pack(fill="x", pady=6)

        row_btns = tk.Frame(sec_sys, bg=BG_CARD)
        row_btns.pack(fill="x", pady=4)

        apps_to_launch = [
            ("Sublime Text", lambda: subprocess.Popen(["/usr/bin/subl", "-n"])),
            ("Geany", lambda: subprocess.Popen(["/usr/bin/geany"])),
            ("ChromeOS Settings", self._open_chromeos_guide),
            ("Terminal", lambda: subprocess.Popen(["garcon-terminal-handler"])),
        ]

        for name, cmd in apps_to_launch:
            b = tk.Button(
                row_btns,
                text=name,
                font=("DejaVu Sans", 8),
                bg="#3E4249",
                fg=TEXT_WHITE,
                relief="flat",
                bd=0,
                padx=8,
                pady=4,
                cursor="hand2",
                command=cmd
            )
            b.pack(side="left", padx=4)

        # Bottom Close Button
        btn_close = tk.Button(
            self.root,
            text="Done",
            font=("DejaVu Sans", 9, "bold"),
            bg="#3A3D44",
            fg=TEXT_WHITE,
            relief="flat",
            bd=0,
            padx=20,
            pady=6,
            cursor="hand2",
            command=self.root.destroy
        )
        btn_close.pack(side="right", padx=20, pady=12)

    def _test_voice(self):
        try:
            from tts_engine import TTSEngine
            tts = TTSEngine()
            tts.speak("Audio check complete. All voice systems are operating normally.")
        except Exception as e:
            messagebox.showerror("Audio Error", str(e))

    def _run_mic_diagnostic(self):
        script_dir = os.path.dirname(os.path.abspath(__file__))
        diag = os.path.join(script_dir, "test_mic.py")
        if shutil.which("garcon-terminal-handler"):
            subprocess.Popen(["garcon-terminal-handler", "-e", f"python3 {diag}"])
        else:
            subprocess.Popen(["x-terminal-emulator", "-e", f"python3 {diag}"])

    def _open_chromeos_guide(self):
        messagebox.showinfo(
            "ChromeOS System Settings",
            "To open ChromeOS System Settings:\n\n"
            "1. Press Shift + Alt + S, then click the ⚙️ Gear icon.\n"
            "2. Or click the Clock in the bottom-right corner of your screen and click Settings."
        )

    def run(self):
        self.root.mainloop()

def main():
    app = UltronSettingsGUI()
    app.run()

if __name__ == "__main__":
    main()

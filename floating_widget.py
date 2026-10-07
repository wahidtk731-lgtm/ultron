#!/usr/bin/env python3
"""
Ultron - Google Gemini Assistant Edge HUD
Pixel-perfect, high-polish Google Gemini Assistant floating capsule.
Features:
- Pinned directly at the screen's bottom bezel (no center hovering, zero screen disturbance).
- Sleek 54px Gemini Stadium Pill matching Google Gemini on Android/Pixel.
- Animated Gemini multi-color gradient shimmer loading wave when responding ("Thinking...").
- Strict input lockout: microphone is muted and text entry is locked while speaking.
  Typing and speech are strictly prevented until Ultron finishes speaking.
- Exact 0.5s listening silence timeout and calm 0.3s processing pacing.
- Real local File Manager (PCManFM) and Settings integration (never opens Google Chrome).
- Direct Text Input outside terminal: Type in 'Ask Gemini' and press Enter.
"""

import os
import sys
import time
import math
import json
import queue
import random
import threading
import tkinter as tk

from ultron import UltronAssistant
from speech_normalizer import normalize_speech, is_wake_word, strip_wake_words
from speech_recognizer_hybrid import HybridSpeechRecognizer

# Polished Google Gemini Material Palette
GEMINI_BG = "#1E1F22"
GEMINI_BORDER = "#303238"
GEMINI_PILL_BTN = "#282A2E"
GEMINI_HOVER = "#353840"
GEMINI_BLUE = "#1A73E8"
GEMINI_BLUE_ACTIVE = "#4285F4"
GEMINI_RED = "#EA4335"
GEMINI_AMBER = "#FBBC05"
GEMINI_GREEN = "#34A853"
GEMINI_PURPLE = "#9B51E0"
GEMINI_CYAN = "#00E5FF"
GEMINI_PINK = "#EC4899"
GEMINI_HANDLE = "#4F525A"
TEXT_WHITE = "#FFFFFF"
TEXT_MUTED = "#9AA0A6"
TEXT_PLACEHOLDER = "#8E9196"

# Iconic Gemini Multi-Color Gradient Stops
GEMINI_GRADIENT_PALETTE = [
    (66, 133, 244),   # #4285F4 Google Blue
    (155, 81, 224),   # #9B51E0 Gemini Purple
    (236, 72, 153),   # #EC4899 Gemini Pink
    (251, 188, 5),    # #FBBC05 Google Amber
    (0, 229, 255),    # #00E5FF Cyan
    (52, 168, 83),    # #34A853 Google Green
]

def get_gemini_gradient_color(phase):
    """Interpolates smoothly through the official Gemini gradient spectrum."""
    p = phase % len(GEMINI_GRADIENT_PALETTE)
    i1 = int(p)
    i2 = (i1 + 1) % len(GEMINI_GRADIENT_PALETTE)
    frac = p - i1
    r = int(GEMINI_GRADIENT_PALETTE[i1][0] * (1 - frac) + GEMINI_GRADIENT_PALETTE[i2][0] * frac)
    g = int(GEMINI_GRADIENT_PALETTE[i1][1] * (1 - frac) + GEMINI_GRADIENT_PALETTE[i2][1] * frac)
    b = int(GEMINI_GRADIENT_PALETTE[i1][2] * (1 - frac) + GEMINI_GRADIENT_PALETTE[i2][2] * frac)
    return f"#{r:02x}{g:02x}{b:02x}"

class UltronGeminiWidget:
    def __init__(self):
        self.root = tk.Tk()
        self.root.title("Ultron Gemini Assistant")

        # Frameless, stays on top of all windows
        self.root.overrideredirect(True)
        self.root.attributes("-topmost", True)
        self.root.configure(bg=GEMINI_BG)

        # Detect Screen dimensions
        try:
            self.screen_w = self.root.winfo_screenwidth()
            self.screen_h = self.root.winfo_screenheight()
        except Exception:
            self.screen_w = 1187
            self.screen_h = 667

        # Capsule dimensions: Width 480px, Height 54px (standard bottom bar)
        self.cap_w = 480
        self.cap_h = 54

        # Pinned directly at the screen's bottom bezel (4px cushion above screen bottom)
        self.pos_x = (self.screen_w - self.cap_w) // 2
        self.pos_y = self.screen_h - self.cap_h - 4
        self.root.geometry(f"{self.cap_w}x{self.cap_h}+{self.pos_x}+{self.pos_y}")

        # Dragging state
        self.drag_start_x = 0
        self.drag_start_y = 0

        # Assistant state: "ready", "listening", "processing", "speaking"
        self.state = "ready"
        self.running = True
        self.is_mic_muted = False
        self.is_compact = False

        # Live wave and loading animation state
        self.anim_tick = 0
        self.wave_phase = 0.0

        # Queues
        self.ui_queue = queue.Queue()
        self.audio_queue = queue.Queue()

        # Build UI
        self._init_ui()
        self._bind_events()

        # Initialize Ultron core & TTS finish callback
        self.assistant = UltronAssistant()
        self.assistant.tts.set_callback(self._on_assistant_speak)
        self.assistant.tts.set_finish_callback(self._on_assistant_finish_speaking)

        self.hybrid_stt = HybridSpeechRecognizer(sample_rate=16000)
        self.recognizer = None
        self.audio_stream = None

        # Start decoupled audio loop with 0.5s silence endpointing & speech muting
        self.audio_thread = threading.Thread(target=self._fast_audio_loop, daemon=True)
        self.audio_thread.start()

        # UI & Animation Loops (~30ms = 33 FPS)
        self.root.after(30, self._process_ui_queue)
        self.root.after(30, self._animation_loop)

    def _init_ui(self):
        """Constructs the polished Google Gemini Stadium Capsule."""
        self.canvas = tk.Canvas(
            self.root,
            width=self.cap_w,
            height=self.cap_h,
            bg=GEMINI_BG,
            highlightthickness=0,
            cursor="arrow"
        )
        self.canvas.pack(fill="both", expand=True)

        w = self.cap_w
        h = self.cap_h
        r = h // 2  # radius = 27
        cy = h // 2 # center vertical = 27

        # 1. Base Stadium Capsule Shape (Smooth rounded corners)
        # Left semi-circle
        self.cap_left = self.canvas.create_oval(
            1, 1, 2 * r - 1, h - 1,
            fill=GEMINI_BG, outline=GEMINI_BORDER, width=1
        )
        # Right semi-circle
        self.cap_right = self.canvas.create_oval(
            w - 2 * r + 1, 1, w - 1, h - 1,
            fill=GEMINI_BG, outline=GEMINI_BORDER, width=1
        )
        # Center rectangle
        self.cap_rect = self.canvas.create_rectangle(
            r, 1, w - r, h - 1,
            fill=GEMINI_BG, outline=""
        )
        # Bottom border line
        self.border_bot = self.canvas.create_line(
            r, h - 1, w - r, h - 1,
            fill=GEMINI_BORDER, width=1
        )

        # 2. Multi-Segment Top Shimmer Border (For animated Gemini gradient loading wave)
        self.shimmer_segments = []
        num_segments = 32
        start_x = r
        end_x = w - r
        seg_w = (end_x - start_x) / num_segments
        for i in range(num_segments):
            x1 = start_x + i * seg_w
            x2 = start_x + (i + 1) * seg_w
            seg = self.canvas.create_line(
                x1, 1, x2, 1,
                fill=GEMINI_BORDER, width=1
            )
            self.shimmer_segments.append(seg)

        # 3. Subtle Drag Handle at top center
        handle_w = 26
        hx1 = (w - handle_w) // 2
        hx2 = hx1 + handle_w
        self.drag_handle = self.canvas.create_line(
            hx1, 4, hx2, 4,
            fill=GEMINI_HANDLE, width=2, capstyle="round"
        )

        # 4. Left Element 1: Google Plus Button '+' (Add context / Quick suggestions)
        self.btn_plus_bg = self.canvas.create_oval(
            10, 13, 38, 41,
            fill=GEMINI_PILL_BTN, outline=""
        )
        self.btn_plus = self.canvas.create_text(
            24, cy,
            text="+",
            font=("DejaVu Sans", 13, "bold"),
            fill="#E8EAED"
        )

        # 5. Left Element 2: Gemini Sparkle Icon '✦'
        self.sparkle = self.canvas.create_text(
            52, cy,
            text="✦",
            font=("DejaVu Sans", 12, "bold"),
            fill="#8AB4F8"
        )

        # 6. Center: Seamless Text Entry ("Ask Gemini")
        entry_x = 76
        entry_w = 282
        entry_h = 26

        self.text_entry = tk.Entry(
            self.root,
            font=("DejaVu Sans", 10),
            bg=GEMINI_BG,
            fg=TEXT_WHITE,
            disabledbackground=GEMINI_BG,
            disabledforeground="#C58AF9",
            insertbackground=GEMINI_BLUE_ACTIVE,
            relief="flat",
            bd=0,
            highlightthickness=0
        )
        self.text_entry.insert(0, "Ask Gemini")
        self.text_entry.config(fg=TEXT_PLACEHOLDER)

        self.entry_window = self.canvas.create_window(
            entry_x + entry_w // 2, cy,
            window=self.text_entry,
            width=entry_w,
            height=entry_h
        )

        # 7. Right Element 1: Blue Circular Mic Button
        mic_cx = 388
        mic_r = 16
        self.mic_circle = self.canvas.create_oval(
            mic_cx - mic_r, cy - mic_r, mic_cx + mic_r, cy + mic_r,
            fill=GEMINI_BLUE,
            outline=""
        )
        self.mic_icon = self.canvas.create_text(
            mic_cx, cy,
            text="🎙",
            font=("DejaVu Sans", 10),
            fill=TEXT_WHITE
        )

        # 8. Right Element 2: Soundwave Equalizer
        wave_cx = 414
        self.wave_bars = []
        bar_offsets = [-7, -2, 3, 8]
        for off in bar_offsets:
            bar = self.canvas.create_line(
                wave_cx + off, cy - 4, wave_cx + off, cy + 4,
                fill=TEXT_MUTED,
                width=2,
                capstyle="round"
            )
            self.wave_bars.append(bar)

        # 9. Right Element 3: Minimize '─'
        self.btn_minimize = self.canvas.create_text(
            442, cy,
            text="─",
            font=("DejaVu Sans", 10, "bold"),
            fill="#8E9196"
        )

        # 10. Right Element 4: Close '×'
        self.btn_close = self.canvas.create_text(
            464, cy,
            text="×",
            font=("DejaVu Sans", 11, "bold"),
            fill="#5E636E"
        )

    def _bind_events(self):
        """Binds mouse and keyboard interactions."""
        # Dragging support
        self.canvas.bind("<Button-1>", self._on_mouse_down)
        self.canvas.bind("<B1-Motion>", self._on_mouse_drag)
        self.canvas.bind("<Double-Button-1>", self._on_double_click)

        # Text Entry placeholder behavior & Submit on Enter
        self.text_entry.bind("<FocusIn>", self._on_entry_focus_in)
        self.text_entry.bind("<FocusOut>", self._on_entry_focus_out)
        self.text_entry.bind("<Return>", lambda e: self._on_text_submit())
        self.text_entry.bind("<Key>", self._on_entry_key)

        # Plus '+' Button -> Quick action suggestions
        self.canvas.tag_bind(self.btn_plus, "<Button-1>", lambda e: self._show_quick_actions())
        self.canvas.tag_bind(self.btn_plus_bg, "<Button-1>", lambda e: self._show_quick_actions())
        self.canvas.tag_bind(self.btn_plus_bg, "<Enter>", lambda e: self.canvas.itemconfig(self.btn_plus_bg, fill=GEMINI_HOVER))
        self.canvas.tag_bind(self.btn_plus_bg, "<Leave>", lambda e: self.canvas.itemconfig(self.btn_plus_bg, fill=GEMINI_PILL_BTN))
        self.canvas.tag_bind(self.btn_plus, "<Enter>", lambda e: self.canvas.itemconfig(self.btn_plus_bg, fill=GEMINI_HOVER))
        self.canvas.tag_bind(self.btn_plus, "<Leave>", lambda e: self.canvas.itemconfig(self.btn_plus_bg, fill=GEMINI_PILL_BTN))

        # Click Sparkle '✦' -> Trigger Voice Listening
        self.canvas.tag_bind(self.sparkle, "<Button-1>", lambda e: self.trigger_listening())

        # Click Mic Circle / Icon -> Trigger Voice Listening
        self.canvas.tag_bind(self.mic_circle, "<Button-1>", lambda e: self.trigger_listening())
        self.canvas.tag_bind(self.mic_icon, "<Button-1>", lambda e: self.trigger_listening())

        # Click Wave Bars -> Trigger Voice Listening
        for b in self.wave_bars:
            self.canvas.tag_bind(b, "<Button-1>", lambda e: self.trigger_listening())

        # Click Minimize '─' (Collapse to tiny bubble)
        self.canvas.tag_bind(self.btn_minimize, "<Button-1>", lambda e: self.toggle_compact())
        self.canvas.tag_bind(self.btn_minimize, "<Enter>", lambda e: [self.canvas.itemconfig(self.btn_minimize, fill="#FFFFFF"), self.canvas.config(cursor="hand2")])
        self.canvas.tag_bind(self.btn_minimize, "<Leave>", lambda e: [self.canvas.itemconfig(self.btn_minimize, fill="#8E9196"), self.canvas.config(cursor="arrow")])

        # Click Close 'x'
        self.canvas.tag_bind(self.btn_close, "<Button-1>", lambda e: self.quit())
        self.canvas.tag_bind(self.btn_close, "<Enter>", lambda e: [self.canvas.itemconfig(self.btn_close, fill="#FF5252"), self.canvas.config(cursor="hand2")])
        self.canvas.tag_bind(self.btn_close, "<Leave>", lambda e: [self.canvas.itemconfig(self.btn_close, fill="#5E636E"), self.canvas.config(cursor="arrow")])

        # Hand cursor on interactive elements
        clickable = [self.btn_plus, self.btn_plus_bg, self.sparkle, self.mic_circle, self.mic_icon, self.btn_minimize] + self.wave_bars
        for item in clickable:
            self.canvas.tag_bind(item, "<Enter>", lambda e: self.canvas.config(cursor="hand2"))
            self.canvas.tag_bind(item, "<Leave>", lambda e: self.canvas.config(cursor="arrow"))

    def toggle_compact(self):
        """Collapses into a tiny 54px floating orb or expands to full 480px pill so it never covers windows."""
        self.is_compact = not self.is_compact
        if self.is_compact:
            self.root.geometry(f"54x54+{self.pos_x}+{self.pos_y}")
        else:
            self.root.geometry(f"{self.cap_w}x{self.cap_h}+{self.pos_x}+{self.pos_y}")

    def _on_entry_key(self, event):
        """Blocks typing when assistant is speaking or processing to prevent speech collisions."""
        if self.state in ("speaking", "processing"):
            return "break"
        return None

    def _on_entry_focus_in(self, event):
        if self.state in ("speaking", "processing"):
            return
        if self.text_entry.get() in ("Ask Gemini", "Ask Ultron"):
            self.text_entry.delete(0, tk.END)
            self.text_entry.config(fg=TEXT_WHITE)

    def _on_entry_focus_out(self, event):
        if self.state in ("speaking", "processing"):
            return
        if not self.text_entry.get().strip():
            self.text_entry.delete(0, tk.END)
            self.text_entry.insert(0, "Ask Gemini")
            self.text_entry.config(fg=TEXT_PLACEHOLDER)

    def _on_text_submit(self):
        """Executes text command entered outside terminal with lockout & smooth pacing."""
        if self.state in ("speaking", "processing"):
            return  # Locked out while speaking/processing!

        cmd = self.text_entry.get().strip()
        if not cmd or cmd in ("Ask Gemini", "Ask Ultron"):
            return

        # Lock text entry immediately
        self.text_entry.config(state="disabled")
        self.is_mic_muted = True
        self.set_state("processing")

        # Dispatch background worker with gentle composed pacing (0.3s)
        threading.Thread(target=self._run_command_worker, args=(cmd,), daemon=True).start()

    def _show_quick_actions(self):
        """Cycles quick suggestion examples into entry field."""
        if self.state in ("speaking", "processing"):
            return

        chips = [
            "open file manager",
            "open sublime text and open learning.py and write hi in that file",
            "open settings",
            "open sublime text",
            "close chrome",
            "what time is it"
        ]
        chosen = random.choice(chips)
        self.text_entry.delete(0, tk.END)
        self.text_entry.insert(0, chosen)
        self.text_entry.config(fg=TEXT_WHITE)

    def _on_mouse_down(self, event):
        self.drag_start_x = event.x
        self.drag_start_y = event.y

    def _on_mouse_drag(self, event):
        dx = event.x - self.drag_start_x
        dy = event.y - self.drag_start_y
        if abs(dx) > 3 or abs(dy) > 3:
            new_x = self.root.winfo_x() + dx
            new_y = self.root.winfo_y() + dy
            # Keep within screen bounds
            new_x = max(0, min(self.screen_w - self.cap_w, new_x))
            new_y = max(0, min(self.screen_h - self.cap_h, new_y))
            self.root.geometry(f"+{new_x}+{new_y}")

    def _on_double_click(self, event):
        """Double click snaps the capsule directly back to the bottom center bezel."""
        self.pos_x = (self.screen_w - self.cap_w) // 2
        self.pos_y = self.screen_h - self.cap_h - 4
        self.root.geometry(f"+{self.pos_x}+{self.pos_y}")

    def trigger_listening(self):
        """Triggers active voice listening instantly."""
        if self.state in ("speaking", "processing"):
            return  # Locked out while speaking!

        # Clear any stale audio frames
        while not self.audio_queue.empty():
            try:
                self.audio_queue.get_nowait()
            except queue.Empty:
                break

        self.set_state("listening")
        self.ui_queue.put({"action": "set_entry_text", "text": "Listening...", "fg": "#8AB4F8"})

    def _on_assistant_speak(self, text):
        """
        Called when Ultron begins speaking.
        Enforces strict input lockout: typing is disabled and mic is completely muted.
        """
        self._speech_started = True
        self.is_mic_muted = True
        self.ui_queue.put({
            "action": "speak_start",
            "text": text
        })

    def _on_assistant_finish_speaking(self):
        """
        Called the exact millisecond Ultron finishes speaking.
        Cleans audio queue, resets recognizer, and safely unlocks user input.
        """
        # Drain any residual audio chunks
        while not self.audio_queue.empty():
            try:
                self.audio_queue.get_nowait()
            except queue.Empty:
                break

        # Wipe internal Kaldi acoustic model state to eliminate acoustic feedback
        if self.recognizer:
            try:
                self.recognizer.Reset()
            except Exception:
                pass

        # Brief acoustic decay delay (0.2s) so room echo dissipates completely
        time.sleep(0.20)

        # Notify UI to unlock
        self.ui_queue.put({"action": "finish_speaking"})

    def set_state(self, state):
        """Thread-safe UI state update."""
        self.ui_queue.put({"action": "set_state", "state": state})

    def _process_ui_queue(self):
        """Main thread queue processor."""
        while not self.ui_queue.empty():
            msg = self.ui_queue.get_nowait()
            action = msg.get("action")

            if action == "set_state":
                self.state = msg["state"]

                if self.state == "listening":
                    self.canvas.itemconfig(self.mic_circle, fill=GEMINI_RED)
                elif self.state == "processing":
                    self.canvas.itemconfig(self.mic_circle, fill=GEMINI_AMBER)
                elif self.state == "speaking":
                    self.canvas.itemconfig(self.mic_circle, fill=GEMINI_GREEN)
                else:
                    self.canvas.itemconfig(self.mic_circle, fill=GEMINI_BLUE)

            elif action == "set_entry_text":
                self.text_entry.config(state="normal")
                self.text_entry.delete(0, tk.END)
                self.text_entry.insert(0, msg["text"])
                self.text_entry.config(fg=msg.get("fg", TEXT_WHITE))

            elif action == "update_entry_speech":
                # Real-time transcribed speech display while listening
                if self.state == "listening":
                    self.text_entry.config(state="normal")
                    self.text_entry.delete(0, tk.END)
                    self.text_entry.insert(0, msg["text"])
                    self.text_entry.config(fg=TEXT_WHITE)

            elif action == "speak_start":
                self.state = "speaking"
                self.canvas.itemconfig(self.mic_circle, fill=GEMINI_GREEN)
                # Lock entry box & show Ultron response
                clean_text = msg["text"]
                if len(clean_text) > 36:
                    display_text = f"🤖 {clean_text[:34]}..."
                else:
                    display_text = f"🤖 {clean_text}"
                self.text_entry.config(state="normal")
                self.text_entry.delete(0, tk.END)
                self.text_entry.insert(0, display_text)
                self.text_entry.config(fg=TEXT_WHITE, state="disabled")

            elif action == "finish_speaking":
                # UNLOCK user input and microphone now that Ultron has finished speaking
                self.state = "ready"
                self.is_mic_muted = False
                self.text_entry.config(state="normal")
                self.text_entry.delete(0, tk.END)
                self.text_entry.insert(0, "Ask Gemini")
                self.text_entry.config(fg=TEXT_PLACEHOLDER)
                self.canvas.itemconfig(self.mic_circle, fill=GEMINI_BLUE)
                self.canvas.itemconfig(self.sparkle, fill="#8AB4F8")
                # Reset shimmer border to calm subtle border
                for seg in self.shimmer_segments:
                    self.canvas.itemconfig(seg, fill=GEMINI_BORDER, width=1)

        if self.running:
            self.root.after(30, self._process_ui_queue)

    def _animation_loop(self):
        """
        Smooth, non-boring Gemini animations:
        - Animated Gemini multi-color gradient shimmer laser sweeping across top edge when loading.
        - Animated thinking dots cycling in entry field ("✦ Thinking...").
        - Dynamic 4-bar equalizer wave visualizer dancing fluidly.
        - Pulsing Gemini sparkle icon '✦'.
        """
        self.anim_tick += 1
        self.wave_phase += 0.22
        cy = self.cap_h // 2
        wave_cx = 428
        bar_offsets = [-9, -3, 3, 9]

        if self.state == "processing":
            # 1. Iconic Gemini Multi-Color Gradient Loading Shimmer Wave
            num_segs = len(self.shimmer_segments)
            for i, seg in enumerate(self.shimmer_segments):
                seg_phase = self.wave_phase * 2.2 + (i / num_segs) * 3.2
                color = get_gemini_gradient_color(seg_phase)
                self.canvas.itemconfig(seg, fill=color, width=2)

            # 2. Pulsing Gemini Sparkle Icon with dynamic gradient hues
            sparkle_color = get_gemini_gradient_color(self.wave_phase * 3.0)
            sparkle_size = 11 + int(math.sin(self.wave_phase * 2.0) * 2)
            self.canvas.itemconfig(
                self.sparkle,
                fill=sparkle_color,
                font=("DejaVu Sans", sparkle_size, "bold")
            )

            # 3. Cycling Animated Thinking Dots in entry field
            if self.anim_tick % 7 == 0:
                dots = "." * ((self.anim_tick // 7) % 4 + 1)
                self.text_entry.config(state="normal")
                self.text_entry.delete(0, tk.END)
                self.text_entry.insert(0, f"✦ Thinking{dots}")
                self.text_entry.config(fg="#C58AF9", state="disabled")

            # 4. Undulating 4-Bar Loading Wave with gradient colors
            for i, (bar, off) in enumerate(zip(self.wave_bars, bar_offsets)):
                h = 4.0 + math.sin(self.wave_phase * 2.2 + i * 0.9) * 4.5
                b_color = get_gemini_gradient_color(self.wave_phase * 2.0 + i * 0.7)
                self.canvas.coords(bar, wave_cx + off, cy - h, wave_cx + off, cy + h)
                self.canvas.itemconfig(bar, fill=b_color)

        elif self.state == "speaking":
            # Audio Speech Visualizer rhythm (Voice wave)
            for i, (bar, off) in enumerate(zip(self.wave_bars, bar_offsets)):
                h = 3.5 + math.sin(self.wave_phase * 3.2 + i * 1.3) * 7.5
                self.canvas.coords(bar, wave_cx + off, cy - h, wave_cx + off, cy + h)
                self.canvas.itemconfig(bar, fill=GEMINI_GREEN)
            self.canvas.itemconfig(self.sparkle, fill=GEMINI_GREEN)
            # Soft blue glow on border
            for seg in self.shimmer_segments:
                self.canvas.itemconfig(seg, fill=GEMINI_BLUE_ACTIVE, width=1)

        elif self.state == "listening":
            # Reactive Voice Listening visualizer
            for i, (bar, off) in enumerate(zip(self.wave_bars, bar_offsets)):
                h = 4.0 + math.sin(self.wave_phase * 3.8 + i * 1.4) * 6.5
                self.canvas.coords(bar, wave_cx + off, cy - h, wave_cx + off, cy + h)
                self.canvas.itemconfig(bar, fill=GEMINI_RED)
            self.canvas.itemconfig(self.sparkle, fill=GEMINI_RED)
            for seg in self.shimmer_segments:
                self.canvas.itemconfig(seg, fill=GEMINI_RED, width=1)

        else:
            # Idle Calm Breathing Wave
            for i, (bar, off) in enumerate(zip(self.wave_bars, bar_offsets)):
                h = 3.5 + math.sin(self.wave_phase * 0.4 + i * 0.5) * 1.5
                self.canvas.coords(bar, wave_cx + off, cy - h, wave_cx + off, cy + h)
                self.canvas.itemconfig(bar, fill=TEXT_MUTED)
            self.canvas.itemconfig(self.sparkle, fill="#8AB4F8")
            for seg in self.shimmer_segments:
                self.canvas.itemconfig(seg, fill=GEMINI_BORDER, width=1)

        if self.running:
            self.root.after(30, self._animation_loop)

    def _audio_callback(self, indata, frames, time_info, status):
        """Discards audio chunks immediately if microphone is muted or assistant is speaking."""
        if self.is_mic_muted or self.state in ("speaking", "processing"):
            return  # Drop frame completely!
        self.audio_queue.put(bytes(indata))

    def _fast_audio_loop(self):
        """
        Fast Audio Loop with EXACT 0.5s Silence Endpointing.
        Prevents audio buffer overflow and premature speech cutoffs.
        """
        import sounddevice as sd
        from vosk import Model, KaldiRecognizer

        model_dir = self.assistant.model_dir
        if not os.path.exists(model_dir):
            return

        try:
            vosk_model = Model(model_dir)
            self.recognizer = KaldiRecognizer(vosk_model, 16000)
        except Exception:
            return

        try:
            self.audio_stream = sd.RawInputStream(
                samplerate=16000,
                blocksize=3200,
                dtype="int16",
                channels=1,
                callback=self._audio_callback
            )
            self.audio_stream.start()
        except Exception:
            return

        last_speech_time = 0
        has_partial = False
        current_partial = ""

        try:
            while self.running:
                # If mic is muted while Ultron speaks, drain audio queue and pause
                if self.is_mic_muted or self.state in ("speaking", "processing"):
                    while not self.audio_queue.empty():
                        try:
                            self.audio_queue.get_nowait()
                        except queue.Empty:
                            break
                    time.sleep(0.05)
                    continue

                try:
                    data = self.audio_queue.get(timeout=0.08)
                except queue.Empty:
                    # EXACT 0.50s SILENCE ENDPOINTING (requested by user)
                    if self.state == "listening" and has_partial and (time.time() - last_speech_time > 0.50):
                        has_partial = False
                        res = json.loads(self.recognizer.FinalResult())
                        final_text = res.get("text", "").strip() or current_partial
                        if final_text:
                            self._dispatch_command(final_text)
                    continue

                if self.recognizer.AcceptWaveform(data):
                    res = json.loads(self.recognizer.Result())
                    raw_text = res.get("text", "").strip()

                    if raw_text:
                        norm = normalize_speech(raw_text)
                        if is_wake_word(norm) or self.state == "listening":
                            cmd_body = strip_wake_words(norm)
                            if cmd_body:
                                has_partial = False
                                self._dispatch_command(norm)
                            else:
                                self.trigger_listening()
                                has_partial = False
                else:
                    partial = json.loads(self.recognizer.PartialResult())
                    part_text = partial.get("partial", "").strip()

                    if part_text:
                        last_speech_time = time.time()
                        has_partial = True
                        current_partial = part_text
                        norm_part = normalize_speech(part_text)

                        if self.state == "listening":
                            self.ui_queue.put({"action": "update_entry_speech", "text": norm_part})
                        elif is_wake_word(norm_part):
                            cmd_body = strip_wake_words(norm_part)
                            if cmd_body and len(cmd_body.split()) >= 2:
                                has_partial = False
                                self._dispatch_command(norm_part)
                            else:
                                self.trigger_listening()
                                has_partial = False
        finally:
            stream = self.audio_stream
            self.audio_stream = None
            if stream:
                try:
                    stream.stop()
                    stream.close()
                except Exception:
                    pass

    def _dispatch_command(self, text):
        """Transitions smoothly to processing state and locks input."""
        norm_text = normalize_speech(text)
        self.is_mic_muted = True
        self.set_state("processing")

        # Clear audio queue
        while not self.audio_queue.empty():
            try:
                self.audio_queue.get_nowait()
            except queue.Empty:
                break

        threading.Thread(target=self._run_command_worker, args=(norm_text,), daemon=True).start()

    def _run_command_worker(self, text):
        """
        Background worker executing Ultron commands.
        Paced calmly (0.3s) to prevent audio overflow and sudden cuts.
        """
        time.sleep(0.30)  # Calm composed pacing requested by user
        self._speech_started = False

        try:
            should_continue = self.assistant.process_command(text)
            if not should_continue:
                self.quit()
                return

            # If command produced no speech, safely unlock after a brief moment
            if not getattr(self, "_speech_started", False):
                time.sleep(0.2)
                self.ui_queue.put({"action": "finish_speaking"})
        except Exception as e:
            # Safely unlock if an error occurs
            self.ui_queue.put({"action": "finish_speaking"})

    def quit(self):
        """Clean shutdown."""
        self.running = False
        stream = self.audio_stream
        self.audio_stream = None
        if stream:
            try:
                stream.stop()
                stream.close()
            except Exception:
                pass
        if hasattr(self, "audio_thread") and self.audio_thread.is_alive():
            try:
                self.audio_thread.join(timeout=0.2)
            except Exception:
                pass
        try:
            self.root.quit()
            self.root.destroy()
        except Exception:
            pass
        os._exit(0)

    def run(self):
        self.root.mainloop()

# Backward compatibility alias
UltronFloatingWidget = UltronGeminiWidget

def main():
    app = UltronGeminiWidget()
    app.run()

if __name__ == "__main__":
    main()

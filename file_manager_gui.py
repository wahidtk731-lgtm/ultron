#!/usr/bin/env python3
"""
Ultron Assistant - Native Linux File Explorer
A fast, lightweight, modern desktop file manager for browsing files and folders.
Zero dependencies, 100% native Tkinter GUI.
"""

import os
import sys
import shutil
import subprocess
import datetime
import tkinter as tk
from tkinter import ttk, messagebox

BG_DARK = "#1E1F22"
BG_CARD = "#2B2D31"
ACCENT_BLUE = "#4285F4"
TEXT_WHITE = "#FFFFFF"
TEXT_MUTED = "#9AA0A6"

class UltronFileManager:
    def __init__(self, start_dir="/home/wahidtk"):
        self.root = tk.Tk()
        self.root.title("Files - Ultron File Manager")
        self.root.geometry("680x480")
        self.root.configure(bg=BG_DARK)

        # Center on screen
        try:
            sw = self.root.winfo_screenwidth()
            sh = self.root.winfo_screenheight()
            x = (sw - 680) // 2
            y = (sh - 480) // 2
            self.root.geometry(f"680x480+{x}+{y}")
        except Exception:
            pass

        self.current_dir = os.path.abspath(start_dir if os.path.exists(start_dir) else "/home/wahidtk")
        self._build_ui()
        self._load_directory(self.current_dir)

    def _build_ui(self):
        # Top toolbar
        toolbar = tk.Frame(self.root, bg=BG_DARK)
        toolbar.pack(fill="x", padx=14, pady=(12, 6))

        btn_up = tk.Button(
            toolbar,
            text="⬆ Up",
            font=("DejaVu Sans", 9, "bold"),
            bg="#3A3D44",
            fg=TEXT_WHITE,
            activebackground=ACCENT_BLUE,
            relief="flat",
            bd=0,
            padx=10,
            pady=4,
            cursor="hand2",
            command=self._go_up
        )
        btn_up.pack(side="left", padx=(0, 8))

        btn_home = tk.Button(
            toolbar,
            text="🏠 Home",
            font=("DejaVu Sans", 9),
            bg="#3A3D44",
            fg=TEXT_WHITE,
            activebackground=ACCENT_BLUE,
            relief="flat",
            bd=0,
            padx=10,
            pady=4,
            cursor="hand2",
            command=lambda: self._load_directory("/home/wahidtk")
        )
        btn_home.pack(side="left", padx=(0, 8))

        btn_new_file = tk.Button(
            toolbar,
            text="+ New File",
            font=("DejaVu Sans", 9),
            bg="#3A3D44",
            fg=TEXT_WHITE,
            activebackground=ACCENT_BLUE,
            relief="flat",
            bd=0,
            padx=10,
            pady=4,
            cursor="hand2",
            command=self._create_new_file
        )
        btn_new_file.pack(side="left", padx=(0, 8))

        # Path label
        self.lbl_path = tk.Label(
            toolbar,
            text=self.current_dir,
            font=("DejaVu Sans", 9),
            bg=BG_DARK,
            fg=TEXT_MUTED,
            anchor="w"
        )
        self.lbl_path.pack(side="left", fill="x", expand=True, padx=8)

        # Treeview list of files
        list_frame = tk.Frame(self.root, bg=BG_CARD)
        list_frame.pack(fill="both", expand=True, padx=14, pady=6)

        style = ttk.Style()
        style.theme_use("clam")
        style.configure(
            "Treeview",
            background=BG_CARD,
            foreground=TEXT_WHITE,
            fieldbackground=BG_CARD,
            rowheight=26,
            font=("DejaVu Sans", 9)
        )
        style.configure(
            "Treeview.Heading",
            background="#33353A",
            foreground=TEXT_WHITE,
            font=("DejaVu Sans", 9, "bold")
        )
        style.map("Treeview", background=[("selected", ACCENT_BLUE)])

        cols = ("name", "type", "size", "modified")
        self.tree = ttk.Treeview(list_frame, columns=cols, show="headings", selectmode="browse")
        self.tree.heading("name", text="Name")
        self.tree.heading("type", text="Type")
        self.tree.heading("size", text="Size")
        self.tree.heading("modified", text="Date Modified")

        self.tree.column("name", width=280)
        self.tree.column("type", width=90)
        self.tree.column("size", width=80)
        self.tree.column("modified", width=150)

        scrollbar = ttk.Scrollbar(list_frame, orient="vertical", command=self.tree.yview)
        self.tree.configure(yscrollcommand=scrollbar.set)

        self.tree.pack(side="left", fill="both", expand=True)
        scrollbar.pack(side="right", fill="y")

        self.tree.bind("<Double-1>", self._on_item_double_click)
        self.tree.bind("<Return>", self._on_item_double_click)

        # Bottom action bar
        bot_bar = tk.Frame(self.root, bg=BG_DARK)
        bot_bar.pack(fill="x", padx=14, pady=(6, 12))

        btn_open = tk.Button(
            bot_bar,
            text="Open in Sublime",
            font=("DejaVu Sans", 9, "bold"),
            bg=ACCENT_BLUE,
            fg=TEXT_WHITE,
            activebackground="#3367D6",
            relief="flat",
            bd=0,
            padx=14,
            pady=5,
            cursor="hand2",
            command=self._open_selected_in_sublime
        )
        btn_open.pack(side="left")

        btn_close = tk.Button(
            bot_bar,
            text="Close",
            font=("DejaVu Sans", 9),
            bg="#3A3D44",
            fg=TEXT_WHITE,
            relief="flat",
            bd=0,
            padx=14,
            pady=5,
            cursor="hand2",
            command=self.root.destroy
        )
        btn_close.pack(side="right")

    def _load_directory(self, path):
        if not os.path.exists(path) or not os.path.isdir(path):
            return

        self.current_dir = os.path.abspath(path)
        self.lbl_path.config(text=self.current_dir)

        # Clear tree
        for item in self.tree.get_children():
            self.tree.delete(item)

        try:
            entries = os.scandir(self.current_dir)
            folders = []
            files = []
            for e in entries:
                if e.name.startswith("."):
                    continue
                if e.is_dir():
                    folders.append(e)
                else:
                    files.append(e)

            folders.sort(key=lambda x: x.name.lower())
            files.sort(key=lambda x: x.name.lower())

            for f in folders:
                mod = datetime.datetime.fromtimestamp(f.stat().st_mtime).strftime("%b %d, %Y %H:%M")
                self.tree.insert("", "end", values=(f"📁 {f.name}", "Folder", "--", mod))

            for f in files:
                stat = f.stat()
                size_str = self._format_size(stat.st_size)
                mod = datetime.datetime.fromtimestamp(stat.st_mtime).strftime("%b %d, %Y %H:%M")
                icon = "🐍 " if f.name.endswith(".py") else ("📄 " if f.name.endswith((".cpp", ".c", ".js", ".html", ".txt")) else "📦 ")
                self.tree.insert("", "end", values=(f"{icon}{f.name}", "File", size_str, mod))

        except Exception as e:
            messagebox.showerror("Error", f"Could not read directory: {e}")

    def _format_size(self, size_bytes):
        for unit in ["B", "KB", "MB", "GB"]:
            if size_bytes < 1024.0:
                return f"{size_bytes:.1f} {unit}"
            size_bytes /= 1024.0
        return f"{size_bytes:.1f} TB"

    def _go_up(self):
        parent = os.path.dirname(self.current_dir)
        if parent and parent != self.current_dir and os.path.exists(parent):
            self._load_directory(parent)

    def _on_item_double_click(self, event=None):
        selected = self.tree.selection()
        if not selected:
            return
        vals = self.tree.item(selected[0], "values")
        if not vals:
            return
        name_raw = vals[0]
        # Strip icon
        name = name_raw.split(" ", 1)[1] if " " in name_raw else name_raw
        target = os.path.join(self.current_dir, name)

        if os.path.isdir(target):
            self._load_directory(target)
        else:
            # Open file in Sublime Text or default editor
            subl = shutil.which("subl")
            if subl:
                subprocess.Popen([subl, target])
            else:
                subprocess.Popen(["xdg-open", target])

    def _open_selected_in_sublime(self):
        selected = self.tree.selection()
        if not selected:
            return
        vals = self.tree.item(selected[0], "values")
        if not vals:
            return
        name_raw = vals[0]
        name = name_raw.split(" ", 1)[1] if " " in name_raw else name_raw
        target = os.path.join(self.current_dir, name)
        subl = shutil.which("subl") or "/usr/bin/subl"
        subprocess.Popen([subl, target])

    def _create_new_file(self):
        from tkinter import simpledialog
        name = simpledialog.askstring("New File", "Enter new file name (e.g. script.py):", parent=self.root)
        if name and name.strip():
            target = os.path.join(self.current_dir, name.strip())
            open(target, "a").close()
            self._load_directory(self.current_dir)
            subl = shutil.which("subl")
            if subl:
                subprocess.Popen([subl, target])

    def run(self):
        self.root.mainloop()

def main():
    app = UltronFileManager()
    app.run()

if __name__ == "__main__":
    main()

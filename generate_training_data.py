#!/usr/bin/env python3
"""
Comprehensive High-Precision Training Data Generator for Ultron AI Assistant.
Generates all possibilities of commands across the entire system:
- All open verbs, prefixes, polite forms, and app targets ("whole open all possibilities")
- All file opening and viewing possibilities across all user files and editors
- All file writing, typing, inserting, appending possibilities
- All compound multifunctional chained action sequences
- All close, terminate, and process killing possibilities
- All web search and Google query patterns
- All date, time, and calendar inquiries
- All greetings, capability queries, and exit commands
"""

import json
import os
import sys

# Load local launcher for device-first scanning
import shutil

try:
    from app_launcher import AppLauncher
    _launcher = AppLauncher()
    DEVICE_APPS = _launcher.get_existing_app_names()
    USER_FILES = list(_launcher.user_files.keys())
except Exception:
    DEVICE_APPS = []
    USER_FILES = []

# 1. Comprehensive App Catalog
BASE_APPS = [
    # Browsers
    "chrome", "google chrome", "browser", "web browser", "internet", "firefox", "chromium",
    # Editors & IDEs
    "sublime", "sublime text", "sublime editor", "subl", "geany", "geany editor", "geany ide",
    "vs code", "vscode", "visual studio code", "code", "vim", "vim editor", "nvim", "neovim",
    "text editor", "editor",
    # File Manager & Storage
    "file manager", "files", "my files", "folder", "home folder", "explorer", "pcmanfm",
    "desktop preferences", "home", "downloads", "documents", "desktop",
    # Terminals & Shells
    "terminal", "bash", "console", "shell", "command prompt", "cmd", "command line", "xterm",
    # System Monitors & Utilities
    "cmatrix", "matrix", "matrix code", "htop", "top", "system monitor", "process monitor",
    "settings", "system settings", "control center", "preferences", "calculator", "calc",
    "python", "python3", "ultron",
    # Popular Websites & Online Tools
    "youtube", "google", "github", "gmail", "chatgpt", "reddit", "twitter", "wikipedia",
    "amazon", "netflix", "spotify",
    # User Code & Web Projects
    "learning", "learning project", "html project", "my website", "web project", "coding project"
]

# Only train on apps that actually exist and can be launched on this device!
def does_app_exist(app):
    clean = app.lower().strip()
    if clean in [a.lower() for a in DEVICE_APPS]:
        return True
    return shutil.which(clean) is not None

VERIFIED_BASE_APPS = [a for a in BASE_APPS if does_app_exist(a)]
ALL_APPS = sorted(list(set(VERIFIED_BASE_APPS + [a.lower() for a in DEVICE_APPS])))

# 2. Comprehensive File Catalog
BASE_FILES = [
    "learning.py", "learning.cpp", "learning.js", "index.html", "style.css", "index.css",
    "notes.txt", "main.py", "script.py", "test.py", "todo.txt", "readme.md", "app.py",
    "server.py", "nodesource_setup.sh", "settings_gui.py", "file_manager_gui.py",
    "floating_widget.py", "launch_floating.sh", "train_intent.py", "app_launcher.py"
]
ALL_USER_FILES = sorted(list(set(BASE_FILES + [
    f.lower() for f in USER_FILES
    if any(f.lower().endswith(ext) for ext in [".py", ".cpp", ".js", ".html", ".css", ".txt", ".sh", ".md", ".json"])
])))

# 3. Comprehensive Open Verbs & Prefixes ("Whole Open All Possibilities")
OPEN_VERBS = [
    "open", "launch", "start", "run", "open up", "start up", "fire up", "bring up",
    "pop up", "pop open", "pull up", "load", "load up", "spin up", "execute", "access",
    "go to", "switch to", "turn on", "bring on", "show me", "give me", "display",
    "get me", "view", "let me see", "can you open", "could you open", "would you open",
    "please open", "can you please open", "could you please open", "would you please open",
    "i want to open", "i need to open", "i'd like to open", "help me open", "can we open",
    "let me open", "let's open", "let's start", "let's launch",
    "open the", "launch the", "start the", "run the", "bring up the", "fire up the",
    "pull up the", "show the", "load the", "execute the",
    "hey ultron open", "ultron open", "hey all thrown open", "all thrown open",
    "hey ultron launch", "ultron launch", "hey ultron start", "ultron start",
    "hey ultron please open", "ok ultron open", "computer open"
]

# 4. Close & Terminate Verbs
CLOSE_VERBS = [
    "close", "shut down", "terminate", "kill", "exit", "quit", "stop", "end",
    "close down", "kill the", "close the", "shut the", "stop running", "turn off",
    "please close", "can you close", "could you close", "shut", "kill process",
    "force close", "kill task", "stop task", "dismiss",
    "hey ultron close", "ultron close", "hey all thrown close", "all thrown close",
    "hey ultron kill", "ultron terminate"
]

# 5. File Writing Phrases & Content Variations
WRITE_VERBS = ["write", "type", "insert", "add", "put", "append", "save"]
WRITE_PREPOSITIONS = ["in", "into", "to", "inside"]
WRITE_TARGETS = [
    "that file", "the file", "this file", "learning.py", "learning.py file",
    "notes.txt", "my file", "main.py"
]
WRITE_SNIPPETS = [
    "hi", "hello", "hi there", "hello world", "test", "testing", "print hi",
    "code", "text", "welcome", "my name is wahid", "python code",
    "learning python", "print('hello')", "hello python"
]

# 6. Compound Action Patterns
CONJUNCTIONS = ["and", "and then", "then", "after that"]

# 7. Time & Date Phrases
TIME_PHRASES = [
    "what time is it", "what is the time", "tell me the time", "current time",
    "what time is it now", "what day is it", "what is today's date", "tell me today's date",
    "what is the date", "what is the date today", "what day is today", "time now",
    "date today", "can you tell me the time", "do you have the time", "what's the time",
    "what's today's date", "give me the current time", "tell me the current date",
    "check the time", "check the date", "time please", "clock time", "show time",
    "what month is it", "what year is this", "tell me what time it is", "tell me the hour",
    "what day of the week is it", "what's the date today", "what time do you have",
    "can you tell me today's date", "current date and time", "show clock",
    "hey ultron what time is it", "hey all thrown what time is it", "ultron what time is it",
    "hey all drone what time is it", "hey ultron tell me the time", "hey all thrown what is the date",
    "ultron current time", "all thrown what time is it"
]

# 8. Web Search Templates & Queries
SEARCH_TEMPLATES = [
    "search google for {q}", "search the web for {q}", "search for {q}", "google {q}",
    "look up {q}", "search {q}", "find info on {q}", "search online for {q}",
    "look on google for {q}", "query google for {q}", "can you search for {q}",
    "please google {q}", "hey ultron search for {q}", "hey all thrown search google for {q}",
    "ultron search {q}", "find {q} on google", "web search {q}", "look up {q} online",
    "google search {q}", "search internet for {q}", "find articles on {q}"
]

SEARCH_QUERIES = [
    "python", "python tutorials", "linux commands", "machine learning",
    "artificial intelligence", "weather today", "chromeos tips", "javascript",
    "css flexbox", "github open source", "space news", "quantum computing",
    "latest news", "how to code in python", "data science", "sublime text shortcuts",
    "pcmanfm linux", "bash scripting tutorials", "deep learning models",
    "offline ai assistants", "crostini linux tips", "html and css"
]

# 9. Greeting Phrases
GREET_PHRASES = [
    "hello", "hi", "hey", "hey ultron", "hey all thrown", "hey all drone",
    "hello ultron", "hi ultron", "are you there", "are you online", "are you listening",
    "good morning", "good afternoon", "good evening", "wake up", "wake up ultron",
    "yo ultron", "how are you", "how are you doing", "what's up", "hey there",
    "greetings", "hello assistant", "hey robot", "sup ultron", "are you awake",
    "good day", "hey buddy", "all thrown", "ultron", "ok ultron", "hi assistant"
]

# 10. Capability & Help Phrases
CAPABILITIES_PHRASES = [
    "what can you do", "who are you", "what are you", "help me", "help",
    "what are your commands", "list commands", "show commands", "what apps can you open",
    "introduce yourself", "tell me about yourself", "what features do you have",
    "what do you do", "how can you help me", "show features", "give me instructions",
    "how do i use you", "what are your skills", "capabilities", "what are your capabilities",
    "what can ultron do", "what are you able to do", "show what you can do",
    "hey ultron what can you do", "hey all thrown help me", "ultron who are you",
    "ultron help", "tell me what you can do"
]

# 11. Exit & Sleep Phrases
EXIT_PHRASES = [
    "exit", "quit", "bye", "goodbye", "goodbye ultron", "shutdown", "power off",
    "stop listening", "go to sleep", "sleep", "exit assistant", "terminate assistant",
    "turn off", "close ultron", "power down", "shut yourself down", "good night",
    "see you later", "deactivate", "stop assistant", "close assistant", "offline",
    "go offline", "hey ultron exit", "hey all thrown shutdown", "ultron goodbye",
    "ultron quit", "ultron stop", "turn yourself off", "dismiss assistant"
]

CONVERSATIONAL_PREFIXES = [
    "", "please", "can you", "could you", "would you", "hey ultron", "ultron",
    "hey all thrown", "ok ultron"
]

def generate_full_dataset():
    data = []
    seen = set()

    def add_sample(text, intent):
        t = " ".join(text.strip().lower().split())
        if t and (t, intent) not in seen:
            seen.add((t, intent))
            data.append((t, intent))

    # 1. OPEN_APP: All possibilities of opening applications
    for app in ALL_APPS:
        add_sample(app, "open_app")
        add_sample(f"ultron {app}", "open_app")
        add_sample(f"hey ultron {app}", "open_app")
        for verb in OPEN_VERBS:
            add_sample(f"{verb} {app}", "open_app")

    # 2. OPEN_FILE: All possibilities of opening/editing files
    file_open_verbs = [
        "open", "edit", "view", "show", "launch", "read", "display",
        "open up", "bring up", "code in", "start"
    ]
    editor_names = ["sublime", "sublime text", "geany", "vim", "nvim", "editor"]

    for f in ALL_USER_FILES:
        for verb in file_open_verbs:
            add_sample(f"{verb} {f}", "open_file")
            add_sample(f"{verb} {f} file", "open_file")
            add_sample(f"please {verb} {f}", "open_file")
            add_sample(f"can you {verb} {f}", "open_file")
            add_sample(f"hey ultron {verb} {f}", "open_file")

            for ed in editor_names:
                add_sample(f"{verb} {f} in {ed}", "open_file")
                add_sample(f"{verb} {f} with {ed}", "open_file")
                add_sample(f"{verb} {f} file in {ed}", "open_file")

    # 3. WRITE_FILE: All possibilities of writing to files
    for v in WRITE_VERBS:
        for prep in WRITE_PREPOSITIONS:
            for tgt in WRITE_TARGETS:
                for snip in WRITE_SNIPPETS:
                    add_sample(f"{v} {snip} {prep} {tgt}", "write_file")
                    add_sample(f"please {v} {snip} {prep} {tgt}", "write_file")
                    add_sample(f"hey ultron {v} {snip} {prep} {tgt}", "write_file")

    # 4. COMPOUND_ACTION: Multifunction chained commands
    core_apps = ["sublime text", "geany", "terminal", "chrome", "file manager", "settings", "cmatrix", "htop"]
    core_files = ["learning.py", "learning.cpp", "index.html", "notes.txt"]
    core_writes = ["hi", "hello", "hi there", "hello world", "test", "python code"]

    for app in core_apps:
        for f in core_files:
            for snip in core_writes:
                for conj in CONJUNCTIONS:
                    add_sample(f"open {app} {conj} open {f} file {conj} write {snip} in that file", "compound_action")
                    add_sample(f"open {app} {conj} open {f} {conj} write {snip} in that file", "compound_action")
                    add_sample(f"open {app} {conj} open {f} file {conj} write {snip} into that file", "compound_action")
                    add_sample(f"open {app} {conj} write {snip} into {f}", "compound_action")
                    add_sample(f"open {f} {conj} write {snip} in that file", "compound_action")
                    add_sample(f"hey ultron open {app} {conj} open {f} and write {snip} in that file", "compound_action")

    # App + App compound pairs
    for a1 in ["sublime text", "file manager", "terminal", "chrome", "settings"]:
        for a2 in ["geany", "cmatrix", "htop", "calculator", "terminal", "settings"]:
            if a1 != a2:
                for conj in CONJUNCTIONS:
                    add_sample(f"open {a1} {conj} open {a2}", "compound_action")
                    add_sample(f"open {a1} {conj} close {a2}", "compound_action")
                    add_sample(f"close {a1} {conj} close {a2}", "compound_action")

    # Time + App compound pairs
    for app in ["sublime text", "chrome", "terminal", "file manager", "settings"]:
        for conj in CONJUNCTIONS:
            add_sample(f"what time is it {conj} open {app}", "compound_action")
            add_sample(f"tell me the time {conj} open {app}", "compound_action")
            add_sample(f"open {app} {conj} what time is it", "compound_action")

    # 5. CLOSE_APP: All closing possibilities
    for app in ALL_APPS:
        for verb in CLOSE_VERBS:
            add_sample(f"{verb} {app}", "close_app")
            add_sample(f"hey ultron {verb} {app}", "close_app")

    generic_close_phrases = [
        "close app", "close the app", "close this app", "close current app", "close the current app",
        "kill app", "kill the app", "kill this app", "kill current app",
        "exit app", "exit the app", "exit this app", "quit app", "quit this app",
        "dismiss app", "terminate app", "terminate the app", "shut down app",
        "close application", "kill application", "exit application", "quit application",
        "hey ultron close app", "hey ultron close this app", "ultron close app", "ultron close current app",
        "close", "terminate", "kill process", "force close"
    ]
    for p in generic_close_phrases:
        add_sample(p, "close_app")

    # 6. WI-FI CONTROLS
    wifi_on_phrases = [
        "turn on wifi", "enable wifi", "start wifi", "switch on wifi", "wifi on", "turn on the wifi",
        "turn on wi fi", "enable wi fi", "switch on wi fi", "wi fi on", "connect wifi",
        "hey ultron turn on wifi", "ultron turn on wifi", "please turn on wifi"
    ]
    for p in wifi_on_phrases:
        add_sample(p, "wifi_on")

    wifi_off_phrases = [
        "turn off wifi", "disable wifi", "stop wifi", "switch off wifi", "wifi off", "turn off the wifi",
        "turn off wi fi", "disable wi fi", "switch off wi fi", "wi fi off", "disconnect wifi",
        "hey ultron turn off wifi", "ultron turn off wifi", "please turn off wifi"
    ]
    for p in wifi_off_phrases:
        add_sample(p, "wifi_off")

    # 7. BLUETOOTH CONTROLS
    bt_on_phrases = [
        "turn on bluetooth", "enable bluetooth", "start bluetooth", "switch on bluetooth", "bluetooth on",
        "turn on the bluetooth", "hey ultron turn on bluetooth", "ultron turn on bluetooth", "please turn on bluetooth"
    ]
    for p in bt_on_phrases:
        add_sample(p, "bluetooth_on")

    bt_off_phrases = [
        "turn off bluetooth", "disable bluetooth", "stop bluetooth", "switch off bluetooth", "bluetooth off",
        "turn off the bluetooth", "hey ultron turn off bluetooth", "ultron turn off bluetooth", "please turn off bluetooth"
    ]
    for p in bt_off_phrases:
        add_sample(p, "bluetooth_off")

    # 8. CLEAR NOTIFICATIONS
    notif_phrases = [
        "clear notifications", "clear notification", "clean notifications", "dismiss notifications",
        "wipe notifications", "remove notifications", "clear all notifications", "delete notifications",
        "clear the notifications", "clear my notifications", "clear status bar notifications",
        "hey ultron clear notifications", "ultron clear notifications", "dismiss all notifications"
    ]
    for p in notif_phrases:
        add_sample(p, "clear_notifications")

    # 9. RECENT APPS
    recent_phrases = [
        "open recent app", "open recently opened app", "recent app", "recent apps", "switch to recent app",
        "open the recent app", "switch app", "last app", "go to recent app", "open previous app",
        "switch to previous app", "open last app", "hey ultron open recent app", "ultron recent app"
    ]
    for p in recent_phrases:
        add_sample(p, "recent_apps")

    # 10. CREATE_FILE
    create_verbs = ["create file", "make file", "create a file", "make a file", "new file", "create new file", "make new file"]
    for f in ALL_USER_FILES:
        for cv in create_verbs:
            add_sample(f"{cv} {f}", "create_file")
            add_sample(f"please {cv} {f}", "create_file")
            add_sample(f"hey ultron {cv} {f}", "create_file")
    for f_generic in ["notes.txt", "todo.txt", "learning.py", "script.py", "test.txt", "demo.py", "app.py"]:
        for cv in create_verbs:
            add_sample(f"{cv} {f_generic}", "create_file")

    # 11. FLOATING MODE
    floating_phrases = [
        "floating mode", "open floating mode", "turn on floating mode", "enable floating mode",
        "floating bubble", "open floating bubble", "enable floating bubble", "bubble mode",
        "floating overlay", "open floating overlay", "switch to floating mode", "start floating mode",
        "hey ultron floating mode", "ultron floating mode", "collapse to bubble", "open floating widget"
    ]
    for p in floating_phrases:
        add_sample(p, "floating_mode")

    # 6. QUERY_TIME: Enriched date and time inquiries
    for p in TIME_PHRASES:
        for prefix in CONVERSATIONAL_PREFIXES:
            phrase = f"{prefix} {p}".strip()
            add_sample(phrase, "query_time")

    # 7. SEARCH_WEB: Web search inquiries
    for template in SEARCH_TEMPLATES:
        for q in SEARCH_QUERIES:
            for prefix in ["", "please", "hey ultron", "ultron"]:
                phrase = f"{prefix} {template.format(q=q)}".strip()
                add_sample(phrase, "search_web")

    # 8. GREET: Enriched greetings
    for g in GREET_PHRASES:
        for suffix in ["", "ultron", "there", "friend", "buddy", "my assistant"]:
            phrase = f"{g} {suffix}".strip()
            add_sample(phrase, "greet")

    # 9. QUERY_CAPABILITIES: Enriched capability and help phrases
    for c in CAPABILITIES_PHRASES:
        for prefix in CONVERSATIONAL_PREFIXES:
            phrase = f"{prefix} {c}".strip()
            add_sample(phrase, "query_capabilities")

    # 10. EXIT: Enriched exit phrases
    for e in EXIT_PHRASES:
        for prefix in CONVERSATIONAL_PREFIXES:
            phrase = f"{prefix} {e}".strip()
            add_sample(phrase, "exit")

    return data

if __name__ == "__main__":
    dataset = generate_full_dataset()
    print(f"[*] Generated {len(dataset)} total training samples across all possibilities!")

    out_file = os.path.join(os.path.dirname(__file__), "expanded_training_data.json")
    with open(out_file, "w", encoding="utf-8") as f:
        json.dump([{"text": sample[0], "intent": sample[1]} for sample in dataset], f, indent=2)
    print(f"[+] Saved {len(dataset)} samples to {out_file}!")

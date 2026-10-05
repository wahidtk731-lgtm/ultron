#!/usr/bin/env python3
"""
High-Precision Training Data Generator for Ultron AI Assistant.
Generates 1,500+ diverse training examples across all command patterns,
phonetic speech misrecognitions, and conversational variants.
"""

import json
import os

APPS = [
    # Browsers
    "chrome", "google chrome", "browser", "web browser", "internet", "firefox", "chromium",
    # Terminals
    "terminal", "bash", "console", "shell", "command prompt", "cmd", "command line",
    # Editors & IDEs
    "geany", "geany editor", "geany ide", "sublime", "sublime text", "sublime editor",
    "vs code", "vscode", "visual studio code", "code", "vim", "vim editor", "nvim", "neovim",
    "text editor",
    # System & Tools
    "cmatrix", "matrix", "matrix code", "htop", "top", "system monitor", "process monitor",
    "calculator", "calc", "settings", "system settings", "control center",
    # Storage & Files
    "files", "my files", "file manager", "folder", "home folder", "explorer",
    # Websites & Projects
    "youtube", "google", "github", "reddit", "twitter", "wikipedia",
    "learning", "learning project", "html project", "my website"
]

OPEN_VERBS = [
    "open", "launch", "start", "run", "start up", "fire up", "bring up", "open up",
    "please open", "please launch", "can you open", "could you open", "can you launch",
    "could you launch", "i want to open", "let me open", "switch to", "go to", "access",
    "open the", "launch the", "start the", "run the",
    "hey ultron open", "hey all thrown open", "ultron open", "ultron launch", "hey ultron please open"
]

CLOSE_VERBS = [
    "close", "shut down", "terminate", "kill", "exit", "quit", "stop", "close the",
    "kill the", "please close", "can you close", "shut", "turn off", "end", "stop running",
    "hey ultron close", "hey all thrown close", "ultron close"
]

TIME_PHRASES = [
    "what time is it", "what is the time", "tell me the time", "current time",
    "what time is it now", "what day is it", "what is today's date", "tell me today's date",
    "what is the date", "what is the date today", "what day is today", "time now",
    "date today", "can you tell me the time", "do you have the time", "what's the time",
    "what's today's date", "give me the current time", "tell me the current date",
    "check the time", "check the date", "time please", "clock time", "show time",
    "hey ultron what time is it", "hey all thrown what time is it", "ultron what time is it",
    "hey all drone what time is it", "hey ultron tell me the time", "hey all thrown what is the date",
    "what month is it", "what year is this", "tell me what time it is", "tell me the hour"
]

SEARCH_TEMPLATES = [
    "search google for {q}", "search the web for {q}", "search for {q}", "google {q}",
    "look up {q}", "search {q}", "find info on {q}", "search online for {q}",
    "look on google for {q}", "query google for {q}", "can you search for {q}",
    "please google {q}", "hey ultron search for {q}", "hey all thrown search google for {q}",
    "ultron search {q}", "find {q} on google", "web search {q}"
]

SEARCH_QUERIES = [
    "python", "python tutorials", "linux commands", "machine learning",
    "artificial intelligence", "weather today", "chromeos tips", "javascript",
    "css flexbox", "github open source", "space news", "quantum computing",
    "latest news", "how to code in python", "data science"
]

GREET_PHRASES = [
    "hello", "hi", "hey", "hey ultron", "hey all thrown", "hey all drone",
    "hello ultron", "hi ultron", "are you there", "are you online", "are you listening",
    "good morning", "good afternoon", "good evening", "wake up", "wake up ultron",
    "yo ultron", "how are you", "how are you doing", "what's up", "hey there",
    "greetings", "hello assistant", "hey robot", "sup ultron", "are you awake",
    "good day", "hey buddy", "all thrown", "ultron"
]

CAPABILITIES_PHRASES = [
    "what can you do", "who are you", "what are you", "help me", "help",
    "what are your commands", "list commands", "show commands", "what apps can you open",
    "introduce yourself", "tell me about yourself", "what features do you have",
    "what do you do", "how can you help me", "show features", "give me instructions",
    "how do i use you", "what are your skills", "capabilities", "what are your capabilities",
    "hey ultron what can you do", "hey all thrown help me", "ultron who are you"
]

EXIT_PHRASES = [
    "exit", "quit", "bye", "goodbye", "goodbye ultron", "shutdown", "power off",
    "stop listening", "go to sleep", "sleep", "exit assistant", "terminate assistant",
    "turn off", "close ultron", "power down", "shut yourself down", "good night",
    "see you later", "deactivate", "stop assistant", "close assistant", "offline",
    "go offline", "hey ultron exit", "hey all thrown shutdown", "ultron goodbye"
]

def generate_full_dataset():
    data = []

    # 1. open_app
    for verb in OPEN_VERBS:
        for app in APPS:
            data.append((f"{verb} {app}", "open_app"))

    # 2. close_app
    for verb in CLOSE_VERBS:
        for app in APPS[:25]:  # Common closeable applications
            data.append((f"{verb} {app}", "close_app"))

    # 3. query_time
    for p in TIME_PHRASES:
        data.append((p, "query_time"))

    # 4. search_web
    for template in SEARCH_TEMPLATES:
        for q in SEARCH_QUERIES:
            data.append((template.format(q=q), "search_web"))

    # 5. greet
    for g in GREET_PHRASES:
        data.append((g, "greet"))

    # 6. query_capabilities
    for c in CAPABILITIES_PHRASES:
        data.append((c, "query_capabilities"))

    # 7. exit
    for e in EXIT_PHRASES:
        data.append((e, "exit"))

    return data

if __name__ == "__main__":
    dataset = generate_full_dataset()
    print(f"[*] Generated {len(dataset)} total training samples!")
    
    # Save to expanded_training_data.json
    out_file = os.path.join(os.path.dirname(__file__), "expanded_training_data.json")
    with open(out_file, "w", encoding="utf-8") as f:
        json.dump([{"text": sample[0], "intent": sample[1]} for sample in dataset], f, indent=2)
    print(f"[+] Saved to {out_file}")

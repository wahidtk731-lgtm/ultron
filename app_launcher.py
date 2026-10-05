#!/usr/bin/env python3
"""
Linux App Discovery and Launcher Engine
Scans .desktop entries, binaries, system handlers, and web services to match voice/text requests.
Optimized for Linux and ChromeOS container environments.
"""

import os
import glob
import subprocess
import shutil
import re
import urllib.parse
from rapidfuzz import process, fuzz

# Common system aliases mapping friendly names to executables/handlers
COMMON_ALIASES = {
    # Browsers
    "browser": ["garcon-url-handler https://www.google.com", "xdg-open https://www.google.com", "google-chrome", "chromium", "firefox", "x-www-browser https://www.google.com"],
    "chrome": ["garcon-url-handler https://www.google.com", "xdg-open https://www.google.com", "google-chrome", "google-chrome-stable", "chromium"],
    "google chrome": ["garcon-url-handler https://www.google.com", "xdg-open https://www.google.com", "google-chrome", "chromium"],
    "google": ["garcon-url-handler https://www.google.com", "xdg-open https://www.google.com"],
    "firefox": ["firefox", "firefox-esr"],
    "chromium": ["chromium", "chromium-browser"],
    "internet": ["garcon-url-handler https://www.google.com", "xdg-open https://www.google.com"],
    "web": ["garcon-url-handler https://www.google.com", "xdg-open https://www.google.com"],

    # Terminals & Shell
    "terminal": ["garcon-terminal-handler", "x-terminal-emulator", "gnome-terminal", "xfce4-terminal", "konsole", "alacritty", "kitty", "xterm"],
    "bash": ["garcon-terminal-handler", "x-terminal-emulator"],
    "console": ["garcon-terminal-handler", "x-terminal-emulator"],
    "shell": ["garcon-terminal-handler", "x-terminal-emulator"],
    "cmd": ["garcon-terminal-handler", "x-terminal-emulator"],

    # Code & Text Editors
    "code": ["sublime_text", "geany", "code", "nvim", "vim"],
    "editor": ["sublime_text", "geany", "gedit", "kate", "nvim", "vim"],
    "text editor": ["sublime_text", "geany", "gedit", "vim", "nvim"],
    "sublime": ["sublime_text", "/opt/sublime_text/sublime_text"],
    "sublime text": ["sublime_text", "/opt/sublime_text/sublime_text"],
    "geany": ["geany"],
    "geany editor": ["geany"],
    "vim": ["vim"],
    "nvim": ["nvim"],
    "neovim": ["nvim"],

    # Fun & Terminal Tools
    "cmatrix": ["cmatrix"],
    "matrix": ["cmatrix"],
    "top": ["top"],
    "htop": ["htop"],

    # Files & Storage
    "files": ["garcon-url-handler file:///home/wahidtk", "xdg-open /home/wahidtk", "nautilus", "thunar", "dolphin", "pcmanfm"],
    "file manager": ["garcon-url-handler file:///home/wahidtk", "xdg-open /home/wahidtk", "nautilus", "thunar", "dolphin", "pcmanfm"],
    "folder": ["garcon-url-handler file:///home/wahidtk", "xdg-open /home/wahidtk"],
    "home": ["garcon-url-handler file:///home/wahidtk", "xdg-open /home/wahidtk"],
    "my files": ["garcon-url-handler file:///home/wahidtk", "xdg-open /home/wahidtk"],

    # Utilities
    "calculator": ["gnome-calculator", "kcalc", "galculator", "xcalc"],
    "calc": ["gnome-calculator", "kcalc", "galculator", "xcalc"],
    "settings": ["gnome-control-center", "xfce4-settings-manager"],

    # User Projects
    "html project": ["garcon-url-handler 'file:///home/wahidtk/html and css/index.html'", "xdg-open '/home/wahidtk/html and css/index.html'"],
    "my website": ["garcon-url-handler 'file:///home/wahidtk/html and css/index.html'", "xdg-open '/home/wahidtk/html and css/index.html'"],
    "learning": ["garcon-url-handler 'file:///home/wahidtk/learning/index.html'", "xdg-open '/home/wahidtk/learning/index.html'"],
    "learning project": ["garcon-url-handler 'file:///home/wahidtk/learning/index.html'", "xdg-open '/home/wahidtk/learning/index.html'"],
}

# Known websites that should launch directly in the default browser
KNOWN_WEBSITES = {
    "youtube": "https://www.youtube.com",
    "google": "https://www.google.com",
    "github": "https://www.github.com",
    "reddit": "https://www.reddit.com",
    "twitter": "https://twitter.com",
    "x": "https://x.com",
    "wikipedia": "https://www.wikipedia.org",
    "gmail": "https://mail.google.com",
    "chatgpt": "https://chatgpt.com",
    "stackoverflow": "https://stackoverflow.com",
    "amazon": "https://www.amazon.com",
    "netflix": "https://www.netflix.com",
    "spotify": "https://open.spotify.com",
    "maps": "https://maps.google.com",
}

# Terminal apps that require a tty window to display properly
TERMINAL_APPS = {"cmatrix", "vim", "vi", "nvim", "top", "htop", "nano", "less"}

class AppLauncher:
    def __init__(self):
        self.apps = {}
        self.reload_installed_apps()

    def reload_installed_apps(self):
        """Scans Linux .desktop directories to index all launchable applications."""
        search_dirs = [
            "/usr/share/applications",
            "/usr/local/share/applications",
            os.path.expanduser("~/.local/share/applications"),
            "/var/lib/snapd/desktop/applications",
            "/var/lib/flatpak/exports/share/applications",
            os.path.expanduser("~/.local/share/flatpak/exports/share/applications")
        ]

        found_apps = {}

        for folder in search_dirs:
            if not os.path.isdir(folder):
                continue
            for desktop_file in glob.glob(os.path.join(folder, "*.desktop")):
                desktop_id = os.path.basename(desktop_file)
                base_key = desktop_id.replace(".desktop", "").lower()
                display_name = ""
                exec_cmd = ""
                generic_name = ""
                nodisplay = False
                terminal = False

                try:
                    with open(desktop_file, "r", encoding="utf-8", errors="ignore") as f:
                        for line in f:
                            line = line.strip()
                            if line == "[Desktop Entry]":
                                continue
                            if line.startswith("[") and line != "[Desktop Entry]":
                                break
                            if line.startswith("Name="):
                                display_name = line.split("=", 1)[1].strip()
                            elif line.startswith("Exec="):
                                exec_cmd = line.split("=", 1)[1].strip()
                            elif line.startswith("GenericName="):
                                generic_name = line.split("=", 1)[1].strip()
                            elif line.startswith("NoDisplay=true"):
                                nodisplay = True
                            elif line.startswith("Terminal=true"):
                                terminal = True
                except Exception:
                    continue

                if not exec_cmd:
                    continue

                # Strip field codes like %u, %F, %U
                clean_exec = re.sub(r"%[a-zA-Z]", "", exec_cmd).strip()

                app_info = {
                    "id": desktop_id,
                    "name": display_name,
                    "exec": clean_exec,
                    "path": desktop_file,
                    "terminal": terminal
                }

                # Allow Chrome OS host browser even if NoDisplay=true
                is_browser = "browser" in desktop_id.lower() or "chrome" in desktop_id.lower()
                if nodisplay and not is_browser:
                    continue

                if display_name:
                    found_apps[display_name.lower()] = app_info
                found_apps[base_key] = app_info
                if generic_name:
                    found_apps[generic_name.lower()] = app_info

        self.apps = found_apps

    def launch_url(self, url, friendly_name=None):
        """Opens a website or URL in the default browser."""
        target_name = friendly_name or url
        # Use garcon-url-handler on ChromeOS, fallback to xdg-open
        if shutil.which("garcon-url-handler"):
            return self._execute_cmd(f'garcon-url-handler "{url}"', target_name)
        elif shutil.which("xdg-open"):
            return self._execute_cmd(f'xdg-open "{url}"', target_name)
        elif shutil.which("google-chrome"):
            return self._execute_cmd(f'google-chrome "{url}"', target_name)
        return False, "No web browser handler found."

    def search_web(self, query):
        """Searches Google for a user query."""
        encoded = urllib.parse.quote_plus(query)
        url = f"https://www.google.com/search?q={encoded}"
        return self.launch_url(url, f"Google search for '{query}'")

    def launch(self, requested_app):
        """Attempts to match and launch the requested application, website, or tool."""
        req = requested_app.strip().lower()
        if not req:
            return False, "No app name specified."

        # Strip polite phrases or filler words
        req = re.sub(r"^(please\s+|can\s+you\s+|could\s+you\s+)", "", req).strip()

        # 1. Check known websites
        if req in KNOWN_WEBSITES:
            return self.launch_url(KNOWN_WEBSITES[req], req.capitalize())

        # Check for web URLs or domains (e.g. github.com, https://...)
        if req.startswith("http://") or req.startswith("https://") or req.startswith("www.") or any(req.endswith(tld) for tld in [".com", ".org", ".net", ".io", ".dev", ".in", ".co", ".app"]):
            url = req if req.startswith("http") else f"https://{req}"
            return self.launch_url(url, req)

        # Check settings alias specially
        if req in ("settings", "system settings", "control center"):
            for candidate in ["gnome-control-center", "xfce4-settings-manager", "lxappearance"]:
                if shutil.which(candidate):
                    return self._execute_cmd(candidate, req)
            if os.path.exists("/usr/bin/garcon-url-handler"):
                return True, "On ChromeOS, click the clock in the bottom right corner to open Settings."

        # 2. Check known aliases
        if req in COMMON_ALIASES:
            for candidate in COMMON_ALIASES[req]:
                # If candidate is a full command with arguments
                if " " in candidate:
                    binary = candidate.split()[0].strip("'\"")
                    if shutil.which(binary):
                        return self._execute_cmd(candidate, req)
                elif candidate in self.apps:
                    return self._execute_app(self.apps[candidate])
                elif shutil.which(candidate):
                    return self._execute_cmd(candidate, req)

        # 3. Check directly in indexed apps
        if req in self.apps:
            return self._execute_app(self.apps[req])

        # 4. Direct binary lookup in system PATH
        if shutil.which(req):
            return self._execute_cmd(req, req)

        # 5. Fuzzy search among .desktop names and IDs
        choices = list(self.apps.keys())
        if choices:
            match = process.extractOne(req, choices, scorer=fuzz.token_sort_ratio)
            if match and match[1] >= 60:
                matched_key = match[0]
                target = self.apps[matched_key]
                return self._execute_app(target)

            match_partial = process.extractOne(req, choices, scorer=fuzz.partial_ratio)
            if match_partial and match_partial[1] >= 75:
                matched_key = match_partial[0]
                target = self.apps[matched_key]
                return self._execute_app(target)

        # 6. Fallback: Search on Google if no app matches
        print(f"[*] Application '{requested_app}' not found locally. Searching web...")
        return self.search_web(requested_app)

    def _execute_app(self, app_info):
        """Launches application using gtk-launch, terminal wrapper, or direct exec."""
        desktop_id = app_info.get("id")
        app_name = app_info.get("name") or desktop_id
        is_terminal = app_info.get("terminal", False)
        exec_cmd = app_info.get("exec", "")

        # Terminal apps need a terminal window to run
        if is_terminal or any(term_app in exec_cmd.split() for term_app in TERMINAL_APPS):
            return self._execute_terminal_app(exec_cmd, app_name)

        # Try gtk-launch first if available
        if shutil.which("gtk-launch") and desktop_id:
            try:
                subprocess.Popen(
                    ["gtk-launch", desktop_id],
                    env=os.environ,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    start_new_session=True
                )
                return True, f"Launched {app_name}."
            except Exception:
                pass

        # Try gio launch
        if shutil.which("gio") and app_info.get("path"):
            try:
                subprocess.Popen(
                    ["gio", "launch", app_info["path"]],
                    env=os.environ,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    start_new_session=True
                )
                return True, f"Launched {app_name}."
            except Exception:
                pass

        return self._execute_cmd(exec_cmd, app_name)

    def _execute_terminal_app(self, cmd_string, friendly_name):
        """Wraps console/terminal applications in an active terminal emulator."""
        if shutil.which("x-terminal-emulator"):
            term_cmd = f"x-terminal-emulator -e {cmd_string}"
        elif shutil.which("garcon-terminal-handler"):
            term_cmd = f"garcon-terminal-handler -e {cmd_string}"
        elif shutil.which("xterm"):
            term_cmd = f"xterm -e {cmd_string}"
        else:
            term_cmd = cmd_string

        return self._execute_cmd(term_cmd, friendly_name)

    def _execute_cmd(self, cmd_string, friendly_name):
        """Executes a command detached from current process, with proper environment."""
        if not cmd_string:
            return False, f"Invalid launch command for {friendly_name}."

        # ChromeOS fix: if garcon-url-handler or x-www-browser is called without arguments, pass default URL
        clean_parts = cmd_string.split()
        if len(clean_parts) == 1 and clean_parts[0] in ("garcon-url-handler", "x-www-browser", "sensible-browser"):
            cmd_string = f"{clean_parts[0]} https://www.google.com"

        try:
            subprocess.Popen(
                cmd_string,
                shell=True,
                env=os.environ,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                start_new_session=True
            )
            return True, f"Launched {friendly_name}."
        except Exception as e:
            return False, f"Error launching {friendly_name}: {e}"

    def close(self, app_name):
        """Attempts to close an app using pkill."""
        target = app_name.strip().lower()
        if not target:
            return False, "No application specified to close."

        targets_to_try = [target]
        if target in COMMON_ALIASES:
            for item in COMMON_ALIASES[target]:
                binary = item.split()[0].replace("garcon-url-handler", "").replace("garcon-terminal-handler", "").strip()
                if binary:
                    targets_to_try.append(binary)

        closed_any = False
        for candidate in targets_to_try:
            if not candidate:
                continue
            try:
                res = subprocess.run(["pkill", "-f", candidate], capture_output=True)
                if res.returncode == 0:
                    closed_any = True
            except Exception:
                pass

        if closed_any:
            return True, f"Closed {target}."
        return False, f"No active process found for '{target}'."

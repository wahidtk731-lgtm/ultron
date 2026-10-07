#!/usr/bin/env python3
"""
Linux & ChromeOS App Discovery and Launcher Engine for Ultron.
Scans system .desktop entries, executables, user files, and environment handlers.
Supports:
- Device-first indexing: Scans user device for all installed apps and user workspace files.
- High-accuracy app launching: Subl, Geany, Settings, Chrome, Terminal, etc.
- Smart process termination: Closes Linux processes and handles ChromeOS host apps gracefully.
- File operations: Opens files in editors (e.g. Sublime Text) and writes text to files.
"""

import os
import sys
import glob
import subprocess
import shutil
import re
import urllib.parse
from rapidfuzz import process, fuzz

WORKSPACE_DIR = "/home/wahidtk"

# Common system aliases mapping friendly names to executables/handlers
COMMON_ALIASES = {
    # Browsers
    "browser": ["garcon-url-handler https://www.google.com", "xdg-open https://www.google.com", "google-chrome", "chromium"],
    "chrome": ["garcon-url-handler https://www.google.com", "xdg-open https://www.google.com", "google-chrome", "google-chrome-stable", "chromium"],
    "google chrome": ["garcon-url-handler https://www.google.com", "xdg-open https://www.google.com", "google-chrome", "chromium"],
    "google": ["garcon-url-handler https://www.google.com", "xdg-open https://www.google.com"],
    "firefox": ["firefox", "firefox-esr"],
    "chromium": ["chromium", "chromium-browser"],
    "internet": ["garcon-url-handler https://www.google.com", "xdg-open https://www.google.com"],
    "web": ["garcon-url-handler https://www.google.com", "xdg-open https://www.google.com"],

    # Terminals & Shell
    "terminal": ["garcon-terminal-handler", "x-terminal-emulator", "gnome-terminal", "xfce4-terminal", "konsole", "xterm"],
    "bash": ["garcon-terminal-handler", "x-terminal-emulator"],
    "console": ["garcon-terminal-handler", "x-terminal-emulator"],
    "shell": ["garcon-terminal-handler", "x-terminal-emulator"],
    "cmd": ["garcon-terminal-handler", "x-terminal-emulator"],

    # Code & Text Editors
    "sublime": ["/usr/bin/subl", "sublime_text", "/opt/sublime_text/sublime_text"],
    "sublime text": ["/usr/bin/subl", "sublime_text", "/opt/sublime_text/sublime_text"],
    "subl": ["/usr/bin/subl", "sublime_text"],
    "code": ["/usr/bin/subl", "sublime_text", "geany", "code", "nvim", "vim"],
    "editor": ["/usr/bin/subl", "sublime_text", "geany", "gedit", "nvim", "vim"],
    "text editor": ["/usr/bin/subl", "sublime_text", "geany", "gedit", "vim"],
    "geany": ["geany", "/usr/bin/geany"],
    "geany editor": ["geany", "/usr/bin/geany"],
    "vim": ["vim"],
    "nvim": ["nvim"],
    "neovim": ["nvim"],

    # Fun & Terminal Tools
    "cmatrix": ["cmatrix"],
    "matrix": ["cmatrix"],
    "top": ["top"],
    "htop": ["htop"],

    # Files & Storage
    "files": ["file_manager"],
    "file manager": ["file_manager"],
    "folder": ["file_manager"],
    "home": ["file_manager"],
    "my files": ["file_manager"],

    # Utilities
    "calculator": ["gnome-calculator", "kcalc", "galculator", "xcalc"],
    "calc": ["gnome-calculator", "kcalc", "galculator", "xcalc"],
    "settings": ["chrome_settings", "gnome-control-center", "xfce4-settings-manager"],
    "system settings": ["chrome_settings", "gnome-control-center", "xfce4-settings-manager"],
    "control center": ["chrome_settings", "gnome-control-center"],

    # User Projects
    "html project": ["garcon-url-handler 'file:///home/wahidtk/html and css/index.html'", "xdg-open '/home/wahidtk/html and css/index.html'"],
    "my website": ["garcon-url-handler 'file:///home/wahidtk/html and css/index.html'", "xdg-open '/home/wahidtk/html and css/index.html'"],
    "learning": ["garcon-url-handler 'file:///home/wahidtk/learning/learning.js'", "xdg-open '/home/wahidtk/learning'"],
}

# Known websites
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

TERMINAL_APPS = {"cmatrix", "vim", "vi", "nvim", "top", "htop", "nano", "less"}

class AppLauncher:
    def __init__(self):
        self.apps = {}
        self.user_files = {}
        self.recent_apps = []
        self.is_chromeos = os.path.exists("/opt/google/cros-containers") or shutil.which("garcon-url-handler") is not None
        self.reload_installed_apps()
        self.reload_user_files()

    def reload_installed_apps(self):
        """Scans Linux .desktop directories and system binaries to index all applications on user device."""
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

                clean_exec = re.sub(r"%[a-zA-Z]", "", exec_cmd).strip()

                app_info = {
                    "id": desktop_id,
                    "name": display_name,
                    "exec": clean_exec,
                    "path": desktop_file,
                    "terminal": terminal
                }

                is_browser = "browser" in desktop_id.lower() or "chrome" in desktop_id.lower()
                if nodisplay and not is_browser:
                    continue

                if display_name:
                    found_apps[display_name.lower()] = app_info
                found_apps[base_key] = app_info
                if generic_name:
                    found_apps[generic_name.lower()] = app_info

        # Also register known binaries on this device
        for bin_name, alias_key in [
            ("subl", "sublime text"),
            ("geany", "geany"),
            ("vim", "vim"),
            ("nvim", "nvim"),
            ("cmatrix", "cmatrix"),
        ]:
            bin_path = shutil.which(bin_name)
            if bin_path:
                found_apps[bin_name] = {
                    "id": f"{bin_name}.desktop",
                    "name": alias_key.title(),
                    "exec": bin_path,
                    "path": "",
                    "terminal": bin_name in TERMINAL_APPS
                }
                found_apps[alias_key] = found_apps[bin_name]

        self.apps = found_apps

    def reload_user_files(self):
        """Scans the user workspace (/home/wahidtk) for user files and scripts."""
        self.user_files = {}
        if not os.path.isdir(WORKSPACE_DIR):
            return

        try:
            for root, dirs, files in os.walk(WORKSPACE_DIR):
                # Avoid scanning deep hidden directories
                dirs[:] = [d for d in dirs if not d.startswith(".") and d not in ("venv", "node_modules", "__pycache__", "cache")]
                for f in files:
                    if f.startswith("."):
                        continue
                    full_p = os.path.join(root, f)
                    self.user_files[f.lower()] = full_p
        except Exception:
            pass

    def get_all_app_names(self):
        """Returns a list of all recognized application names on this device."""
        names = set(self.apps.keys())
        names.update(COMMON_ALIASES.keys())
        names.update(KNOWN_WEBSITES.keys())
        return sorted(list(names))

    def get_existing_app_names(self):
        """Returns only applications and tools that actually exist and are verified installed on this system."""
        existing = set()
        for name, info in self.apps.items():
            exec_bin = info.get("exec", "").split()[0]
            if shutil.which(exec_bin) or os.path.exists(exec_bin) or os.path.exists(info.get("path", "")):
                existing.add(name)
        for alias, cmds in COMMON_ALIASES.items():
            for cmd in cmds:
                binary = cmd.split()[0].replace("garcon-url-handler", "").replace("garcon-terminal-handler", "").strip()
                if binary and (shutil.which(binary) or os.path.exists(binary)):
                    existing.add(alias)
                    break
        return sorted(list(existing))

    def open_recent_app(self):
        """Launches the most recently opened application in this session."""
        if self.recent_apps:
            target = self.recent_apps[0]
            return self.launch(target)
        return False, "No recently opened application found."

    def launch_url(self, url, friendly_name=None):
        """Opens a website or URL in the host/default browser."""
        target_name = friendly_name or url
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

    def open_settings(self):
        """Opens native Ultron & System Settings panel on the device."""
        settings_script = os.path.join(os.path.dirname(os.path.abspath(__file__)), "settings_gui.py")
        if os.path.exists(settings_script):
            subprocess.Popen(
                [sys.executable, settings_script],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                start_new_session=True
            )
            return True, "Opened Settings."

        if shutil.which("garcon-url-handler"):
            subprocess.Popen(
                ["garcon-url-handler", "https://myaccount.google.com"],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                start_new_session=True
            )
            return True, "Opened Settings."

        return True, "Opened Settings."

    def open_file_manager(self):
        """Opens real desktop file manager without launching Chrome browser."""
        if shutil.which("pcmanfm"):
            subprocess.Popen(["/usr/bin/pcmanfm", "/home/wahidtk"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
            return True, "Opened File Manager."

        fm_script = os.path.join(os.path.dirname(os.path.abspath(__file__)), "file_manager_gui.py")
        if os.path.exists(fm_script):
            subprocess.Popen([sys.executable, fm_script], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
            return True, "Opened File Manager."

        return True, "Opened File Manager."

    def launch(self, requested_app):
        """Attempts to match and launch requested application, website, or tool."""
        req = requested_app.strip().lower()
        if not req:
            return False, "No app name specified."

        req = re.sub(r"^(please\s+|can\s+you\s+|could\s+you\s+)", "", req).strip()

        # 1. File manager handling (Never opens in Google Chrome!)
        if req in ("file manager", "files", "my files", "folder", "open file manager", "open files"):
            return self.open_file_manager()

        # 2. Settings handling
        if req in ("settings", "system settings", "control center", "preferences", "options"):
            return self.open_settings()

        # 2. Known websites
        if req in KNOWN_WEBSITES:
            return self.launch_url(KNOWN_WEBSITES[req], req.capitalize())

        # 3. Direct URL / domains
        if req.startswith("http://") or req.startswith("https://") or req.startswith("www.") or any(req.endswith(tld) for tld in [".com", ".org", ".net", ".io", ".dev", ".in", ".co", ".app"]):
            url = req if req.startswith("http") else f"https://{req}"
            return self.launch_url(url, req)

        # 4. Known aliases
        if req in COMMON_ALIASES:
            for candidate in COMMON_ALIASES[req]:
                if candidate == "chrome_settings":
                    return self.open_settings()
                if candidate == "file_manager":
                    return self.open_file_manager()
                if " " in candidate:
                    binary = candidate.split()[0].strip("'\"")
                    if shutil.which(binary):
                        return self._execute_cmd(candidate, req)
                elif candidate in self.apps:
                    return self._execute_app(self.apps[candidate])
                elif shutil.which(candidate):
                    return self._execute_cmd(candidate, req)

        # 5. Direct match in indexed apps
        if req in self.apps:
            return self._execute_app(self.apps[req])

        # 6. Direct binary lookup in PATH
        if shutil.which(req):
            return self._execute_cmd(req, req)

        # 7. Check if user is trying to open a file (e.g. "learning.py")
        if any(req.endswith(ext) for ext in [".py", ".cpp", ".js", ".html", ".css", ".txt", ".json", ".md"]):
            return self.open_file(req)

        # 8. Fuzzy search among indexed apps
        choices = list(self.apps.keys())
        if choices:
            match = process.extractOne(req, choices, scorer=fuzz.token_sort_ratio)
            if match and match[1] >= 65:
                matched_key = match[0]
                return self._execute_app(self.apps[matched_key])

            match_partial = process.extractOne(req, choices, scorer=fuzz.partial_ratio)
            if match_partial and match_partial[1] >= 80:
                matched_key = match_partial[0]
                return self._execute_app(self.apps[matched_key])

        # 9. Fallback: Search on Google if not found
        return self.search_web(requested_app)

    def resolve_file_path(self, filename):
        """Resolves a file path in the user workspace, creating it if it doesn't exist."""
        clean_name = filename.strip().strip("'\"")
        
        # Check if absolute path
        if os.path.isabs(clean_name):
            if not os.path.exists(clean_name):
                try:
                    os.makedirs(os.path.dirname(clean_name), exist_ok=True)
                    open(clean_name, "a").close()
                except Exception:
                    pass
            return clean_name

        # Check in cached workspace files
        lower_name = clean_name.lower()
        if lower_name in self.user_files:
            return self.user_files[lower_name]

        # Check directly in WORKSPACE_DIR
        target = os.path.join(WORKSPACE_DIR, clean_name)
        if not os.path.exists(target):
            try:
                os.makedirs(os.path.dirname(target), exist_ok=True)
                open(target, "a").close()
            except Exception:
                pass
        self.user_files[lower_name] = target
        return target

    def open_file(self, filename, preferred_editor=None):
        """Opens a file in Sublime Text, Geany, or default system editor."""
        file_path = self.resolve_file_path(filename)
        base_name = os.path.basename(file_path)

        # 1. Use Sublime Text if requested or available
        subl_bin = shutil.which("subl") or ("/opt/sublime_text/sublime_text" if os.path.exists("/opt/sublime_text/sublime_text") else None)
        if preferred_editor in ("sublime", "sublime text", "subl") or subl_bin:
            if subl_bin:
                try:
                    subprocess.Popen(
                        [subl_bin, file_path],
                        stdout=subprocess.DEVNULL,
                        stderr=subprocess.DEVNULL,
                        start_new_session=True
                    )
                    return True, f"Opened {base_name} in Sublime Text."
                except Exception as e:
                    pass

        # 2. Use Geany if available
        geany_bin = shutil.which("geany")
        if geany_bin:
            try:
                subprocess.Popen(
                    [geany_bin, file_path],
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    start_new_session=True
                )
                return True, f"Opened {base_name} in Geany."
            except Exception:
                pass

        # 3. Fallback to xdg-open
        if shutil.which("xdg-open"):
            subprocess.Popen(["xdg-open", file_path], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
            return True, f"Opened {base_name}."

        return False, f"Could not open {base_name}."

    def write_to_file(self, filename_or_path, text_content, append=True):
        """Writes or appends text to a file."""
        file_path = self.resolve_file_path(filename_or_path)
        base_name = os.path.basename(file_path)

        try:
            mode = "a" if append else "w"
            with open(file_path, mode, encoding="utf-8") as f:
                f.write(text_content.strip() + "\n")
            return True, f"Wrote '{text_content}' into {base_name}."
        except Exception as e:
            return False, f"Failed to write to {base_name}: {e}"

    def _execute_app(self, app_info):
        """Launches application using gtk-launch, terminal wrapper, or direct exec."""
        desktop_id = app_info.get("id")
        app_name = app_info.get("name") or desktop_id
        is_terminal = app_info.get("terminal", False)
        exec_cmd = app_info.get("exec", "")

        # Terminal apps need a terminal window
        if is_terminal or any(term_app in exec_cmd.split() for term_app in TERMINAL_APPS):
            return self._execute_terminal_app(exec_cmd, app_name)

        # Sublime Text special optimization
        if "sublime" in app_name.lower() or "sublime_text" in desktop_id.lower():
            subl_bin = shutil.which("subl") or "/opt/sublime_text/sublime_text"
            if os.path.exists(subl_bin):
                return self._execute_cmd(f"{subl_bin} -n", app_name)

        # Try gtk-launch
        if shutil.which("gtk-launch") and desktop_id:
            try:
                subprocess.Popen(
                    ["gtk-launch", desktop_id],
                    env=os.environ,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    start_new_session=True
                )
                return True, f"Opened {app_name}."
            except Exception:
                pass

        return self._execute_cmd(exec_cmd, app_name)

    def _execute_terminal_app(self, cmd_string, friendly_name):
        """Wraps console applications in an active terminal emulator."""
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
        """Executes a command detached from current process."""
        if not cmd_string:
            return False, f"Invalid launch command for {friendly_name}."

        try:
            subprocess.Popen(
                cmd_string,
                shell=True,
                env=os.environ,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                start_new_session=True
            )
            if friendly_name:
                clean_f = friendly_name.strip()
                if clean_f in self.recent_apps:
                    self.recent_apps.remove(clean_f)
                self.recent_apps.insert(0, clean_f)
            return True, f"Opened {friendly_name}."
        except Exception as e:
            return False, f"Error opening {friendly_name}: {e}"

    def close(self, app_name):
        """Closes an application cleanly, handling active window and processes gracefully."""
        target = app_name.strip().lower() if app_name else ""
        if not target or target in ("app", "this app", "current app", "the app"):
            # Close active foreground window
            try:
                if shutil.which("xdotool"):
                    subprocess.run(["xdotool", "getactivewindow", "windowclose"], timeout=1.0)
                    return True, "Closed active window."
                elif shutil.which("wmctrl"):
                    subprocess.run(["wmctrl", "-c", ":ACTIVE:"], timeout=1.0)
                    return True, "Closed active window."
            except Exception:
                pass
            return False, "No active application found to close."

        # Chrome browser special case
        if target in ("chrome", "google chrome", "browser", "chromium"):
            killed_local = False
            for proc_name in ["google-chrome", "chromium", "chrome", "google-chrome-stable"]:
                try:
                    res = subprocess.run(["pkill", "-if", proc_name], capture_output=True, timeout=1.0)
                    if res.returncode == 0:
                        killed_local = True
                except Exception:
                    pass

            if killed_local:
                return True, "Closed Chrome browser."

            if self.is_chromeos:
                return True, "Chrome is running on ChromeOS host. To close its window or tab, press Ctrl+W."

        # Sublime Text special case
        if target in ("sublime", "sublime text", "subl"):
            try:
                subprocess.run(["pkill", "-if", "sublime_text"], capture_output=True, timeout=1.0)
                return True, "Closed Sublime Text."
            except Exception:
                pass

        # Geany
        if target in ("geany", "geany editor"):
            try:
                subprocess.run(["pkill", "-if", "geany"], capture_output=True, timeout=1.0)
                return True, "Closed Geany."
            except Exception:
                pass

        # General processes
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
                # Try wmctrl first for graceful GUI close
                if shutil.which("wmctrl"):
                    subprocess.run(["wmctrl", "-c", candidate], timeout=1.0)
                res = subprocess.run(["pkill", "-if", candidate], capture_output=True, timeout=1.0)
                if res.returncode == 0:
                    closed_any = True
            except Exception:
                pass

        if closed_any:
            return True, f"Closed {target}."

        return False, f"No running process found for '{target}'."

    def turn_on_wifi(self):
        """Turns on Wi-Fi adapter via nmcli or rfkill."""
        try:
            if shutil.which("nmcli"):
                subprocess.run(["nmcli", "radio", "wifi", "on"], capture_output=True, timeout=2.0)
                return True, "Wi-Fi turned on."
            elif shutil.which("rfkill"):
                subprocess.run(["rfkill", "unblock", "wifi"], capture_output=True, timeout=2.0)
                return True, "Wi-Fi enabled."
        except Exception as e:
            return False, f"Could not toggle Wi-Fi: {e}"
        return False, "Wi-Fi control tool not found."

    def turn_off_wifi(self):
        """Turns off Wi-Fi adapter via nmcli or rfkill."""
        try:
            if shutil.which("nmcli"):
                subprocess.run(["nmcli", "radio", "wifi", "off"], capture_output=True, timeout=2.0)
                return True, "Wi-Fi turned off."
            elif shutil.which("rfkill"):
                subprocess.run(["rfkill", "block", "wifi"], capture_output=True, timeout=2.0)
                return True, "Wi-Fi disabled."
        except Exception as e:
            return False, f"Could not toggle Wi-Fi: {e}"
        return False, "Wi-Fi control tool not found."

    def turn_on_bluetooth(self):
        """Turns on Bluetooth adapter via bluetoothctl or rfkill."""
        try:
            if shutil.which("bluetoothctl"):
                subprocess.run(["bluetoothctl", "power", "on"], capture_output=True, timeout=2.0)
                return True, "Bluetooth turned on."
            elif shutil.which("rfkill"):
                subprocess.run(["rfkill", "unblock", "bluetooth"], capture_output=True, timeout=2.0)
                return True, "Bluetooth enabled."
        except Exception as e:
            return False, f"Could not toggle Bluetooth: {e}"
        return False, "Bluetooth control tool not found."

    def turn_off_bluetooth(self):
        """Turns off Bluetooth adapter via bluetoothctl or rfkill."""
        try:
            if shutil.which("bluetoothctl"):
                subprocess.run(["bluetoothctl", "power", "off"], capture_output=True, timeout=2.0)
                return True, "Bluetooth turned off."
            elif shutil.which("rfkill"):
                subprocess.run(["rfkill", "block", "bluetooth"], capture_output=True, timeout=2.0)
                return True, "Bluetooth disabled."
        except Exception as e:
            return False, f"Could not toggle Bluetooth: {e}"
        return False, "Bluetooth control tool not found."

    def clear_notifications(self):
        """Clears desktop notifications via dunstctl or makoctl."""
        try:
            if shutil.which("dunstctl"):
                subprocess.run(["dunstctl", "close-all"], capture_output=True, timeout=1.0)
                return True, "Cleared all notifications."
            elif shutil.which("makoctl"):
                subprocess.run(["makoctl", "dismiss", "-a"], capture_output=True, timeout=1.0)
                return True, "Dismissed notifications."
        except Exception:
            pass
        return True, "Notifications cleared."

    def create_file(self, filename):
        """Creates an empty file in workspace if it doesn't exist."""
        clean_name = filename.strip().strip("'\"") if filename else "new_file.txt"
        path = self.resolve_file_path(clean_name)
        try:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            if not os.path.exists(path):
                with open(path, "w", encoding="utf-8") as f:
                    pass
            self.reload_user_files()
            return True, f"Created file {os.path.basename(path)}."
        except Exception as e:
            return False, f"Failed to create file: {e}"

if __name__ == "__main__":
    launcher = AppLauncher()
    print(f"Total apps indexed from device: {len(launcher.apps)}")
    print(f"Indexed files in workspace: {len(launcher.user_files)}")
    print("Testing settings launch:")
    s, m = launcher.launch("settings")
    print(s, m)

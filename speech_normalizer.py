#!/usr/bin/env python3
"""
Speech Normalization & Phonetic Matching Engine for Ultron.
Fixes offline speech recognition (Vosk/Kaldi & Google STT) acoustic misrecognitions.
Provides high-accuracy phonetic normalization for wake words, apps, file extensions, and actions.
"""

import re

# Comprehensive regex of phonetic misrecognitions for "Ultron"
PHONETIC_VARIANTS_PATTERN = (
    r"\b("
    r"hey\s+ultron|ok\s+ultron|hi\s+ultron|hello\s+ultron|"
    r"all\s+thrown|all\s+throne|all\s+drone|all\s+drawn|"
    r"hall\s+thrown|hall\s+drone|"
    r"ultra\s+on|alter\s+on|"
    r"altron|oltron|ultran|eltron|"
    r"ultron"
    r")\b"
)

PHONETIC_REGEX = re.compile(PHONETIC_VARIANTS_PATTERN, re.IGNORECASE)

# Wake words variants
WAKE_WORDS = [
    "hey ultron",
    "ok ultron",
    "hi ultron",
    "hello ultron",
    "ultron",
    "hey all thrown",
    "all thrown",
    "hey all drone",
    "all drone",
    "hey altron",
    "altron",
    "ultra on",
    "hey ultra on",
]

# App & keyword phonetic replacements
APP_PHONETIC_REPLACEMENTS = [
    # Sublime Text
    (r"\b(sub\s+lime\s+text|sub\s+line\s+text|sublime\s+test|sober\s+lime\s+text|sub\s+light\s+text)\b", "sublime text"),
    (r"\b(sub\s+lime|sub\s+line|sober\s+lime|sub\s+light)\b", "sublime"),
    
    # Settings
    (r"\b(set\s+things|sat\s+things|sad\s+things|set\s+in|setting|setting\'s|system\s+set\s+things)\b", "settings"),
    
    # Chrome / Browser
    (r"\b(google\s+crown|google\s+crome|google\s+chrom)\b", "google chrome"),
    (r"\b(close\s+crown|close\s+crome|open\s+crown|open\s+crome)\b", lambda m: m.group(0).replace("crown", "chrome").replace("crome", "chrome")),
    
    # Terminal
    (r"\b(turn\s+min\s+al|terminator|ter\s+min\s+al|term\s+nal)\b", "terminal"),
    
    # Geany
    (r"\b(genie\s+editor|jeannie\s+editor|jeany\s+editor)\b", "geany editor"),
    (r"\b(genie|jeannie|jeany|gini)\b", "geany"),
    
    # Cmatrix
    (r"\b(see\s+matrix|sea\s+matrix|c\s+matrix)\b", "cmatrix"),
    
    # YouTube
    (r"\b(you\s+tube|u\s+tube|u-tube)\b", "youtube"),
    
    # Calculator
    (r"\b(cal\s+cue\s+later|calcu\s+later)\b", "calculator"),
    
    # File extensions & filenames (learning.py, learning.cpp, etc.)
    (r"\b(learning\s+dot\s+py|learning\s+dot\s+pi|learning\s+dot\s+pie|learning\s+py|learning\s+pi|learning\s+pie)\b", "learning.py"),
    (r"\b(learning\s+dot\s+cpp|learning\s+dot\s+c\s+plus\s+plus|learning\s+c\s+plus\s+plus|learning\s+cpp)\b", "learning.cpp"),
    (r"\b(learning\s+dot\s+js|learning\s+dot\s+j\s+s|learning\s+js)\b", "learning.js"),
    (r"\b(index\s+dot\s+html|index\s+dot\s+h\s+t\s+m\s+l|index\s+html)\b", "index.html"),
    (r"\bdot\s+(py|cpp|js|html|css|txt|json|md|sh|c|java)\b", r".\1"),
    
    # WhatsApp
    (r"\b(what\'?s?\s*app|wat[- ]?zap|watsapp|wa)\b", "whatsapp"),

    # Instagram
    (r"\b(insta|ig|in\s+sta|insta\s+gram)\b", "instagram"),

    # Spotify
    (r"\b(spot\s*ify|spotty\s*fy|spoti\s*pie|spot\s*fight)\b", "spotify"),

    # Telegram
    (r"\b(tele\s*gram|tellegram)\b", "telegram"),

    # Flashlight & Torch
    (r"\b(flash\s*light|flesh\s*light|tour\s*ch|torch)\b", "flashlight"),

    # Volume & Sound
    (r"\b(valume|vol\s*ume|valyoom|sound\s+level)\b", "volume"),

    # Screenshot
    (r"\b(screen\s*shot|screan\s*shot|snap\s+screen)\b", "screenshot"),

    # Camera & Photos
    (r"\b(cam\s*ra|kamra|kamera|cam)\b", "camera"),
    (r"\b(gel\s*ry|gallary|galery|photoes|photos)\b", "gallery"),

    # Clock & Alarm
    (r"\b(clok|clack|alaram|alerm)\b", "clock"),

    # Messages
    (r"\b(text\s+message|sms|massage|massages)\b", "messages"),

    # Action verbs
    (r"\b(right|ride|rite)\s+(hi|hello|text|code|print|something|notes)\b", r"write \2"),
    (r"\b(claws|clothes|closed)\s+(chrome|sublime|browser|terminal|geany|app|whatsapp|instagram|spotify)\b", r"close \2"),
    (r"\b(blue\s*tooth|blue\s*tooths|bluetooths|blootooth)\b", "bluetooth"),
    (r"\b(why\s*fi|wi\s*fi|wai\s*fai|wifi|wee\s*fee|waifai)\b", "wifi"),
    (r"\b(sirch|soorch|goggle|googel)\b", "search"),
    (r"\b(hoppen|opun|lunch|lanch)\b", "open"),
    (r"\b(notes|notification|notifications|notif|notifs)\b", "notifications"),
    (r"\b(clear|clean|dismiss|wipe|cancel)\s+(the\s+)?(notifications|notification|notifs)\b", "clear notifications"),
    (r"\b(quick\s+setting|kwick\s+settings)\b", "quick settings"),
    (r"\b(status\s+bar|stats\s+bar|notification\s+bar)\b", "status bar"),
    (r"\b(reel\s+section|reels\s+section|reels|reel)\b", "reels"),
    (r"\b(short\s+section|shorts\s+section)\b", "shorts"),
    (r"\b(recent\s+apps?|recently\s+opened\s+apps?|last\s+apps?|switch\s+apps?)\b", "recent apps"),
    (r"\b(make\s+file|create\s+file|new\s+file)\b", "create file"),
    (r"\b(edit\s+file|open\s+file|modify\s+file)\b", "edit file"),
]

def soundex(token: str) -> str:
    """Computes standard American Soundex code for phonetic token matching."""
    if not token or not token.isalpha():
        return ""
    token = token.upper()
    first = token[0]
    mapping = {
        'B': '1', 'F': '1', 'P': '1', 'V': '1',
        'C': '2', 'G': '2', 'J': '2', 'K': '2', 'Q': '2', 'S': '2', 'X': '2', 'Z': '2',
        'D': '3', 'T': '3',
        'L': '4',
        'M': '5', 'N': '5',
        'R': '6'
    }
    encoded = [first]
    prev = mapping.get(first, '')
    for char in token[1:]:
        code = mapping.get(char, '')
        if code and code != prev:
            encoded.append(code)
        prev = code
    encoded = [c for c in encoded if c]
    return (("".join(encoded)) + "000")[:4]

def levenshtein_similarity(s1: str, s2: str) -> float:
    """Computes normalized Levenshtein similarity [0.0, 1.0] between two strings."""
    if s1 == s2:
        return 1.0
    if not s1 or not s2:
        return 0.0
    n, m = len(s1), len(s2)
    dp = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(n + 1):
        dp[i][0] = i
    for j in range(m + 1):
        dp[0][j] = j
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            cost = 0 if s1[i - 1] == s2[j - 1] else 1
            dp[i][j] = min(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
    max_len = max(n, m)
    return 1.0 - (dp[n][m] / max_len)

def normalize_speech(text: str) -> str:
    """
    Normalizes speech-to-text output by standardizing phonetic variants of 'Ultron',
    apps, file names, and actions into canonical representations.
    """
    if not text:
        return ""
    
    cleaned = text.lower().strip()

    # 1. Replace phonetic misrecognitions of Ultron
    cleaned = PHONETIC_REGEX.sub("ultron", cleaned)

    # 2. Replace phonetic misrecognitions of apps, files, and actions
    for pattern, replacement in APP_PHONETIC_REPLACEMENTS:
        cleaned = re.sub(pattern, replacement, cleaned, flags=re.IGNORECASE)

    # 3. Clean multiple spaces
    cleaned = re.sub(r"\s+", " ", cleaned).strip()

    return cleaned

def is_wake_word(text: str) -> bool:
    """
    Detects if the input text contains a genuine wake word for Ultron.
    Avoids false positives on casual mentions or partial words.
    """
    if not text:
        return False
    
    cleaned = text.lower().strip()
    for w in WAKE_WORDS:
        if re.search(rf"\b{re.escape(w)}\b", cleaned):
            return True
            
    norm = normalize_speech(text)
    if re.search(r"\bultron\b", norm):
        return True
        
    return False

def strip_wake_words(text: str) -> str:
    """
    Strips wake words from text so downstream intent classifier and executor
    receive only the active command body.
    """
    if not text:
        return ""
        
    normalized = normalize_speech(text)
    
    cleaned = re.sub(r"\b(?:hey|ok|hi|hello)\s+ultron\b", " ", normalized, flags=re.IGNORECASE)
    cleaned = re.sub(r"\bultron\b", " ", cleaned, flags=re.IGNORECASE)
    
    for w in WAKE_WORDS:
        cleaned = re.sub(rf"\b{re.escape(w)}\b", " ", cleaned, flags=re.IGNORECASE)
        
    cleaned = re.sub(r"\s+", " ", cleaned).strip()
    return cleaned

if __name__ == "__main__":
    test_cases = [
        "hey all thrown open sub lime text and open learning dot py file and write hi in that file",
        "all drone open set things",
        "hey all turn close crown",
        "hey out run open see matrix",
        "all thrown write hi in that file",
    ]
    print("=== Testing Enhanced Speech Normalizer ===")
    for t in test_cases:
        norm = normalize_speech(t)
        body = strip_wake_words(t)
        print(f"Raw:  {t}")
        print(f"Norm: {norm}")
        print(f"Body: {body}\n")

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
    r"all\s+thrown|all\s+throne|all\s+throw\w*|all\s+thr\w+|"
    r"all\s+drone|all\s+drown\w*|all\s+drawn|all\s+dr\w+|"
    r"hall\s+dr\w+|hall\s+thr\w+|hall\s+thrown|"
    r"all\s+turn|all\s+grown|all\s+crown|all\s+round|all\s+train|all\s+churn|all\s+tone|"
    r"old\s+run|out\s+run|outrun|el\s+tr\w+|alter\s+on|ultra\s+on|"
    r"whole\s+turn|hole\s+turn|full\s+turn|"
    r"altron|ultra|ultran|oltron"
    r")\b"
)

PHONETIC_REGEX = re.compile(PHONETIC_VARIANTS_PATTERN, re.IGNORECASE)

# Wake words variants
WAKE_WORDS = [
    "hey ultron",
    "ultron",
    "hey all thrown",
    "all thrown",
    "hey all drone",
    "all drone",
    "hey altron",
    "altron",
    "hey ultra",
    "ultra",
    "hey out run",
    "out run",
    "outrun",
    "hey old run",
    "old run",
    "hey all turn",
    "all turn",
    "hey all round",
    "all round",
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
    
    # Action verbs
    (r"\b(right|ride)\s+(hi|hello|text|code|print|something|notes)\b", r"write \2"),
    (r"\b(claws|clothes|closed)\s+(chrome|sublime|browser|terminal|geany|app)\b", r"close \2"),
    (r"\b(blue\s*tooth|blue\s*tooths|bluetooths)\b", "bluetooth"),
    (r"\b(why\s*fi|wi\s*fi|wai\s*fai|wifi|wee\s*fee)\b", "wifi"),
    (r"\b(notes|notification|notifications|notif|notifs)\b", "notifications"),
    (r"\b(clear|clean|dismiss|wipe|cancel)\s+(the\s+)?(notifications|notification|notifs)\b", "clear notifications"),
    (r"\b(recent\s+apps?|recently\s+opened\s+apps?|last\s+apps?|switch\s+apps?)\b", "recent apps"),
    (r"\b(make\s+file|create\s+file|new\s+file)\b", "create file"),
    (r"\b(edit\s+file|open\s+file|modify\s+file)\b", "edit file"),
]

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
    Detects if the input text contains a wake word, either in raw or normalized form.
    """
    if not text:
        return False
    
    normalized = normalize_speech(text)
    
    if "ultron" in normalized:
        return True
        
    lower = text.lower()
    for w in WAKE_WORDS:
        if w in lower:
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
    
    cleaned = re.sub(r"\bhey\s+ultron\b", " ", normalized)
    cleaned = re.sub(r"\bultron\b", " ", cleaned)
    
    for w in WAKE_WORDS:
        cleaned = re.sub(rf"\b{re.escape(w)}\b", " ", cleaned)
        
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

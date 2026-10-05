#!/usr/bin/env python3
"""
Speech Normalization & Phonetic Matching Engine for Ultron.
Fixes offline speech recognition (Vosk/Kaldi) acoustic misrecognitions where
'Ultron' / 'Hey Ultron' is transcribed as 'all thrown', 'all drone', 'out run', etc.
"""

import re

# Comprehensive regex of phonetic misrecognitions produced by Vosk acoustic models for "Ultron"
PHONETIC_VARIANTS_PATTERN = (
    r"\b("
    r"all\s+thrown|all\s+throne|all\s+throw\w*|all\s+thr\w+|"
    r"all\s+drone|all\s+drown\w*|all\s+drawn|all\s+dr\w+|"
    r"hall\s+dr\w+|hall\s+thr\w+|hall\s+thrown|"
    r"all\s+turn|all\s+grown|all\s+crown|all\s+round|all\s+train|all\s+churn|all\s+tone|"
    r"old\s+run|out\s+run|outrun|el\s+tr\w+|alter\s+on|ultra\s+on|"
    r"whole\s+turn|hole\s+turn|full\s+turn|"
    r"altron|ultra"
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
]

def normalize_speech(text: str) -> str:
    """
    Normalizes speech-to-text output by standardizing phonetic variants of 'Ultron'
    into 'ultron' and cleaning unnecessary symbols.
    """
    if not text:
        return ""
    
    # 1. Lowercase
    cleaned = text.lower().strip()

    # 2. Replace phonetic misrecognitions with 'ultron'
    cleaned = PHONETIC_REGEX.sub("ultron", cleaned)

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
    
    # Check if normalized contains ultron
    if "ultron" in normalized:
        return True
        
    # Check against raw wake words list
    lower = text.lower()
    for w in WAKE_WORDS:
        if w in lower:
            return True
            
    return False

def strip_wake_words(text: str) -> str:
    """
    Strips wake words from text so that the downstream intent classifier
    and app launcher receive only the command body.
    """
    if not text:
        return ""
        
    normalized = normalize_speech(text)
    
    # Strip 'hey ultron' or 'ultron'
    cleaned = re.sub(r"\bhey\s+ultron\b", " ", normalized)
    cleaned = re.sub(r"\bultron\b", " ", cleaned)
    
    # Also strip any remaining raw wake words
    for w in WAKE_WORDS:
        cleaned = re.sub(rf"\b{re.escape(w)}\b", " ", cleaned)
        
    cleaned = re.sub(r"\s+", " ", cleaned).strip()
    return cleaned

if __name__ == "__main__":
    test_cases = [
        "hey all thrown",
        "hey all thrown open chrome",
        "all thrown open terminal",
        "all drone what time is it",
        "hey hall drone open geany",
        "hey all turn launch browser",
        "all thrown close sublime",
        "hey out run search google for python",
        "hey altron search for news",
        "hey ultra open youtube",
        "hey old run what is the date",
        "what can you do all thrown",
        "hey all drown open files",
    ]
    print("=== Testing Speech Normalizer ===")
    for t in test_cases:
        norm = normalize_speech(t)
        wake = is_wake_word(t)
        body = strip_wake_words(t)
        print(f"Raw: {t!r:<38} -> Norm: {norm!r:<36} | Wake: {wake} | Command: {body!r}")

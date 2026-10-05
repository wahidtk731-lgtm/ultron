#!/usr/bin/env python3
"""
Train Intent Recognition Model From Zero
Trains an offline Natural Language Processing (NLP) Machine Learning model from scratch.
Classifies commands into:
- open_app: open, launch, or start an application, website, or project
- close_app: close, kill, or terminate an application
- greet: greetings and presence checks
- query_capabilities: capabilities, help, and commands
- query_time: date and time inquiries
- search_web: search queries on the web
- exit: stop or shutdown the assistant
"""

import os
import sys
import json
import argparse

# Auto-fallback to local virtualenv python if scikit-learn is missing
try:
    import sklearn
except ImportError:
    base_dir = os.path.dirname(os.path.abspath(__file__))
    venv_py = os.path.join(base_dir, "venv", "bin", "python3")
    if os.path.exists(venv_py):
        os.execv(venv_py, [venv_py] + sys.argv)

import pickle
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import Pipeline
from sklearn.metrics import classification_report

# Import speech normalizer for phonetic tolerance
try:
    from speech_normalizer import normalize_speech, strip_wake_words
except ImportError:
    def normalize_speech(t): return t.lower()
    def strip_wake_words(t): return t.lower()

TRAINING_DATA = [
    # ------------------ open_app ------------------
    # Browsers
    ("open browser", "open_app"),
    ("launch browser", "open_app"),
    ("start browser", "open_app"),
    ("open the browser", "open_app"),
    ("launch the web browser", "open_app"),
    ("open internet", "open_app"),
    ("start web browser", "open_app"),
    ("can you open the browser", "open_app"),
    ("please launch browser", "open_app"),
    ("open chrome", "open_app"),
    ("start chrome", "open_app"),
    ("launch chrome", "open_app"),
    ("open google chrome", "open_app"),
    ("launch google chrome browser", "open_app"),
    ("start google chrome", "open_app"),
    ("open firefox", "open_app"),
    ("launch firefox browser", "open_app"),
    ("start firefox", "open_app"),
    ("open chromium", "open_app"),
    ("launch chromium", "open_app"),

    # Terminal & Shell
    ("open terminal", "open_app"),
    ("launch terminal", "open_app"),
    ("start terminal", "open_app"),
    ("open the terminal", "open_app"),
    ("open command line", "open_app"),
    ("launch command prompt", "open_app"),
    ("start bash", "open_app"),
    ("open bash shell", "open_app"),
    ("launch console", "open_app"),
    ("open console", "open_app"),
    ("run terminal", "open_app"),
    ("please open terminal", "open_app"),

    # Code & Text Editors
    ("open geany", "open_app"),
    ("launch geany", "open_app"),
    ("start geany editor", "open_app"),
    ("open geany ide", "open_app"),
    ("launch geany code editor", "open_app"),
    ("open sublime", "open_app"),
    ("launch sublime", "open_app"),
    ("open sublime text", "open_app"),
    ("launch sublime text editor", "open_app"),
    ("start sublime text", "open_app"),
    ("open code", "open_app"),
    ("launch visual studio code", "open_app"),
    ("open vscode", "open_app"),
    ("start vs code", "open_app"),
    ("run vim", "open_app"),
    ("open vim editor", "open_app"),
    ("start vim", "open_app"),
    ("launch vi", "open_app"),
    ("open neovim", "open_app"),
    ("launch nvim", "open_app"),
    ("open text editor", "open_app"),
    ("launch text editor", "open_app"),

    # Fun & Terminal Apps
    ("open cmatrix", "open_app"),
    ("launch matrix", "open_app"),
    ("start cmatrix", "open_app"),
    ("run matrix code", "open_app"),
    ("open htop", "open_app"),
    ("run htop", "open_app"),
    ("launch htop monitor", "open_app"),
    ("open system monitor", "open_app"),
    ("show process monitor", "open_app"),

    # Files & Storage
    ("open files", "open_app"),
    ("open file manager", "open_app"),
    ("open folder", "open_app"),
    ("open home folder", "open_app"),
    ("open my files", "open_app"),
    ("launch file manager", "open_app"),
    ("open explorer", "open_app"),

    # Utilities
    ("open calculator", "open_app"),
    ("launch calculator", "open_app"),
    ("start calc", "open_app"),
    ("open calc", "open_app"),
    ("open settings", "open_app"),
    ("launch control center", "open_app"),

    # User Projects & Websites
    ("open my website", "open_app"),
    ("launch my website", "open_app"),
    ("open html project", "open_app"),
    ("open html", "open_app"),
    ("open learning", "open_app"),
    ("open learning project", "open_app"),
    ("launch learning", "open_app"),
    ("open youtube", "open_app"),
    ("launch youtube", "open_app"),
    ("open github", "open_app"),
    ("launch github", "open_app"),
    ("open reddit", "open_app"),
    ("launch reddit", "open_app"),

    # ------------------ close_app ------------------
    ("close geany", "close_app"),
    ("close sublime", "close_app"),
    ("close sublime text", "close_app"),
    ("kill terminal", "close_app"),
    ("close terminal", "close_app"),
    ("terminate terminal", "close_app"),
    ("close chrome", "close_app"),
    ("close google chrome", "close_app"),
    ("close browser", "close_app"),
    ("close the browser", "close_app"),
    ("close firefox", "close_app"),
    ("close files", "close_app"),
    ("close calculator", "close_app"),
    ("close cmatrix", "close_app"),
    ("close editor", "close_app"),
    ("close the app", "close_app"),
    ("kill geany", "close_app"),
    ("terminate geany", "close_app"),
    ("kill sublime", "close_app"),

    # ------------------ greet ------------------
    ("hello", "greet"),
    ("hi", "greet"),
    ("hey", "greet"),
    ("hey ultron", "greet"),
    ("hey all thrown", "greet"),
    ("hey all drone", "greet"),
    ("hello ultron", "greet"),
    ("hi ultron", "greet"),
    ("are you there", "greet"),
    ("are you online", "greet"),
    ("good morning", "greet"),
    ("good afternoon", "greet"),
    ("good evening", "greet"),
    ("wake up", "greet"),
    ("wake up ultron", "greet"),
    ("yo ultron", "greet"),

    # ------------------ query_capabilities ------------------
    ("what can you do", "query_capabilities"),
    ("who are you", "query_capabilities"),
    ("what are you", "query_capabilities"),
    ("help me", "query_capabilities"),
    ("help", "query_capabilities"),
    ("what are your commands", "query_capabilities"),
    ("list commands", "query_capabilities"),
    ("show commands", "query_capabilities"),
    ("what apps can you open", "query_capabilities"),
    ("introduce yourself", "query_capabilities"),
    ("tell me about yourself", "query_capabilities"),
    ("what features do you have", "query_capabilities"),
    ("what do you do", "query_capabilities"),

    # ------------------ query_time ------------------
    ("what time is it", "query_time"),
    ("what is the time", "query_time"),
    ("tell me the time", "query_time"),
    ("current time", "query_time"),
    ("what is today's date", "query_time"),
    ("what is the date", "query_time"),
    ("what day is it", "query_time"),
    ("tell me today's date", "query_time"),
    ("time now", "query_time"),
    ("date today", "query_time"),

    # ------------------ search_web ------------------
    ("search google for python", "search_web"),
    ("search for latest news", "search_web"),
    ("search the web for linux", "search_web"),
    ("google artificial intelligence", "search_web"),
    ("look up machine learning", "search_web"),
    ("search google", "search_web"),
    ("search for weather", "search_web"),
    ("search for chromeos tips", "search_web"),
    ("google search python tutorial", "search_web"),
    ("search the web for coding", "search_web"),

    # ------------------ exit ------------------
    ("exit", "exit"),
    ("quit", "exit"),
    ("bye", "exit"),
    ("goodbye", "exit"),
    ("goodbye ultron", "exit"),
    ("shutdown", "exit"),
    ("power off", "exit"),
    ("stop listening", "exit"),
    ("go to sleep", "exit"),
    ("sleep", "exit"),
    ("exit assistant", "exit"),
    ("terminate assistant", "exit"),
    ("turn off", "exit"),
    ("close ultron", "exit"),
]

CUSTOM_INTENTS_FILE = "custom_intents.json"
EXPANDED_DATA_FILE = "expanded_training_data.json"

def load_all_training_samples():
    """Combines default TRAINING_DATA with expanded_training_data.json and custom_intents.json."""
    base_dir = os.path.dirname(os.path.abspath(__file__))
    expanded_path = os.path.join(base_dir, EXPANDED_DATA_FILE)
    custom_path = os.path.join(base_dir, CUSTOM_INTENTS_FILE)

    samples = list(TRAINING_DATA)

    # 1. Load expanded training data if generated
    if os.path.exists(expanded_path):
        try:
            with open(expanded_path, "r", encoding="utf-8") as f:
                exp_data = json.load(f)
                for item in exp_data:
                    if isinstance(item, dict) and "text" in item and "intent" in item:
                        samples.append((item["text"], item["intent"]))
                    elif isinstance(item, (list, tuple)) and len(item) == 2:
                        samples.append((item[0], item[1]))
            print(f"[*] Loaded {len(samples) - len(TRAINING_DATA)} enhanced dataset samples from {EXPANDED_DATA_FILE}")
        except Exception as e:
            print(f"[!] Warning reading expanded dataset: {e}")

    # 2. Load custom user intents
    if os.path.exists(custom_path):
        try:
            with open(custom_path, "r", encoding="utf-8") as f:
                custom_data = json.load(f)
                for item in custom_data:
                    if isinstance(item, (list, tuple)) and len(item) == 2:
                        samples.append((item[0], item[1]))
                    elif isinstance(item, dict) and "text" in item and "intent" in item:
                        samples.append((item["text"], item["intent"]))
            print(f"[*] Loaded user samples from {CUSTOM_INTENTS_FILE}")
        except Exception as e:
            print(f"[!] Warning reading custom intents: {e}")

    # Process samples with speech normalization to tolerate phonetic variants
    normalized_samples = []
    seen = set()
    for text, label in samples:
        norm_text = normalize_speech(text)
        if (norm_text, label) not in seen:
            seen.add((norm_text, label))
            normalized_samples.append((norm_text, label))
            
        # Also ensure raw variation is covered if different
        raw_clean = text.lower().strip()
        if (raw_clean, label) not in seen:
            seen.add((raw_clean, label))
            normalized_samples.append((raw_clean, label))

    return normalized_samples

def train_and_save_model(output_path="intent_model.pkl"):
    samples = load_all_training_samples()
    texts = [sample[0] for sample in samples]
    labels = [sample[1] for sample in samples]

    # Pipeline: TF-IDF n-grams (1-3) with sublinear term frequency scaling
    pipeline = Pipeline([
        ("tfidf", TfidfVectorizer(ngram_range=(1, 3), lowercase=True, sublinear_tf=True)),
        ("classifier", LogisticRegression(C=10.0, max_iter=300))
    ])

    print("[*] Training Intent Classification model from zero...")
    print(f"[*] Total training examples: {len(texts)}")
    pipeline.fit(texts, labels)

    # Classification report on training set
    predictions = pipeline.predict(texts)
    print("\n[+] Training Classification Report:")
    print(classification_report(labels, predictions, zero_division=0))

    base_dir = os.path.dirname(os.path.abspath(__file__))
    target_file = os.path.join(base_dir, output_path)

    with open(target_file, "wb") as f:
        pickle.dump(pipeline, f)

    print(f"[+] Model successfully trained and saved to: {target_file}")
    return pipeline

def test_model(query: str, model_path="intent_model.pkl"):
    """Tests a single sentence or phrase against the trained model."""
    base_dir = os.path.dirname(os.path.abspath(__file__))
    target_file = os.path.join(base_dir, model_path)
    if not os.path.exists(target_file):
        print(f"[-] Model not found at {target_file}. Train it first: python3 train_intent.py")
        return

    with open(target_file, "rb") as f:
        pipeline = pickle.load(f)

    norm_query = normalize_speech(query)
    cleaned_query = strip_wake_words(norm_query) or norm_query

    pred = pipeline.predict([cleaned_query])[0]
    probs = pipeline.predict_proba([cleaned_query])[0]
    classes = pipeline.classes_
    best_prob = max(probs)

    print(f"\nQuery:           {query!r}")
    print(f"Normalized:      {norm_query!r}")
    print(f"Command Body:    {cleaned_query!r}")
    print(f"Predicted Intent: \033[1;32m{pred}\033[0m (Confidence: {best_prob*100:.1f}%)")
    print("\nAll Probabilities:")
    for cls, prob in sorted(zip(classes, probs), key=lambda x: x[1], reverse=True):
        print(f"  - {cls:<20}: {prob*100:5.1f}%")

def add_custom_phrase(text: str, intent: str):
    """Adds a custom training example to custom_intents.json and retrains."""
    base_dir = os.path.dirname(os.path.abspath(__file__))
    custom_path = os.path.join(base_dir, CUSTOM_INTENTS_FILE)

    data = []
    if os.path.exists(custom_path):
        try:
            with open(custom_path, "r", encoding="utf-8") as f:
                data = json.load(f)
        except Exception:
            data = []

    data.append({"text": text.strip().lower(), "intent": intent.strip().lower()})
    with open(custom_path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)

    print(f"[+] Added ({text!r} -> {intent!r}) to {CUSTOM_INTENTS_FILE}")
    print("[*] Re-training model with new data...")
    train_and_save_model()

def main():
    parser = argparse.ArgumentParser(description="Train or Test Ultron Intent Recognition Model")
    parser.add_argument("--test", "-t", type=str, help="Test a single phrase or command against the trained model")
    parser.add_argument("--add", "-a", nargs=2, metavar=("PHRASE", "INTENT"), help="Add a new training phrase and intent, then retrain")
    parser.add_argument("--list-intents", action="store_true", help="List all available intent categories")
    args = parser.parse_args()

    if args.list_intents:
        intents = sorted(list(set(sample[1] for sample in TRAINING_DATA)))
        print("Available Intent Categories:")
        for idx, it in enumerate(intents, 1):
            print(f"  {idx}. {it}")
        return

    if args.test:
        test_model(args.test)
    elif args.add:
        add_custom_phrase(args.add[0], args.add[1])
    else:
        train_and_save_model()

if __name__ == "__main__":
    main()

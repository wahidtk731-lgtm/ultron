#!/usr/bin/env python3
"""
Train Intent Recognition Model From Zero
Trains an offline Natural Language Processing (NLP) Machine Learning model from scratch.
Classifies commands into:
- open_app: open or launch an application, website, or project
- open_file: open or edit a specific file (e.g. learning.py)
- write_file: write/append content to a file
- compound_action: chained multifunctional commands
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
import pickle
import argparse
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import Pipeline
from sklearn.metrics import classification_report

from speech_normalizer import normalize_speech, strip_wake_words
from generate_training_data import generate_full_dataset

def load_or_generate_dataset():
    """Loads dataset from expanded_training_data.json or generates it fresh."""
    json_path = os.path.join(os.path.dirname(__file__), "expanded_training_data.json")
    if os.path.exists(json_path):
        try:
            with open(json_path, "r", encoding="utf-8") as f:
                data = json.load(f)
                return [(item["text"], item["intent"]) for item in data]
        except Exception:
            pass
    return generate_full_dataset()

def train_and_save_model(output_file="intent_model.pkl"):
    """Trains a high-accuracy TF-IDF + Logistic Regression model from scratch."""
    dataset = load_or_generate_dataset()
    print(f"[*] Training on {len(dataset)} examples...")

    X = []
    y = []

    for text, intent in dataset:
        cleaned = strip_wake_words(normalize_speech(text))
        X.append(cleaned)
        y.append(intent)

    # State-of-the-art n-gram pipeline
    model = Pipeline([
        ("tfidf", TfidfVectorizer(
            ngram_range=(1, 3),
            min_df=1,
            sublinear_tf=True,
            strip_accents="unicode"
        )),
        ("clf", LogisticRegression(
            C=15.0,
            max_iter=1000,
            class_weight="balanced",
            solver="lbfgs"
        ))
    ])

    model.fit(X, y)

    # Quick validation report
    y_pred = model.predict(X)
    print("\n--- Training Set Evaluation ---")
    print(classification_report(y, y_pred, zero_division=0))

    # Save to disk
    with open(output_file, "wb") as f:
        pickle.dump(model, f)
    print(f"[+] Model successfully saved to {output_file}!")

    return model

if __name__ == "__main__":
    out_path = os.path.join(os.path.dirname(__file__), "intent_model.pkl")
    # Regenerate training data first
    print("[*] Generating updated training data from user device...")
    dataset = generate_full_dataset()
    json_file = os.path.join(os.path.dirname(__file__), "expanded_training_data.json")
    with open(json_file, "w", encoding="utf-8") as f:
        json.dump([{"text": s[0], "intent": s[1]} for s in dataset], f, indent=2)
    
    print("[*] Training high-accuracy intent model...")
    train_and_save_model(out_path)

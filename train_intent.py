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
import math
import collections
import argparse

from speech_normalizer import normalize_speech, strip_wake_words
from generate_training_data import generate_full_dataset

class PurePythonIntentClassifier:
    """High-accuracy Zero-Dependency TF-IDF Centroid Classifier for offline Termux/Linux."""
    def __init__(self):
        self.classes = []
        self.idf = {}
        self.centroids = {}

    def tokenize(self, text):
        words = text.lower().strip().split()
        toks = list(words)
        for i in range(len(words) - 1):
            toks.append(words[i] + "_" + words[i+1])
        return toks

    def fit(self, X, y):
        self.classes = sorted(list(set(y)))
        N = len(X)
        df = collections.defaultdict(int)
        doc_tf = []
        for text in X:
            toks = self.tokenize(text)
            counts = collections.Counter(toks)
            doc_tf.append(counts)
            for t in counts:
                df[t] += 1
        
        self.idf = {t: math.log((1 + N) / (1 + count)) + 1.0 for t, count in df.items()}
        
        class_vecs = {c: collections.defaultdict(float) for c in self.classes}
        class_counts = collections.defaultdict(int)
        for counts, c in zip(doc_tf, y):
            class_counts[c] += 1
            for t, cnt in counts.items():
                tfidf = (1.0 + math.log(cnt)) * self.idf[t]
                class_vecs[c][t] += tfidf
        
        self.centroids = {}
        for c in self.classes:
            vec = {}
            norm_sq = 0.0
            for t, val in class_vecs[c].items():
                avg = val / class_counts[c]
                vec[t] = avg
                norm_sq += avg * avg
            norm = math.sqrt(norm_sq) if norm_sq > 0 else 1.0
            self.centroids[c] = {t: v / norm for t, v in vec.items()}

    def predict(self, texts):
        res = []
        for text in texts:
            toks = self.tokenize(text)
            counts = collections.Counter(toks)
            q_norm_sq = 0.0
            q_vec = {}
            for t, cnt in counts.items():
                if t in self.idf:
                    val = (1.0 + math.log(cnt)) * self.idf[t]
                    q_vec[t] = val
                    q_norm_sq += val * val
            if q_norm_sq == 0:
                res.append("open_app")
                continue
            q_norm = math.sqrt(q_norm_sq)
            for t in q_vec:
                q_vec[t] /= q_norm
            
            best_c = "open_app"
            best_sim = -1.0
            for c, c_vec in self.centroids.items():
                sim = sum(q_vec[t] * c_vec.get(t, 0.0) for t in q_vec)
                if sim > best_sim:
                    best_sim = sim
                    best_c = c
            
            # Smart contextual guards for open-domain questions & vocabulary
            low = text.lower().strip()
            time_words = ("time", "date", "clock", "hour", "day", "month", "year", "today")
            question_starters = ("what", "who", "where", "when", "why", "how", "which", "tell me", "explain", "define", "meaning", "calculate", "translate")
            
            if best_c == "query_time" and not any(w in low for w in time_words):
                best_c = "search_web"
            elif best_c in ("create_file", "write_file", "open_file") and not any(w in low for w in ("file", ".py", ".cpp", ".js", ".html", ".css", ".txt", ".sh", ".md", ".json")):
                if any(low.startswith(q) for q in question_starters) or "?" in low:
                    best_c = "search_web"
            elif any(low.startswith(q) for q in question_starters) and not any(w in low for w in time_words):
                best_c = "search_web"

            res.append(best_c)
        return res

    def save(self, filepath):
        data = {
            "type": "PurePythonIntentClassifier",
            "classes": self.classes,
            "idf": self.idf,
            "centroids": self.centroids
        }
        with open(filepath, "w", encoding="utf-8") as f:
            json.dump(data, f)

    @classmethod
    def load(cls, filepath):
        with open(filepath, "r", encoding="utf-8") as f:
            data = json.load(f)
        obj = cls()
        obj.classes = data["classes"]
        obj.idf = data["idf"]
        obj.centroids = data["centroids"]
        return obj

def load_or_generate_dataset(force_refresh=False):
    """Generates dataset fresh from user's installed device apps and actions."""
    json_path = os.path.join(os.path.dirname(__file__), "expanded_training_data.json")
    if not force_refresh and os.path.exists(json_path):
        try:
            with open(json_path, "r", encoding="utf-8") as f:
                data = json.load(f)
                return [(item["text"], item["intent"]) for item in data]
        except Exception:
            pass
    dataset = generate_full_dataset()
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump([{"text": s[0], "intent": s[1]} for s in dataset], f, indent=2)
    return dataset

def train_and_save_model(output_file="intent_model.pkl"):
    """Trains a high-accuracy TF-IDF intent model from scratch (supports sklearn and zero-dep pure Python)."""
    dataset = load_or_generate_dataset(force_refresh=False)
    print(f"[*] Training on {len(dataset)} examples...")

    X = []
    y = []

    for text, intent in dataset:
        cleaned = strip_wake_words(normalize_speech(text))
        X.append(cleaned)
        y.append(intent)

    # Try Scikit-Learn if installed
    try:
        from sklearn.feature_extraction.text import TfidfVectorizer
        from sklearn.linear_model import LogisticRegression
        from sklearn.pipeline import Pipeline
        from sklearn.metrics import classification_report

        print("[*] Training using Scikit-Learn n-gram Logistic Regression...")
        model = Pipeline([
            ("tfidf", TfidfVectorizer(
                ngram_range=(1, 3),
                min_df=1,
                sublinear_tf=True,
                strip_accents="unicode"
            )),
            ("clf", LogisticRegression(
                C=25.0,
                max_iter=5000,
                class_weight="balanced",
                solver="lbfgs"
            ))
        ])
        model.fit(X, y)
        with open(output_file, "wb") as f:
            pickle.dump(model, f)
        print(f"[+] Scikit-learn model saved to {output_file}!")
        return model

    except (ImportError, ModuleNotFoundError):
        print("[*] Scikit-Learn not found. Training using high-speed Pure Python TF-IDF Centroid Engine...")
        pure_model = PurePythonIntentClassifier()
        pure_model.fit(X, y)
        json_output = output_file.replace(".pkl", ".json")
        pure_model.save(json_output)
        print(f"[+] Pure Python intent model saved to {json_output}!")
        return pure_model

def load_intent_model(model_dir=None):
    """Resiliently loads either Scikit-learn model or Pure Python model."""
    if model_dir is None:
        model_dir = os.path.dirname(os.path.abspath(__file__))
    
    json_path = os.path.join(model_dir, "intent_model.json")
    pkl_path = os.path.join(model_dir, "intent_model.pkl")

    if os.path.exists(json_path):
        try:
            return PurePythonIntentClassifier.load(json_path)
        except Exception:
            pass

    if os.path.exists(pkl_path):
        try:
            with open(pkl_path, "rb") as f:
                return pickle.load(f)
        except Exception:
            pass

    # If neither can be loaded, train fresh
    return train_and_save_model(pkl_path)

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

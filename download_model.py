#!/usr/bin/env python3
import os
import sys
import urllib.request
import zipfile

MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
ZIP_NAME = "vosk-model-small-en-us-0.15.zip"
EXTRACTED_DIR = "vosk-model-small-en-us-0.15"
TARGET_DIR = "model"

def download_and_setup_model():
    base_dir = os.path.dirname(os.path.abspath(__file__))
    model_path = os.path.join(base_dir, TARGET_DIR)

    if os.path.exists(model_path) and os.path.isdir(model_path):
        print(f"[+] Vosk offline model already exists at: {model_path}")
        return True

    zip_path = os.path.join(base_dir, ZIP_NAME)
    print(f"[*] Downloading offline English speech model from {MODEL_URL}...")
    
    try:
        def reporthook(blocknum, blocksize, totalsize):
            read = blocknum * blocksize
            if totalsize > 0:
                percent = read * 100 / totalsize
                s = f"\r[+] Downloading: {percent:.1f}% ({read // (1024*1024)}MB / {totalsize // (1024*1024)}MB)"
                sys.stdout.write(s)
                sys.stdout.flush()

        urllib.request.urlretrieve(MODEL_URL, zip_path, reporthook)
        print("\n[+] Download complete! Extracting model...")

        with zipfile.ZipFile(zip_path, 'r') as zip_ref:
            zip_ref.extractall(base_dir)

        extracted_path = os.path.join(base_dir, EXTRACTED_DIR)
        if os.path.exists(extracted_path):
            os.rename(extracted_path, model_path)

        if os.path.exists(zip_path):
            os.remove(zip_path)

        print(f"[+] Model installed successfully in: {model_path}")
        return True
    except Exception as e:
        print(f"\n[-] Failed to download or unpack model: {e}")
        return False

if __name__ == "__main__":
    download_and_setup_model()

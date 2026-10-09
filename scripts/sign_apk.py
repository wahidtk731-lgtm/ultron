#!/usr/bin/env python3
import sys
import os
import zipfile
import hashlib
import base64
import subprocess
import tempfile

def sign_apk(in_apk, out_apk):
    workdir = tempfile.mkdtemp(prefix="apk_sign_")
    key_pem = os.path.join(workdir, "key.pem")
    cert_pem = os.path.join(workdir, "cert.pem")
    
    # 1. Generate key and cert if not exist
    keystore_dir = os.path.expanduser("~/.android-sdk")
    os.makedirs(keystore_dir, exist_ok=True)
    saved_key = os.path.join(keystore_dir, "debug_key.pem")
    saved_cert = os.path.join(keystore_dir, "debug_cert.pem")
    
    if not (os.path.exists(saved_key) and os.path.exists(saved_cert)):
        cmd = [
            "openssl", "req", "-newkey", "rsa:2048", "-nodes",
            "-keyout", saved_key, "-x509", "-days", "10000",
            "-out", saved_cert, "-subj", "/CN=Ultron/O=UltronAssistant/C=US"
        ]
        subprocess.check_call(cmd)
    
    # 2. Compute MANIFEST.MF
    manifest_lines = [
        "Manifest-Version: 1.0",
        "Created-By: 1.0 (Ultron Android Builder)"
    ]
    
    entries_digests = {}
    
    with zipfile.ZipFile(in_apk, 'r') as zin:
        for info in zin.infolist():
            if info.filename.startswith("META-INF/"):
                continue
            data = zin.read(info.filename)
            sha1 = base64.b64encode(hashlib.sha1(data).digest()).decode('ascii')
            entries_digests[info.filename] = sha1
            
            manifest_lines.append("")
            manifest_lines.append(f"Name: {info.filename}")
            manifest_lines.append(f"SHA1-Digest: {sha1}")

    manifest_lines.append("")
    manifest_content = "\r\n".join(manifest_lines).encode("utf-8")
    
    # 3. Compute CERT.SF
    manifest_sha1 = base64.b64encode(hashlib.sha1(manifest_content).digest()).decode('ascii')
    sf_lines = [
        "Signature-Version: 1.0",
        "Created-By: 1.0 (Ultron Android Builder)",
        f"SHA1-Digest-Manifest: {manifest_sha1}"
    ]
    
    for filename, _ in entries_digests.items():
        # Each section digest in MANIFEST.MF
        section = f"Name: {filename}\r\nSHA1-Digest: {entries_digests[filename]}\r\n\r\n".encode("utf-8")
        sec_sha1 = base64.b64encode(hashlib.sha1(section).digest()).decode('ascii')
        sf_lines.append("")
        sf_lines.append(f"Name: {filename}")
        sf_lines.append(f"SHA1-Digest: {sec_sha1}")
        
    sf_lines.append("")
    sf_content = "\r\n".join(sf_lines).encode("utf-8")
    
    sf_path = os.path.join(workdir, "CERT.SF")
    rsa_path = os.path.join(workdir, "CERT.RSA")
    with open(sf_path, "wb") as f:
        f.write(sf_content)
        
    # 4. Sign CERT.SF with openssl smime to produce CERT.RSA
    cmd_sign = [
        "openssl", "smime", "-sign", "-in", sf_path, "-out", rsa_path,
        "-outform", "DER", "-inkey", saved_key, "-signer", saved_cert, "-nodetach"
    ]
    subprocess.check_call(cmd_sign)
    
    with open(rsa_path, "rb") as f:
        rsa_content = f.read()
        
    # 5. Write to out_apk
    with zipfile.ZipFile(in_apk, 'r') as zin:
        with zipfile.ZipFile(out_apk, 'w', compression=zipfile.ZIP_DEFLATED) as zout:
            # First write META-INF entries
            zout.writestr("META-INF/MANIFEST.MF", manifest_content)
            zout.writestr("META-INF/CERT.SF", sf_content)
            zout.writestr("META-INF/CERT.RSA", rsa_content)
            
            # Write all other entries
            for info in zin.infolist():
                if info.filename.startswith("META-INF/"):
                    continue
                data = zin.read(info.filename)
                zout.writestr(info, data)
                
    print(f"Successfully signed APK -> {out_apk}")

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Usage: sign_apk.py <in.apk> <out.apk>")
        sys.exit(1)
    sign_apk(sys.argv[1], sys.argv[2])

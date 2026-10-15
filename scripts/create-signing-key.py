#!/usr/bin/env python3
"""Create a persistent, local release signing identity; never overwrite one."""
import os
from pathlib import Path
import secrets
import shutil
import subprocess

root = Path(__file__).resolve().parents[1]
directory = root / ".signing"
key = directory / "eidolon-release.p12"
properties = directory / "release.properties"
if key.exists() or properties.exists():
    raise SystemExit("Signing material already exists. Reuse it; no files were changed.")

java_home = os.environ.get("JAVA_HOME")
keytool = str(Path(java_home) / "bin/keytool") if java_home else shutil.which("keytool")
if not keytool:
    raise SystemExit("Set JAVA_HOME to a JDK or put keytool on PATH.")

directory.mkdir(mode=0o700, exist_ok=True)
directory.chmod(0o700)
password = secrets.token_urlsafe(36)
environment = dict(os.environ, EIDOLON_SIGNING_PASSWORD=password)
# Restrict permissions from the instant the keystore is created.
os.umask(0o077)
subprocess.run([
    keytool, "-genkeypair", "-noprompt", "-storetype", "PKCS12",
    "-keystore", str(key), "-alias", "eidolon", "-keyalg", "RSA",
    "-keysize", "3072", "-validity", "10000",
    "-dname", "CN=Eidolon Android Release, OU=Mobile",
    "-storepass:env", "EIDOLON_SIGNING_PASSWORD",
    "-keypass:env", "EIDOLON_SIGNING_PASSWORD",
], env=environment, check=True)
with properties.open("x") as output:
    output.write("storeFile=.signing/eidolon-release.p12\n")
    output.write("keyAlias=eidolon\n")
    output.write(f"storePassword={password}\nkeyPassword={password}\n")
print("Release signing identity created in .signing/ (excluded from Git).")
print("Keep an encrypted backup of this directory to sign compatible updates.")

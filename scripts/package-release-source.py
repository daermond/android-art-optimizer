#!/usr/bin/env python3
"""Package tracked app sources and pinned pairing-library sources, never local secrets."""
import argparse
import copy
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import urllib.request
import zipfile

DEPENDENCIES = [
    ("kadb-2.1.4.zip", "https://codeload.github.com/flyfishxu/Kadb/zip/5bbec8684bbb5476476f2df11ccec25ba281dc76", "383841c9c545819d6c58e06486a5467c6b3619ceeb80bc271e20ba5cb200b6f0"),
    ("spake2-java-1.1.1.zip", "https://codeload.github.com/Flyfish233/spake2-java/zip/809ad7aa731f475c07763b1ad2c165ae192dfb5f", "327b417a5d0ef2361b1a89088ac8a87c66bba6d417be4f5331f5581c889f72c1"),
    ("ed25519-elisabeth-0.1.0-sources.jar", "https://repo.maven.apache.org/maven2/cafe/cryptography/ed25519-elisabeth/0.1.0/ed25519-elisabeth-0.1.0-sources.jar", "90ca1b91f187bc65982fc6fcae9ad44bbbcfbe791699ab629c14b61257d44cb6"),
    ("curve25519-elisabeth-0.1.0-sources.jar", "https://repo.maven.apache.org/maven2/cafe/cryptography/curve25519-elisabeth/0.1.0/curve25519-elisabeth-0.1.0-sources.jar", "0ca1baeb82b36073f62698bfd28ea6683183be44fb92fb82e640afa5c06fb2fd"),
]


def package(ref, tag, output, cache):
    if not re.fullmatch(r"v(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)", tag):
        raise ValueError("Use a vmajor.minor.patch tag")
    root = Path(__file__).resolve().parents[1]
    commit = subprocess.check_output(["git", "rev-parse", "--verify", f"{ref}^{{commit}}"], cwd=root, text=True).strip()
    source = subprocess.check_output(["git", "archive", "--format=zip", commit], cwd=root)
    # A tracked secret must never become a release artifact, even if someone adds it later.
    with zipfile.ZipFile(io.BytesIO(source)) as archive:
        for name in archive.namelist():
            path = Path(name)
            if "secrets" in path.parts or path.suffix.lower() in {".jks", ".keystore", ".p12", ".dpapi"} or path.name == "local.properties":
                raise ValueError(f"Refusing sensitive tracked file: {name}")
    output.parent.mkdir(parents=True, exist_ok=True)
    cache.mkdir(parents=True, exist_ok=True)
    dependencies = []
    payloads = []
    for name, url, expected in DEPENDENCIES:
        path = cache / name
        if not path.exists():
            with urllib.request.urlopen(url, timeout=60) as response:
                data = response.read()
            if hashlib.sha256(data).hexdigest() != expected:
                raise ValueError(f"Source checksum mismatch: {name}")
            path.write_bytes(data)
        data = path.read_bytes()
        if hashlib.sha256(data).hexdigest() != expected:
            raise ValueError(f"Cached source checksum mismatch: {name}")
        dependencies.append({"file": name, "url": url, "sha256": expected})
        payloads.append((name, data))
    manifest = {"tag": tag, "commit": commit, "dependencies": dependencies}
    temporary = output.with_suffix(".tmp")
    try:
        with zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED) as archive:
            with zipfile.ZipFile(io.BytesIO(source)) as app:
                for entry in app.infolist():
                    packaged = copy.copy(entry)
                    packaged.filename = f"android-art-optimizer/{entry.filename}"
                    archive.writestr(packaged, app.read(entry))
            for name, data in payloads:
                archive.writestr(f"dependency-sources/{name}", data)
            archive.writestr("SOURCE_MANIFEST.json", json.dumps(manifest, indent=2) + "\n")
        temporary.replace(output)
    finally:
        temporary.unlink(missing_ok=True)
    print(f"Source archive: {output.name} ({commit[:12]})")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tag")
    parser.add_argument("--ref", default="HEAD")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--cache", type=Path, default=Path("app/build/release-source-cache"))
    args = parser.parse_args()
    package(args.ref, args.tag, args.output or Path(f"android-art-optimizer-{args.tag}-source.zip"), args.cache)

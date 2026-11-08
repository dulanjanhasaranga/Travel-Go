#!/usr/bin/env python3
"""Vendor TravelGO's existing Google Fonts; run --download explicitly to refresh.

The default command verifies checked-in hashes without accessing the network.
Google's Unicode subsets are preserved, so English pages fetch only the two
Latin files while extended characters retain their existing font coverage.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import struct
from pathlib import Path
from urllib.parse import urlparse
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
FONT_DIR = ROOT / "src/main/resources/static/css/fonts"
CSS_PATH = ROOT / "src/main/resources/static/css/fonts.css"
MANIFEST_PATH = FONT_DIR / "manifest.json"
SOURCE_URL = "https://fonts.googleapis.com/css2?family=Inter:wght@300..700&family=Plus+Jakarta+Sans:wght@400..800&display=swap"
LICENSE_REVISION = "86aced91de05cd4f96a06838124cca45be337b2b"
FAMILIES = {
    "Inter": ("inter", "300 700"),
    "Plus Jakarta Sans": ("plus-jakarta-sans", "400 800"),
}
USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def fetch(url: str) -> bytes:
    if urlparse(url).scheme != "https" or urlparse(url).hostname not in {
        "fonts.googleapis.com", "fonts.gstatic.com", "raw.githubusercontent.com"
    }:
        raise ValueError("Font vendoring only permits the official HTTPS font sources.")
    with urlopen(Request(url, headers={"User-Agent": USER_AGENT}), timeout=30) as response:
        payload = response.read(2_000_001)
    if len(payload) > 2_000_000:
        raise ValueError("Unexpectedly large font source.")
    return payload


def download() -> None:
    source = fetch(SOURCE_URL)
    faces = re.findall(r"/\*\s*([a-z-]+)\s*\*/\s*(@font-face\s*\{[^}]+\})", source.decode("utf-8"))
    if len(faces) != 11:
        raise ValueError("Review upstream font subset changes before updating this vendor script.")
    FONT_DIR.mkdir(parents=True, exist_ok=True)
    output = [
        "/* Self-hosted, unmodified Google Fonts binaries. See /css/fonts/README.md and OFL licenses.",
        "   Variable weights and Unicode ranges preserve the existing typography; only used subsets load. */",
    ]
    manifest = {"sourceStylesheet": SOURCE_URL, "sourceStylesheetSha256": sha256(source),
                "licenseRevision": LICENSE_REVISION, "fonts": [], "licenses": []}
    for subset, block in faces:
        family = re.search(r"font-family:\s*'([^']+)'", block).group(1)
        slug, weight = FAMILIES[family]
        if re.search(r"font-weight:\s*([^;]+)", block).group(1).strip() != weight:
            raise ValueError("Unexpected weight range; preserve the established font request.")
        remote_url = re.search(r"src:\s*url\((https://fonts\.gstatic\.com/[^)]+\.woff2)\)", block).group(1)
        data = fetch(remote_url)
        if data[:4] != b"wOF2" or struct.unpack(">I", data[8:12])[0] != len(data):
            raise ValueError("Invalid WOFF2 payload from official source.")
        digest = sha256(data)
        filename = f"{slug}-{subset}-{weight.replace(' ', '-')}-{digest[:12]}.woff2"
        (FONT_DIR / filename).write_bytes(data)
        local_url = f"/css/fonts/{filename}"
        output.append(f"/* {family}: {subset} */\n" + block.replace(remote_url, local_url))
        manifest["fonts"].append({"family": family, "subset": subset, "weight": weight,
            "path": filename, "bytes": len(data), "sha256": digest, "source": remote_url})
    for family, (slug, _) in FAMILIES.items():
        source_folder = "plusjakartasans" if slug == "plus-jakarta-sans" else slug
        license_url = f"https://raw.githubusercontent.com/google/fonts/{LICENSE_REVISION}/ofl/{source_folder}/OFL.txt"
        data = fetch(license_url)
        if b"SIL OPEN FONT LICENSE Version 1.1" not in data:
            raise ValueError("Expected the upstream SIL Open Font License.")
        filename = f"{slug}-OFL.txt"
        (FONT_DIR / filename).write_bytes(data)
        manifest["licenses"].append({"family": family, "path": filename,
            "sha256": sha256(data), "source": license_url})
    CSS_PATH.write_text("\n\n".join(output) + "\n", encoding="utf-8")
    MANIFEST_PATH.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    check()


def check() -> None:
    manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    css = CSS_PATH.read_text(encoding="utf-8")
    if "https:" in css or "http:" in css:
        raise ValueError("Runtime font CSS must contain only local font URLs.")
    for item in manifest["fonts"] + manifest["licenses"]:
        if sha256((FONT_DIR / item["path"]).read_bytes()) != item["sha256"]:
            raise ValueError(f"Changed vendored asset: {item['path']}")
    latin = [item for item in manifest["fonts"] if item["subset"] == "latin"]
    print(json.dumps({"verifiedFontFiles": len(manifest["fonts"]),
        "verifiedLicenses": len(manifest["licenses"]), "latinBytes": sum(item["bytes"] for item in latin),
        "latinFiles": [{"path": "/css/fonts/" + item["path"], "bytes": item["bytes"]} for item in latin]}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--download", action="store_true", help="Explicitly refresh assets from official HTTPS sources")
    args = parser.parse_args()
    download() if args.download else check()

#!/usr/bin/env python3
"""Generate delivery copies of the approved logo without changing its master.

Requires the existing Pillow installation with WebP support. Run from anywhere:
  python scripts/optimize-navigation-logo.py
  python scripts/optimize-navigation-logo.py --check
"""

from __future__ import annotations

import argparse
import hashlib
import json
from io import BytesIO
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, __version__, features


ROOT = Path(__file__).resolve().parents[1]
IMAGE_ROOT = ROOT / "src/main/resources/static/images/brand"
MASTER = IMAGE_ROOT / "travelgo-airborne.png"
EXPECTED_MASTER_SHA256 = "e874a3c87a7e38e32208f8e54b389365282837b1916414a8921e89a185a22e40"
EVIDENCE = ROOT / "docs/evidence"
MANIFEST = EVIDENCE / "navigation-logo-manifest.json"
PREVIEW = EVIDENCE / "navigation-logo-preview.png"
WIDTHS = (192, 384)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def inspect_output(data: bytes, expected: Image.Image, width: int) -> dict:
    with Image.open(BytesIO(data)) as image:
        image.load()
        if image.format != "WEBP" or image.size != (width, width) or image.mode != "RGBA":
            raise ValueError(f"Unexpected format, canvas or alpha channel for {width}px copy")
        # Include RGB values underneath transparent pixels: exact=True keeps them too.
        if ImageChops.difference(image, expected).getbbox(alpha_only=False) is not None:
            raise ValueError(f"Lossless round-trip changed resized pixels for {width}px copy")
        alpha = image.getchannel("A")
        if alpha.getextrema() != (0, 255):
            raise ValueError(f"Transparent and opaque logo pixels were not retained for {width}px copy")
        return {
            "width": width,
            "height": width,
            "mode": image.mode,
            "alphaExtrema": list(alpha.getextrema()),
            "losslessPixelMatch": True,
            "bytes": len(data),
            "sha256": digest(data),
        }


def preview(master: Image.Image, outputs: dict[int, bytes]) -> bytes:
    cell, inset, label = 384, 18, 46
    sheet = Image.new("RGB", (3 * (cell + inset) + inset, cell + label + 2 * inset), "#f3f5f8")
    draw = ImageDraw.Draw(sheet)
    samples = [
        ("Approved master, resized to 384px", master.resize((cell, cell), Image.Resampling.LANCZOS)),
        ("192px WebP, shown at 384px", Image.open(BytesIO(outputs[192])).resize((cell, cell), Image.Resampling.LANCZOS)),
        ("384px WebP, shown at native size", Image.open(BytesIO(outputs[384]))),
    ]
    for index, (title, sample) in enumerate(samples):
        x, y = inset + index * (cell + inset), inset + label
        draw.text((x, inset + 9), title, fill="#17315c")
        for row in range(0, cell, 16):
            for column in range(0, cell, 16):
                shade = "#ffffff" if (row // 16 + column // 16) % 2 == 0 else "#e6e9ee"
                draw.rectangle((x + column, y + row, x + column + 15, y + row + 15), fill=shade)
        sheet.paste(sample, (x, y), sample.getchannel("A"))
    buffer = BytesIO()
    sheet.save(buffer, "PNG", optimize=True)
    return buffer.getvalue()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="verify existing derivatives and evidence without writing")
    args = parser.parse_args()
    if not features.check("webp"):
        raise SystemExit("The existing Pillow installation requires WebP support")
    original = MASTER.read_bytes()
    if digest(original) != EXPECTED_MASTER_SHA256:
        raise SystemExit("Approved logo master hash differs; no files were changed")
    with Image.open(BytesIO(original)) as loaded:
        loaded.load()
        master = loaded.copy()
    if master.size != (1254, 1254) or master.mode != "RGBA":
        raise SystemExit("Approved logo master dimensions or alpha channel differ")

    outputs: dict[int, bytes] = {}
    records = []
    for width in WIDTHS:
        target = IMAGE_ROOT / f"travelgo-airborne-{width}.webp"
        # Preserve the complete square canvas. CSS continues to position the artwork.
        resized = master.resize((width, width), Image.Resampling.LANCZOS)
        if args.check:
            data = target.read_bytes()
        else:
            buffer = BytesIO()
            resized.save(buffer, "WEBP", lossless=True, method=6, exact=True)
            data = buffer.getvalue()
        record = inspect_output(data, resized, width)
        record["path"] = target.relative_to(ROOT).as_posix()
        record["savedBytesPerRequest"] = len(original) - len(data)
        record["reductionPercent"] = round(100 * (1 - len(data) / len(original)), 2)
        outputs[width] = data
        records.append(record)

    if MASTER.read_bytes() != original:
        raise SystemExit("Logo master changed during processing; derivatives were not written")
    proof = {
        "format": "travelgo-navigation-logo-delivery-v1",
        "source": {"path": MASTER.relative_to(ROOT).as_posix(), "bytes": len(original),
                   "sha256": digest(original), "width": 1254, "height": 1254, "mode": "RGBA"},
        "encoder": {"pillow": __version__, "webp": features.version("webp"), "lossless": True,
                    "method": 6, "exact": True, "resize": "Lanczos", "crop": False},
        "outputs": records,
        "preview": PREVIEW.relative_to(ROOT).as_posix(),
    }
    if args.check:
        previous = json.loads(MANIFEST.read_text(encoding="utf-8"))
        if previous["source"] != proof["source"] or previous["outputs"] != proof["outputs"]:
            raise SystemExit("Committed evidence differs from the approved source or generated outputs")
        with Image.open(PREVIEW) as image:
            image.verify()
    else:
        EVIDENCE.mkdir(parents=True, exist_ok=True)
        for width, data in outputs.items():
            (IMAGE_ROOT / f"travelgo-airborne-{width}.webp").write_bytes(data)
        PREVIEW.write_bytes(preview(master, outputs))
        MANIFEST.write_text(json.dumps(proof, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"checked": args.check, "sourceSha256": digest(original), "outputs": records}, indent=2))


if __name__ == "__main__":
    main()

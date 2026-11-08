#!/usr/bin/env python3
"""Create AVIF delivery copies of the existing Alpine hero, retaining originals.

Pillow's AVIF encoder is a development-only tool. The WebP/JPEG picture
fallbacks stay available for browsers that do not support AVIF.
"""
from pathlib import Path
import hashlib
import json
from PIL import Image, ImageOps

ROOT = Path(__file__).resolve().parents[1]
IMAGES = ROOT / 'src/main/resources/static/images'
source = IMAGES / 'swiss-alps-photo-large.jpg'
manifest = {'source': source.name, 'sourceSha256': hashlib.sha256(source.read_bytes()).hexdigest(), 'quality': 55, 'copies': []}
with Image.open(source) as original:
    original = ImageOps.exif_transpose(original).convert('RGB')
    for width in (640, 960, 1600, 1920):
        height = round(original.height * width / original.width)
        copy = original.resize((width, height), Image.Resampling.LANCZOS)
        target = IMAGES / 'optimized' / f'swiss-alps-photo-{width}.avif'
        copy.save(target, format='AVIF', quality=55, speed=6)
        manifest['copies'].append({'file': target.name, 'width': width, 'height': height, 'bytes': target.stat().st_size})
evidence = ROOT / 'docs/evidence/hero-image-manifest.json'
evidence.parent.mkdir(parents=True, exist_ok=True)
evidence.write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
print(json.dumps(manifest['copies']))

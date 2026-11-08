#!/usr/bin/env python3
"""Create a small same-brand favicon and wire existing logo delivery copies.

The approved master PNG remains unchanged, as do saved/custom agency identities.
Run once after editing templates, or use --check in release preparation.
"""
from __future__ import annotations

import argparse
import hashlib
import re
from io import BytesIO
from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
BRAND = ROOT / "src/main/resources/static/images/brand"
TEMPLATES = ROOT / "src/main/resources/templates"
MASTER = BRAND / "travelgo-airborne.png"
EXPECTED_MASTER = "e874a3c87a7e38e32208f8e54b389365282837b1916414a8921e89a185a22e40"
FAVICON = BRAND / "travelgo-favicon-64.png"
SRCSET = "/images/brand/travelgo-airborne-192.webp 192w, /images/brand/travelgo-airborne-384.webp 384w"
CONDITIONAL_SRCSET = ('th:srcset="${agencyLogo == \'/images/travelgo-logo.png\' or '
    'agencyLogo == \'/images/brand/travelgo-airborne.png\' ? \'%s\' : null}"' % SRCSET)


def favicon_bytes() -> bytes:
    source = MASTER.read_bytes()
    if hashlib.sha256(source).hexdigest() != EXPECTED_MASTER:
        raise ValueError("Approved logo master differs; no files were changed")
    with Image.open(BytesIO(source)) as master:
        artwork = master.crop(master.getchannel("A").getbbox())
        artwork.thumbnail((60, 60), Image.Resampling.LANCZOS)
        icon = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
        icon.alpha_composite(artwork, ((64-artwork.width)//2, (64-artwork.height)//2))
        output = BytesIO()
        icon.save(output, format="PNG", optimize=True)
        return output.getvalue()


def optimize_template(text: str, navbar: bool) -> str:
    # Match only favicon links; image fallbacks deliberately retain the original URI
    # because the existing brand rules key off that source and custom-logo class.
    text = re.sub(r'<link\b[^>]*\brel="icon"[^>]*>',
        lambda match: match.group().replace('/images/brand/travelgo-airborne.png',
            '/images/brand/travelgo-favicon-64.png'), text)
    if navbar:
        return text  # navbar owner supplies the same srcset while redesigning its markup

    def optimize_image(match: re.Match) -> str:
        image = match.group()
        if '/images/brand/travelgo-airborne.png' not in image or 'srcset=' in image:
            return image
        if 'th:with="agencyLogo=' in image:
            attributes = CONDITIONAL_SRCSET
            size = "(max-width: 760px) 120px, 145px"
            if 'system-brand' in text:
                size = "120px"
            if 'sidebar-logo' in text:
                size = "100px"
        elif 'th:src="@{/images/brand/travelgo-airborne.png}"' in image:
            attributes = 'srcset="%s"' % SRCSET
            size = "96px"
        else:
            return image
        return image[:-1] + ' ' + attributes + ' sizes="' + size + '">'

    return re.sub(r'<img\b[^>]*>', optimize_image, text)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    icon = favicon_bytes()
    if args.check:
        if FAVICON.read_bytes() != icon:
            raise ValueError('Favicon delivery copy needs regeneration')
    else:
        FAVICON.write_bytes(icon)
    changed = []
    for path in TEMPLATES.rglob('*.html'):
        # Work on bytes through UTF-8 to preserve each template's line endings.
        text = path.read_bytes().decode('utf-8')
        replacement = optimize_template(text, path.name == 'navbar.html')
        if replacement != text:
            changed.append(path.relative_to(ROOT).as_posix())
            if not args.check:
                path.write_bytes(replacement.encode('utf-8'))
    if args.check and changed:
        raise ValueError('Templates need delivery references: ' + ', '.join(changed))
    print(f'{"Checked" if args.check else "Generated"} favicon: {len(icon):,} bytes; templates updated: {len(changed)}')


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Fingerprint shared navigation scripts and defer DOM-ready enhancements.

Updates only main.js/theme.js script tags. No template content or route changes.
Run after editing either script; --check makes a read-only release check.
"""
import argparse
import hashlib
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / 'src/main/resources'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    versions = {name: hashlib.sha256((RESOURCES / 'static/js' / name).read_bytes()).hexdigest()[:12]
                for name in ('main.js', 'theme.js')}
    changed = []
    for path in (RESOURCES / 'templates').rglob('*.html'):
        original = path.read_bytes()
        text = original.decode('utf-8')

        def update(match):
            tag = match.group()
            for name, version in versions.items():
                if '/js/' + name not in tag:
                    continue
                tag = re.sub(r'/js/' + re.escape(name) + r'(?:\?v=[^"}\s]+)?',
                             '/js/' + name + '?v=' + version, tag)
                if name == 'main.js' and not re.search(r'\bdefer\b', tag):
                    tag = tag.replace('<script', '<script defer', 1)
            return tag

        updated = re.sub(r'<script\b[^>]*>', update, text)
        if updated != text:
            changed.append(str(path.relative_to(ROOT)))
            if not args.check:
                path.write_bytes(updated.encode('utf-8'))
    if args.check and changed:
        raise SystemExit('Navigation script references are stale: run python scripts/build-navigation-assets.py')
    print(('Verified' if args.check else 'Updated') + f' navigation script references; {len(changed)} templates changed.')


if __name__ == '__main__':
    main()

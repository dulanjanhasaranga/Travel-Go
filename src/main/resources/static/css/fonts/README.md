# TravelGO typography

These are unmodified variable WOFF2 binaries supplied by the official Google Fonts CSS API, self-hosted to eliminate the external stylesheet, DNS and TLS dependency. Font family names, normal styles and previously requested weight ranges remain unchanged:

- Inter: weights 300–700.
- Plus Jakarta Sans: weights 400–800.

`/css/fonts.css` preserves Google's Unicode subset ranges. Ordinary English pages load only the two `latin` files. Latin Extended, Vietnamese, Cyrillic and Greek subsets remain available where the upstream family supports them; text is not reduced to ASCII. Characters outside either family's coverage continue to use the existing CSS system fallback fonts.

The upstream SIL Open Font License 1.1 texts and attribution are included alongside the fonts. `manifest.json` records each original URL, SHA-256 digest, byte length, and the official `google/fonts` source revision used for the license texts. Binaries are neither renamed internally nor modified.

Run `python scripts/vendor-navigation-fonts.py` from the project to check every bundled asset without network access. `--download` explicitly refreshes the files from the allowlisted official HTTPS sources; review its manifest and typography before accepting an upstream change.

If preloading, preload the `latin` WOFF2 files listed in the manifest with `as="font" type="font/woff2" crossorigin`. Keep the stylesheet's exact hashed URL. Extended subsets should be fetched only when matching characters occur.

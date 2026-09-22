# TravelGO image and motion update

Verified 11 September 2026. The Java application remains local at http://localhost:8080.

## Photos

Google Images was used to locate Unsplash travel imagery. Downloads came from the original Unsplash image CDN, not Google thumbnails. License: https://unsplash.com/license.

| Asset | Native delivered dimensions | Unsplash photo ID |
|---|---|---|
| Paris | 3840 × 2553 | photo-1502602898657-3e91760cbb34 |
| Tokyo | 3840 × 4160 | photo-1536098561742-ca998e48cbcc |
| Dubai | 3264 × 4896 | photo-1518684079-3c830dcef090 |
| Rome | 3840 × 2800 | photo-1552832230-c0197dd311b5 |
| Coastal hero | 3840 × 2553 | photo-1507525428034-b723cf961d3e |

Dubai is a native portrait above 4K in height, not a 3840-pixel-wide landscape. No claim is made that every auxiliary photograph in the application was replaced. Destination cards use responsive 1400-pixel versions and larger masters; hero backgrounds use image-set. Existing database image references map to local assets without changing SQL records. Custom catalogue image URLs remain supported. The supplied official logo remains in use.

## Motion

Added staggered scroll reveals, gentle image hover zoom, short hero entrance animations and supported-browser page transitions. Reduced-motion preferences are respected. These changes are implemented in website CSS and JavaScript. Figma editing remains pending a user-supplied Figma file link; no Figma animation has been authored or imported.

## Verification

- 19 selected isolated tests passed: target/4k-motion-verification.log.
- Maven packaging passed after stopping the old server that had locked the JAR: target/4k-motion-package.log.
- Restarted the application successfully on port 8080.
- Browser inspection confirmed all four destination images loaded with the expected responsive source sets, and the upgraded login page and official logo rendered correctly.
- Static browser screenshots do not establish animation timing across every device. No production database mutation was used for this visual verification.

The separate EPIC_USER_STORY_TRACEABILITY.md contains the full 43-story business-document audit and outstanding functional gaps.

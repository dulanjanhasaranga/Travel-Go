# Mobile navigation, performance and missing catalogue recovery — 8 November 2026

Release status: tested release prepared; live deployment verification pending.

The hosted database was initialized separately from the local TravelGO database.
The previous deployment had no destinations or tours; it did not copy the local
catalogue. A SELECT-only local inspection confirmed that the original public data
remains intact: **9 destinations, 3 categories, 9 tours, 4 hotels and 27 departures**.
The local transport, gallery and visa-checklist tables contained no rows to migrate.
Customer accounts, credentials, bookings, documents and financial records are excluded
from this public-content recovery and remain in their original databases.

## Public catalogue recovery

The release includes a canonical snapshot of only those five catalogue tables.
Its SHA-256 is `a6c53ba88a03027ef76fbe31b1c61a7a2806b40793677f0c5c832c5bd9d25b16`.
Every catalogue image referenced by the snapshot exists in the accepted static assets.

`HostedPublicCatalogueRecovery` activates only for the `cloud-evaluation` profile
and the exact `local-public-catalogue-20261108` operation. Ordinary local/test
profiles cannot activate it. The existing broad startup initializers stay disabled.
The accepted TLS, provider, MFA, restricted-user, SQL-init and Hibernate validation
gates remain intact. No schema migration or new credentials are involved.

`PublicCatalogueRecoveryService` applies the reviewed data in one transaction,
matching existing destinations, categories, tours, hotels and dated departures by
their business keys. Existing edits, inactive flags and existing seat availability
are preserved. Newly created departures start with their total capacity available,
because local customer reservations are not being migrated. Original schedule dates
are retained; the source dates span 20 October to 19 November 2026.

The operation locks the existing agency-settings row to serialize overlapping
starts and writes an audit completion marker in the same transaction. A failure
rolls back the additions and marker. Once complete, future starts skip recovery,
so later intentional catalogue deletions or changes are not resurrected.

## Navigation and delivery changes

- The mobile header now stays on one row. A custom two-line toggle opens a
  right-hand drawer containing the seven existing public links and account controls.
- The drawer has a clear active state, close control, backdrop, keyboard focus trap,
  focus/scroll restoration, touch targets, safe-area spacing, reduced-motion support
  and a usable inline navigation fallback when JavaScript is disabled.
- Native account/notification dropdowns, role-specific links and one CSRF-protected
  sign-out form are retained. The supplied logo artwork and author credits remain.
- Fourteen existing shared stylesheets are delivered as one fingerprinted bundle;
  the new font-face definitions are bundled before them. Source files remain editable
  and their cascade order is preserved.
- The same Inter and Plus Jakarta Sans variable fonts are self-hosted under the
  existing public `/css/` route. Original weight ranges, Unicode subsets, SIL licenses
  and fallback font stacks are retained. No external font connection is needed.
- Responsive logo WebP copies, a small favicon and Alpine hero AVIF copies reduce
  transfer size while keeping PNG/WebP/JPEG fallbacks and original assets.
- Text compression and static-only one-hour caching are enabled. Login/session/CSRF
  HTML keeps its `no-store` policy. Navigation scripts use content versions and defer.
- Two unused homepage catalogue computations were removed; visible filtering,
  ordering, page routes and transactional business behavior are unchanged.

## Actual performance measurements

Lighthouse13.0.1 used the installed Chrome, a fresh temporary profile, default
simulated mobile throttling and the same guarded, populated H2 preview on port8081.
The homepage figures are per-metric medians of three runs. Packages and desktop
were checked once each. All raw JSON/HTML reports are retained in
`target/mobile-performance-20261108/`; `performance-comparison.json` records settings
and raw numeric results. Diagnostic runs with an incorrect font route are stored
separately and are excluded from the final comparison.

| Home mobile | Before | After |
|---|---:|---:|
| Performance score | 69 | 95 |
| First Contentful Paint | 4.42s | 1.39s |
| Largest Contentful Paint | 5.36s | 2.88s |
| Speed Index | 4.43s | 1.41s |
| Cumulative Layout Shift | 0 | 0 |
| Total Blocking Time | 0ms | 0ms |
| Initial transfer | 1,698,199B | 710,154B |
| Stylesheet requests | 18 | 4 |

The packages mobile score improved from76 to95; desktop home improved from92 to100.
Mobile LCP remains above the requested2.5s goal. Field INP and free-host cold starts
are not measured by these lab runs. A free app waking from sleep can still take much
longer than a warm page load. No paid upgrade or keep-awake traffic was introduced.

## Validation and source scope

The clean release passed79 focused checks: catalogue restoration/rollback/concurrency,
cloud activation and release gates, public/role navigation, login routing, branding,
CSRF, CSP, resource caching and the isolated-preview database guard. The recovery
tests use the accepted cloud ORM mapping with no `tour_package_transports` table.
An additional resource/release-gate verification accompanies the final package.

Thirteen headless Edge browser checks passed across320,390,600,768,900,1199,1200
and1440px, with touch input, account dropdowns, keyboard focus, scroll restoration,
all public links, no-JavaScript and reduced-motion checks. Both brand fonts loaded;
no application JavaScript errors, broken local assets or external font failures were
observed. These are emulated-device checks, not physical iPhone/Safari testing.

Modified source groups are:

| Group | Files |
|---|---|
| Navigation | `fragments/navbar.html`, `navigation.css`, `main.js`, `theme.js`, one legacy selector in `style.css` |
| Delivery | `fragments/site-head.html`, `brand.css`, `fonts.css`, generated CSS bundle, responsive-image fragment, public logo/favicon/hero/font assets, `application.properties` |
| Homepage data | `PublicController.home`, catalogue rendering/query-budget tests |
| Recovery | `PublicCatalogueRecoveryService`, `HostedPublicCatalogueRecovery`, one audit repository lookup, cloud-profile operation setting, five-table public JSON snapshot |
| Narrow template transforms | Existing favicon/logo attributes and shared script version/defer tags; all other template content remains from the accepted release |
| Maintenance and QA | Asset generators/verifiers, local SELECT-only catalogue inspector/exporter, browser checks and focused regression tests |

The exact file list and hashes are recorded in
`target/mobile-performance-20261108/release-manifest.json`. The clean export derives
from revision`3b4677d4c67fec02cd7296774e76f675df87986b`, preserving its schema
compatibility mapping and all existing private-data/security workflows. Unrelated
dirty edits in the main checkout were not staged or exported.

After editing navigation JavaScript/CSS, run:

```powershell
python scripts/build-navigation-assets.py
python scripts/build-shared-css.py
python scripts/build-navigation-assets.py --check
python scripts/build-shared-css.py --check
python scripts/optimize-site-identity.py --check
python scripts/vendor-navigation-fonts.py
```

The catalogue snapshot is immutable for this operation. A future recovery requires
a separately reviewed version/checksum; the exporter is never called at app startup.
TravelGO remains on8081. Port8080 and the user's other project were not touched.

Measurement reference: [Lighthouse CLI](https://github.com/GoogleChrome/lighthouse#using-the-node-cli).

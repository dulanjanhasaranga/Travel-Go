# Shared asset delivery

The navigation head uses one content-fingerprinted CSS asset instead of fourteen
separate shared stylesheet requests. The individual source files stay in
`src/main/resources/static/css/` so the existing public, customer and staff design
rules remain editable. The generator preserves their exact previous order and
does not remove, minify or rewrite selectors. Asset URLs remain relative to the
same `/css/` directory.

After changing any shared stylesheet, run from the Java project directory:

```powershell
python scripts/build-shared-css.py
python scripts/build-shared-css.py --check
python scripts/build-base-styles.py
python scripts/build-base-styles.py --check
```

Commit/package the generated `site-bundle.<content hash>.css`, its source changes,
and `templates/fragments/site-head.html` together. `--check` is read-only and fails
if the checked-in asset or template reference is stale. The generator only removes
obsolete files carrying its own generated-file marker. No runtime npm dependency
or build server is needed.

The base stylesheet generator also expands `style.css`'s single `transitions.css`
import into `base-style.<content hash>.css`. It preserves the original rules and
cascade position on each page, while removing one blocking nested request. Keep
editing the original `style.css` and `transitions.css`, then regenerate and commit
the base asset and updated page links together. Both generators' `--check` modes
must pass before packaging.

The same Plus Jakarta Sans and Inter variable weights are self-hosted under
`/css/fonts/`, with `font-display:swap` definitions first in the bundle and early
Latin-font preloads. The old external Google Fonts import is removed. Unicode
subsets, licenses and the existing system-font fallback stacks are preserved.
`python scripts/vendor-navigation-fonts.py` verifies the binaries offline.

Spring's text-response compression is enabled above 1 KiB. Binary images/media are
excluded. Spring's static resource handler allows a one-hour cache with
revalidation after that hour. This setting does not apply to controller-rendered
pages, sessions, CSRF forms or private document controllers. Spring Security's
existing `no-store` HTML policy is preserved and is covered by
`StaticDeliveryIntegrationTests`. Fingerprinting changes the shared CSS URL on
every content change; hard-refresh during local edits to other unversioned assets.

Configuration reference: [Spring Boot application properties](https://docs.spring.io/spring-boot/4.0/appendix/application-properties/index.html).

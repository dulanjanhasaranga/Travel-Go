# TravelGO workflow and design review

Reviewed 10 September 2026. This review preserves the agreed academic-project rules and simulated payments. Recommendations below are future development, not silently applied business-policy changes.

## Assessment

The current application is a coherent visa-assisted tour booking prototype. Its customer, consultant, visa officer and administrator responsibilities form a sensible starting point. Transactional booking creation, protected documents, server-calculated payment amounts, stage checks, deadlines and duplicate-payment protection are important strengths.

It is not yet a complete operating travel agency system. A saved booking is an internal request, not evidence that an airline seat or hotel room has been reserved. A staff-entered visa decision must represent an external authority's decision; the agency itself cannot grant a visa.

## Current flow

Browse package → submit booking, travellers and optional accommodation → upload documents → officer verifies documents → officer issues visa/documentation quote → customer pays upfront → application processing → officer records approval or rejection → approved customer pays package/hotel balance → booking confirmation.

Rejection cancels the booking and refunds the documentation charge only. Customer edits/cancellations stop once review or payment begins. This is internally consistent with the previously agreed rules, but those rules are agency policy rather than universal travel-industry requirements.

## Recommended development, in priority order

| Area | Current limitation | Recommended real-world behaviour |
|---|---|---|
| Supplier availability | Package capacity and local hotel records do not prove date-specific availability | Track departures, available seats/rooms, temporary holds, supplier references, hold expiry and supplier confirmation. Confirm a trip only after all required suppliers acknowledge it. |
| Visa eligibility | Every booking requires a visa; one application represents the booking | Assess each traveller's nationality, passport, destination, transit and existing permissions. Support visa-exempt, visa-on-arrival and electronic-authorisation paths. A group needs individual document/decision tracking. Retain the current mandatory-visa rule for this project until policy changes are approved. |
| Visa processing | A staff status moves directly from processing to a decision | Track submission, appointment/biometrics where applicable, requests for additional evidence, authority reference, decision evidence and permit validity. Label officers as recording the authority's decision. Do not promise approval rates. |
| Lead times | A date just over three days away is accepted; quotes last 24 hours | Add destination/service lead times, working-day calendars and supplier ticketing deadlines. Show an explicit timezone and expiry. A payment deadline is not proof that a visa can be obtained in time. |
| Quotes and money | Two fixed payments, one documentation-only rejection refund | Store accepted itemised quote versions, currency, taxes, inclusions/exclusions, supplier terms and customer acceptance. Later add deposits, reconciled gateway payments, receipts and refund tracking. Keep simulation clearly identified until then. |
| Changes and cancellations | Self-service becomes locked; broader refunds are deferred | Add a consultant-managed change/cancellation request after locking, with fees, supplier impact, customer approval and recorded reasons. Display refund eligibility before purchase; do not imply the documentation-only rule applies to all cancellations. |
| Customer communication | Inquiry replies are saved but not delivered to the customer | Provide a customer conversation/inbox, delivery state, unread controls and notifications. Add a clear next action to every booking state and communicate upcoming deadlines. |
| Access control | Configurable permission checkboxes do not enforce access; some existing disabled-staff sessions remain usable | Either enforce stored permissions consistently or make the interface accurately describe fixed roles. Revoke or revalidate disabled accounts across every mutation. These are priority issues before real customer use. |
| Account recovery and consent | No functioning password recovery; previous social buttons and terms link were demos | Implement expiring password-reset tokens, verified email, staff MFA and a real published privacy/terms process with versioned consent. The redesign removes misleading controls; it does not claim these services exist. |
| Trip completion | Limited fulfilment/post-trip lifecycle | Add vouchers, final itinerary, emergency support details, departure/completion stages, and reviews restricted to eligible completed trips with ratings 1–5. |
| Operational readiness | Prototype storage and simulated financial operations | Define document retention, access audit, backup/restore, secure hosting, monitoring and finance reconciliation before handling real bookings. Avoid introducing live integrations until those controls exist. |

Travel-document requirements vary with personal details and itinerary; this is supported by [IATA's travel-requirement guidance](https://www.iata.org/en/publications/newsletters/iata-knowledge-hub/latest-travel-restrictions-and-guidance/). For example, the [UK Standard Visitor overview](https://www.gov.uk/standard-visitor) distinguishes travellers who require a visa from those who do not. These examples support configurable eligibility; they are not a substitute for checking the rules for a particular traveller. The development priorities above are recommendations based on the project review.

## Implemented in this design update

- Professional responsive login with a coastal photograph, clearer hierarchy, accessible labels, password reveal and real account/support links. Existing Spring Security POST, CSRF, errors and session behaviour remain intact.
- Removed publicly prefilled demo credentials, simulated social-login/registration buttons, unsupported remember-me and password-recovery controls, and a nonfunctional prechecked terms checkbox/link. Real terms and consent remain future work.
- Original supplied transparent logo copied unchanged to `static/images/travelgo-logo.png`, used in shared public navigation/footer, all staff/admin sidebars, account pages and page icons. CSS displays the artwork without its large transparent margin; no substitute logo was generated.
- Shared blue/orange brand styling, gentler card interactions and single-entry scroll reveals. Reduced-motion preference disables animations/transitions. Content remains visible without JavaScript; important alerts and formatted financial totals are not removed or rewritten by decorative effects.
- Locally served 1400-pixel destination photographs for the original Paris, Tokyo, Dubai and Rome catalogue images. Custom recorded images remain supported and SQL image values are preserved. Added a Paris photograph to About and retained the existing high-quality coastal hero.
- Improved mobile navigation; updated homepage pricing language to distinguish separately quoted visa/documentation charges. Replaced unsubstantiated headline customer counts, approval rate and 24/7 support claims with service steps.
- Fixed the admin role detail template's reserved iteration-variable name. This fixes rendering only; granular permission enforcement remains outstanding.

## Verification and limits

- Maven `verify` passed with 19 selected tests: the 18 normal isolated workflow/context tests plus the existing all-controller-page rendering/form-wiring audit. The test datasource was H2 `travelgo-test`, not the application's MySQL database.
- The controller rendering test passed again after mobile navigation and homepage copy changes. It exercises all 36 page routes with appropriate synthetic roles and inspects rendered form wiring. It does not prove every business policy or browser interaction is correct.
- Browser checked desktop and 390-pixel mobile login, password reveal, mobile homepage/navigation, final registration contrast/layout and destination catalogue. All four destination photos loaded from local URLs at 1400-pixel natural width. The copied logo's SHA-256 matches the supplied original.
- Final account verification also passed both selected integration tests: registration/login/session/logout/disabled-login with SQL assertions, and all-controller-page rendering/form wiring. Final build log: `target/design-account-verification.log`.
- The running application was restarted locally with its existing MySQL profile. No schema or financial-policy migration was introduced. Disposable MySQL workflow-lock checks from the previous core upgrade were not repeated for this presentation change.
- The earlier connectivity audit remains relevant for inquiry delivery, review validation, permissions and disabled-session handling. Its role-page and demo-login findings are superseded by this update. A full production-readiness audit is still needed before deployment.

## Image provenance

The official logo was supplied by the user as `27Sep24 Simon Pro Upload.png`. The coastal hero was an existing project asset. Higher-resolution copies of the same Unsplash images already referenced by the catalogue were downloaded on the review date:

- Paris: https://images.unsplash.com/photo-1502602898657-3e91760cbb34?auto=format&fit=crop&w=1400&q=85
- Tokyo: https://images.unsplash.com/photo-1503899036084-c55cdd92da26?auto=format&fit=crop&w=1400&q=85
- Dubai: https://images.unsplash.com/photo-1512453979798-5ea266f8880c?auto=format&fit=crop&w=1400&q=85
- Rome: https://images.unsplash.com/photo-1552832230-c0197dd311b5?auto=format&fit=crop&w=1400&q=85

This records asset provenance, not a separate licensing audit.

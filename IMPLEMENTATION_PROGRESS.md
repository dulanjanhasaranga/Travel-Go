# Transactional platform upgrade

Update 13 September 2026: see MYSQL_AND_PACKAGES.md for the subsequent package restoration and MySQL persistence repair. The new persistent demonstration is on port 8083; the earlier 8082 fixture is disposable and should not be used to retain entered data.

Baseline: Spring Boot 4.0.7 / Java 17 target, Thymeleaf, Spring Security sessions and BCrypt, JPA repositories, MySQL. Existing service transactions lock booking rows for visa/payment mutations. SMTP currently serves password recovery only; no configured outbound sender has been verified. Package/destination data is persisted. ContactMessage is the existing inquiry entity.

No Git repository is present. A source and pom.xml checkpoint was created under checkpoints/ before changes. Database records and local.properties were excluded, but the historical source configuration contained a database credential. Keep this ignored checkpoint private. It is not a database backup. Baseline Maven package completed with 50 isolated tests passing. Tests use H2/test or disposable MySQL, never the application database.

Execution order: baseline and audit → additive domain changes → account validation → email queue → booking emails → payment emails → catalogue → inquiries → dashboards → visual/responsive refinement → regression verification.

Decisions: keep visa approval before final payment and simulated payments. Booking creation emails say request received, not confirmed travel. New explicit admin inquiry requirement adds admin management alongside consultants. Do not change existing staff account assignments or historical finances. SMTP delivery stays disabled until deployment configuration enables it.

## Verification and implementation status — 12 September 2026

The upgrade is implemented and built locally. It has been exercised against isolated H2 and disposable MySQL, plus a synthetic browser preview on port 8082. It has **not** been installed into the existing application database or verified against a real email provider. The existing application on port 8080 was not replaced during this verification.

### 1. Architecture and files

Existing Spring Boot controllers, Thymeleaf templates, repositories, security and schema relationships are retained. Main changes are in BookingService, UserService, PaymentService, ContactMessageService, PublicController, customer/staff/admin controllers, and their templates. New services include TransactionalEmailService, EmailDeliveryService, BookingEmailService, CatalogueExpansionService and BookingSummaryService. New supporting files include OutboundEmail, InquiryRequest, PackageContentController, CustomerInquiryController, the email template and package-content/inquiry views. The run.ps1 launcher now uses the installed JDK instead of an obsolete machine-specific path.

### 2. Database model

Additive fields cover booking submission tokens/fingerprints, package itinerary/exclusions/traveler information, richer inquiries, inquiry optimistic versions and a durable email outbox. Existing statuses and relationships remain valid. Schema reference: database/platform-upgrade.sql. Automatic schema updates retain the project's existing convention; applying them to historical live data remains pending a verified backup.

### 3. Accounts and backend validation

Registration validates and normalizes email addresses, checks case-insensitive duplicates, validates password length including BCrypt's byte limit, and hashes passwords. Login lookup is case-insensitive. This validates email format; it does not prove mailbox ownership. Existing addresses are not silently rewritten. Customer-facing unexpected failures no longer expose database/SMTP diagnostics.

### 4. Central email delivery

Business transactions queue unique email events. A worker commits a SENDING claim before contacting SMTP, preventing parallel workers from sending the same queued event. Successful sends become SENT; ambiguous failures require review instead of automatic retries. A process crash can leave SENDING for reconciliation. SMTP cannot guarantee exactly-once receipt. Mail remains disabled by default; automated tests use a mocked sender. Password recovery retains its existing mail path. The admin dashboard shows queue/review counts; there is no resend/reconciliation management UI yet.

### 5. Booking email and retry behavior

Booking creation now queues a branded request-received email with reference, itinerary context, stored costs and next steps. Eligible customer cancellation also queues a message. Booking requests use owner-scoped submission keys and payload fingerprints under a user-row lock: identical retries return the same booking, while changed payloads using the same key are rejected. Invalid creation rolls back the booking, travelers, hotel and queued email together.

### 6. Payment confirmation

Successful final payment queues one Fully Paid message, with actual payment reference, totals and booking status. The server retains visa-before-final-payment, amount/deadline validation, booking-row locking and refund safeguards. Failed/pending payments do not trigger that final-success branch. Simulated payments remain clearly described. Original payments and partial documentation refunds remain separate records.

### 7. Catalogue and images

Three packages are available through an opt-in, idempotent repository-backed installer: London Heritage & Thames Discovery (5 days, USD 1,190), Singapore Gardens & Neighbourhoods (4 days, USD 790), and Istanbul Old City & Bosphorus (6 days, USD 990). Each has itinerary, inclusions/exclusions and audience guidance. Flights, accommodation and separately quoted visa charges are excluded from these new catalogue prices. Consultants can edit their content and visibility. These are authored demonstration offers, not supplier-confirmed departures.

New local destination photos include optimized responsive files and high-resolution originals: London and Istanbul at 3840×2560; Singapore at 3320×4096 (portrait 4K long edge). Provenance is in CATALOGUE_IMAGE_SOURCES.md. A broken responsive image now clears srcset before using the fallback. The supplied official logo is retained across the website and embedded in transactional emails. No multi-image gallery or live supplier inventory was added.

### 8. Inquiry lifecycle

The contact form persists destination/package, optional dates/travelers/budget, identity/contact details and a unique INQ reference. It validates choices against active stored catalogue records, rejects duplicate submissions, queues an acknowledgment, and creates staff notifications. Admins and consultants can search/filter, inspect preferences, update valid statuses and keep internal notes with stale-update protection. Signed-in customers can see only their inquiries and recorded replies. Guest replies still require staff email/phone follow-up; automated outgoing reply email is not implemented.

### 9. Dashboards

Customer pages show stored booking counts, upcoming confirmed trips, inquiry access, payment history and overall financial status. Admin metrics use actual records for customers/bookings, net revenue after processed refunds, receivables, inquiries and email queues. Staff payment views use the same booking summary. All role sidebars now show the authenticated account instead of demo email identities.

### 10. UI, motion and responsive work

Shared form controls, labels, focus styles and optional inquiry preferences are consistent with the established design. Legacy JSON business hours render as readable text. Existing subtle transitions and reduced-motion handling are preserved. The customer booking detail page now uses the responsive detail grid, fixing off-screen visa/payment controls at phone widths. Figma foundations and Button/Input/Card work from the previous phase remain available; its remaining screens and motion design are blocked by the Starter tool-call allowance. Canva's existing TravelGO logo design was located, but no replacement logo was created. Nothing was migrated or published through Sites.

### 11. Tests and browser verification

- Final business build: **77 tests passed**, zero failures/errors/skips, on 12 September 2026. Log: target/platform-final-build.log; packaged artifact: target/travelgo-0.0.1-SNAPSHOT.jar.
- Disposable MySQL: **27 tests passed**, zero failures/errors/skips. The server's datadir was verified as target/disposable-mysql before tests; port 33317. Log: target/platform-final-mysql.log. This suite overlaps the H2 suite; counts are executions, not 104 distinct tests.
- Coverage includes role/ownership checks, document protection, invalid inputs, workflow stages, amount/deadline rules, duplicates/concurrency, refunds, booking rollback, normalized accounts, queued-email rollback/escape/failure, catalogue installation and inquiry updates.
- Earlier browser pass: 24 public/admin page-and-width checks across 390, 768 and 1440 pixels, plus inquiry submission and admin status/notes persistence.
- Final browser pass: 15 customer, 15 consultant and 6 officer page-and-width checks. One customer booking-detail overflow was found and fixed, then visually rechecked at 390 pixels. All other checked layouts had no page overflow or broken loaded images. Officer checks used empty payment/visa states; populated financial behavior is covered by service/MVC tests.
- Consultant package-content submission was saved and its change verified on the public package page, using synthetic data only. Customer and both staff-role logins reached the correct workspaces.
- After final template fixes, **26 MVC connectivity tests passed** and the JAR was rebuilt successfully; log: target/platform-final-ui-build.log. These repeat the connectivity subset of the 77-test suite. JavaScript syntax and run.ps1 parsing checks also passed. Real SMTP delivery and historical live-schema migration are not verified by these tests.

### 12. Deployment setup and remaining scope

See PLATFORM_SETUP.md for SMTP variables, database credential variables, catalogue installation flag and verification commands. Live backup/configuration remains pending explicit authorization: automatic approval review rejected retrieving the old database credential from the source checkpoint after the current local credential failed. No recovery using that credential or live database mutation was performed. Once authorized, take and verify the backup, configure the intended credential privately, apply/inspect additive schema changes, install the three packages and verify the actual application before calling this deployed.

The business architecture document is an As-Is assessment, not a complete approved To-Be backlog. The website is **not fully complete against every derived story**. EPIC_USER_STORY_TRACEABILITY.md records the original story inventory; WEBSITE_UX_UPGRADE_REVIEW.md and this report document subsequent changes. Management approvals, supplier/departure inventory, general audit/reporting, complete booking conversations and email operations still need further development or explicit scope decisions. Timelines, PDF receipts, live payments and broader cancellation refunds remain deferred as agreed.

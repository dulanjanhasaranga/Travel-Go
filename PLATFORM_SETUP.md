# Platform upgrade setup and verification

For the complete MySQL Workbench setup, original package restoration, current run commands and restart-persistence verification, see MYSQL_AND_PACKAGES.md (13 September 2026). The older port-8082 test fixture resets its data; use the normal mysql profile for your working database.

## Architecture retained

Spring Boot / Thymeleaf / Spring Security / JPA / MySQL remain in place. All payments remain simulated. Every booking still requires visa approval before final package/hotel payment. Catalogue, visa, payment and customer ownership services retain their existing rules.

## Email configuration

Set deployment environment variables (never commit real values):

- TRAVELGO_MAIL_ENABLED=true to enable transactional delivery.
- SPRING_MAIL_HOST and SPRING_MAIL_PORT for the SMTP provider.
- SPRING_MAIL_USERNAME and SPRING_MAIL_PASSWORD for credentials.
- SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH=true when required.
- SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true when required by the provider.
- TRAVELGO_MAIL_FROM: an approved sender address.
- TRAVELGO_PUBLIC_BASE_URL: the customer-facing base URL, HTTPS for a public deployment.
- TRAVELGO_DB_PASSWORD: the existing database credential, or the legacy DB_PASSWORD fallback.

The default is delivery disabled. Booking/payment/inquiry messages are still durably queued. Password recovery continues using the existing configured JavaMailSender. No real message was sent during automated tests; the sender is mocked.

Queue states: QUEUED → SENDING → SENT or REVIEW_REQUIRED. A committed SENDING claim prevents concurrent workers from sending the same event. A crash or ambiguous SMTP failure leaves the event for reconciliation rather than automatically risking duplicate mail. A stable Message-ID aids tracing; SMTP cannot guarantee exactly-once receipt. Review provider logs before any manual resend. Email delivery does not hold a booking transaction open or undo a successful booking/payment.

Before enabling delivery, inspect the queued messages and use an email provider suitable for the intended recipients. Do not enable it against demonstration addresses.

## Additive database changes

Hibernate's existing ddl-auto=update convention is retained. Back up the real database first. No historical financial records are reconciled automatically.

- outbound_emails: event key (unique), recipient, subject, rendered HTML, state, attempts and timestamps.
- bookings: nullable unique request_key and request_fingerprint for retry detection.
- tour_packages: nullable itinerary, excluded_services and traveler_information text fields.
- contact_messages: nullable phone, inquiry_type, preferred_date, traveler_count, budget_range, destination_id, tour_package_id, internal_notes, unique submission_key and version (default 0). Existing OPEN/IN_PROGRESS/RESOLVED/CLOSED statuses remain valid; CONTACTED is added.
- Existing user email unique constraint is retained; new addresses are validated and normalized, and duplicate checks and login lookup are case-insensitive. Existing stored emails are not rewritten.

The MySQL schema reference is in database/platform-upgrade.sql. It is a reference for installations without automatic schema updates, not a script to rerun over an already-updated schema.

## Catalogue installation

Run the built app once with --travelgo.catalogue.install=true after backup and validation. The installer adds London, Singapore and Istanbul packages through the existing repositories. Existing matching package names are left unchanged. Restart without that flag afterward. It does not alter existing staff accounts, bookings or payment data.

Prices are authored USD catalogue values for the simulated application. New trips exclude international flights, hotel accommodation and separately quoted visa/documentation charges. Itineraries are editable in the travel consultant package workspace. Live departure inventory, supplier contracts and a multi-image gallery are not implemented.

## Verification commands

- mvn "-Dtest=ExperienceUpgradeTests,FeatureConnectivityAuditIT,InquiryUpgradeTests,TravelGoApplicationTests" test
- mvn "-Dtest=MySqlPlatformUpgradeIT" test (only with the disposable server at 127.0.0.1:33317 and the verified target/disposable-mysql datadir)
- mvn -DskipTests package after successful tests.

Never use the application database for tests. Test profiles exclude the existing demonstration account initializer and use H2 or the separate disposable MySQL instance.

## Limitations

Real email-provider delivery needs configuration and mailbox verification. Historical duplicate financial records remain unchanged and require staff reconciliation. Figma work from the previous phase remains blocked by its Starter tool-call allowance. This phase is local; nothing was migrated or published through Sites.

# TravelGO packages and MySQL — inspection, repair and run guide

Reviewed 13 September 2026. Project root: `C:\Users\User\Downloads\ISE\ISE` (the folder containing `pom.xml`).

## What happened to the original packages?

The implementation was not deleted. `PublicController` loads `TourPackageService` → `TourPackageRepository` → `tour_packages` and renders Thymeleaf templates. The old preview on **8082** was deliberately launched using `PlatformBrowserPreview`, the `mysql-test` profile, and **create-drop** against `travelgo_disposable_test` on MySQL port **33317**. That fixture installed only London, Singapore and Istanbul. It is unsuitable for keeping user-entered data across restarts.

The original four offers still existed in `DataInitializer`: European Classical Journey (Paris), Tokyo & Kyoto Cultural Tour, Dubai City & Desert Expedition, and Italian Renaissance & Rome. The initializer only seeded them when the entire package table was empty. Therefore, having some packages did not restore missing original ones. This is a preview/seed distinction; it is not evidence that the original records in your existing database were deleted. Access to that existing database remains unverified.

The default configuration also named `TravelGoDB`, while its active MySQL profile overrode that with `travelgo`. Both now consistently default to `travelgo`, with configurable URL, username and password. Choose the schema that actually holds your existing data; changing the URL is not a data migration.

## What was repaired, not rebuilt

- Retained the existing MVC pages, routes, entities, repositories, authentication, booking/visa/payment sequence, images and design.
- Extracted the existing four sample offers and their four hotel add-ons into `OriginalCatalogueService`. An explicit restoration flag adds missing named records without overwriting existing prices, content or inactive status. The three-package expansion remains separate. Together they provide seven offers across seven destinations and four existing categories. No replacement frontend dataset was introduced.
- Package create/edit now uses a validated `PackageRequest` and a transactional service. Invalid prices, durations, capacities and references cannot be saved through those forms.
- Package deletion now **deactivates** the record, hiding it from new customer requests while preserving all booking/inquiry foreign keys. Reactivate it through **Itinerary & availability**. The old `/delete` URL remains compatible.
- Package mutations and booking creation lock the package row. A package with any booking cannot change destination/duration, preventing those edits from invalidating linked hotel dates and itinerary assumptions. New prices apply to future bookings; existing booking price snapshots stay unchanged.
- Package content changes also go through the service. A standard JAR can run with a persistent demonstration profile using `ddl-auto=update`, independently of the disposable test launcher.

No new departure inventory was invented. `max_capacity` means the maximum travelers **per booking**, not remaining seats across departures. Dates are selected by the customer and validated against the three-day payment deadline. Supplier-confirmed dates/remaining slots would require a separate agreed inventory feature. Hotels are optional and selected from the package destination; the original multi-city marketing descriptions do not constitute multi-city hotel inventory.

## Data flow and actual tables

| Input or operation | Backend persistence | Relationships / qualification |
|---|---|---|
| Registration/profile/staff records | `users`, `roles`, `permissions`, `role_permissions` | Users reference a role; passwords are hashes. |
| Package management | `tour_packages`, `package_categories`, `destinations` | Each package references one destination and category. |
| Hotel management | `hotels` | Hotel references its destination. |
| Booking date, group and owner | `bookings` | References `users` and `tour_packages`; stores date, status and price snapshots. |
| Traveler details | `travelers` | References the booking; saved in the booking transaction. |
| Optional accommodation | `booking_hotels` | References booking and hotel; stores rooms, nights, dates and cost. |
| Visa application/status/charges | `visa_applications`, `visa_status_history` | Application references booking; history records transitions/actors. |
| Visa documents | `visa_documents` | Metadata/path is in MySQL; file bytes are in the configured upload directory. Back up both. |
| Simulated payment/refund | `payments`, `refunds` | Payment references booking; refund references payment. No real gateway integration. |
| Inquiry/reply/preferences | `contact_messages` | Optional owner/package/destination; status and internal notes persist. |
| Notifications/email | `notifications`, `outbound_emails` | Records persist; actual SMTP delivery remains disabled until configured. |
| Reviews/settings/recovery | `reviews`, `system_settings`, `password_reset_tokens` | Existing repository-backed flows retained. |

Source inspection found no LocalStorage/SessionStorage persistence for these business records. JavaScript generates traveler fields, estimates totals and shows the review dialog; the form then POSTs to Spring MVC. Authentication sessions, flash messages, rate-limit timestamps and unsubmitted form values are intentionally temporary. Several older administrative modules still use thin save services, rather than a uniform request-DTO layer; those were not rewritten in this focused repair.

## Routes involved

This is a server-rendered application, not React/Vite. These are MVC routes, not a new JSON API:

| Route | Purpose |
|---|---|
| `GET /`, `/packages`, `/packages/{id}` | Database-backed catalogue, filtering and details. |
| `GET /packages/{id}/book` | Customer booking form with active destination hotels. |
| `POST /customer/bookings/create` | Atomic booking/traveler/hotel submission. |
| `GET /customer/bookings`, `/{id}` | Read the customer's saved bookings. |
| `GET /staff/packages` | Consultant package management. |
| `POST /staff/packages/create`, `/{id}/edit` | Validated create/update. |
| `POST /staff/packages/{id}/delete` | Deactivate, preserve references. |
| `GET/POST /staff/packages/{id}/content` | Itinerary, exclusions, audience and active state. |
| `/staff/destinations`, `/staff/hotels`, `/staff/categories` | Existing related catalogue CRUD. |

The authorized catalogue role is **TRAVEL_CONSULTANT**. Administrators retain account/system administration; they do not automatically acquire consultant or visa-officer operations. Customer mutations retain ownership and CSRF checks.

## Set up the intended MySQL Server in Workbench

1. Start **MySQL Server**, independently of your IDE. On Windows, open Services (`services.msc`), locate the installed MySQL service (commonly `MySQL80`), and choose Start if stopped. Use the service name shown on your computer.
2. Open **MySQL Workbench** → the `+` beside MySQL Connections. Set a descriptive connection name, connection method **Standard TCP/IP**, hostname `127.0.0.1`, port `3306` (or your server's actual port), and your MySQL username. Click **Test Connection**, entering the server password when requested. Open the connection.
3. Before creating anything, inspect the Schemas panel and run `SHOW DATABASES;`. If your earlier records are under `TravelGoDB` or another schema, use that exact name in the JDBC URL. Back up an existing schema through **Server → Data Export**, include its data/structure, and retain the exported file privately before schema upgrades.
4. For a new installation, open and execute `database/setup-mysql.sql`. This creates `travelgo` only if absent; it never drops it. Refresh Schemas. The application user needs access to this schema and enough privileges for Hibernate's configured schema update. A schema-specific application account can be used instead of root.
5. No manual table creation is necessary with the existing `spring.jpa.hibernate.ddl-auto=update`. Starting the app creates missing mapped tables/columns and preserves rows. Do not set `create` or `create-drop` for your working database. The older `database/platform-upgrade.sql` is a reference for manual migration, **not** a script to rerun blindly after Hibernate update.
6. In the project root, edit the existing ignored `local.properties`; do not overwrite existing settings. If it is absent, copy `local.properties.example` to `local.properties`. Set:

```properties
TRAVELGO_DB_URL=jdbc:mysql://localhost:3306/travelgo?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true
TRAVELGO_DB_USERNAME=root
TRAVELGO_DB_PASSWORD=YOUR_ACTUAL_MYSQL_SERVER_PASSWORD
```

The values above describe a local development connection. Substitute your actual schema/port/username/password. Do not paste the password into chat, HTML or JavaScript. The same `TRAVELGO_DB_*` names may be environment variables; standard Spring `SPRING_DATASOURCE_*` overrides also work. The driver is `com.mysql.cj.jdbc.Driver`; the MySQL connector is already a Maven dependency.

## Exact run commands — any IDE or terminal

Install JDK 17 or newer. Run from the directory containing `pom.xml` so the relative `local.properties` and upload paths resolve consistently.

```powershell
Set-Location 'C:\Users\User\Downloads\ISE\ISE'
java -version
.\mvnw.cmd spring-boot:run
```

Or, if Maven is installed:

```powershell
mvn spring-boot:run
```

Open `http://localhost:8080/`. **There is no separate frontend command**: Spring Boot serves Thymeleaf, CSS, JavaScript and images from the same process. In IntelliJ, Eclipse, VS Code or another IDE, import `pom.xml`, use an installed JDK, choose `com.travelgo.TravelGoApplication`, set this working directory and the `mysql` profile. The database connection depends on MySQL Server and configuration, not the IDE. `run.ps1` is an optional wrapper; it no longer contains an IDE-specific Java path.

For a packaged run, first build (tests remain isolated):

```powershell
.\mvnw.cmd "-Dtest=ExperienceUpgradeTests,FeatureConnectivityAuditIT,InquiryUpgradeTests,TravelGoApplicationTests,PackagePersistenceIT" package
java -jar target/travelgo-0.0.1-SNAPSHOT.jar --spring.profiles.active=mysql
```

### Restore original sample offers and add the three newer offers

After backup and confirmation that the JDBC URL points to the intended schema, start once with:

```powershell
java -jar target/travelgo-0.0.1-SNAPSHOT.jar --spring.profiles.active=mysql --travelgo.catalogue.restore-original=true --travelgo.catalogue.install=true
```

Stop with Ctrl+C, then restart without the two catalogue flags. Matching existing package names are preserved, including inactive packages. Do not repeatedly seed to undo deliberate staff changes. The existing initializer still creates its demonstration accounts on a fresh database; existing matching accounts are not reset. These defaults are for local demonstrations, not public production use.

## Verify actual saved data in Workbench

Choose the same connection **and schema** as the application, then open `database/verify-persistence.sql`. Its queries use actual names (`tour_packages`, not `packages`) and omit password hashes/document paths. On your working database:

1. Log in as a customer, browse packages, open details, choose a date at least four days ahead, enter every traveler, optionally choose a hotel, and submit the review dialog.
2. Run the booking, traveler and hotel queries. Confirm the new booking ID/date/count and matching foreign keys. Hotel checkout is check-in plus `max(1, durationDays - 1)` nights; rooms are rounded up at two travelers per room.
3. Log in as a consultant. Add a clearly named demonstration package, edit its price/content, and refresh the public page. Query `tour_packages` after each change. Deactivate it and confirm `is_active=0`; it remains in SQL and disappears from new public booking choices.
4. Stop **only the application**, restart it normally, and rerun the same queries. The IDs and submitted values should be unchanged. Session login may be required again; that does not mean booking data was removed.

```sql
USE travelgo;
SELECT id, name, base_price, is_active FROM tour_packages ORDER BY id;
SELECT id, user_id, tour_package_id, travel_date, number_of_travelers, booking_status
FROM bookings ORDER BY id DESC;
SELECT id, booking_id, full_name FROM travelers ORDER BY id DESC;
SELECT id, name, email FROM users ORDER BY id;
```

## Verification environment delivered with this repair

The persistent demonstration runs at `http://127.0.0.1:8083/`, using the ordinary built application, profile `persistence-demo`, MySQL port **33317**, schema **travelgo_persistence_demo**. It contains sample accounts/offers and the synthetic verification booking. It does not use your unverified existing schema on port 3306. Workbench can inspect it with hostname `127.0.0.1`, port `33317`, username `root`, no password; the verification server is bound to loopback only.

The separate MySQL process must remain running. After a computer reboot, start the existing verification server from PowerShell as follows (only if port 33317 is not already in use):

```powershell
Set-Location 'C:\Users\User\Downloads\ISE\ISE'
Start-Process -FilePath 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqld.exe' -ArgumentList '--no-defaults','--datadir=C:/Users/User/Downloads/ISE/ISE/target/disposable-mysql','--port=33317','--bind-address=127.0.0.1','--mysqlx=0' -WindowStyle Hidden
java -jar target/travelgo-0.0.1-SNAPSHOT.jar --spring.profiles.active=persistence-demo --spring.config.import=
```

This verification datadir is under `target`; **Maven clean can remove it**, so it is not permanent business storage. Normal operation should use your installed MySQL service/schema on port 3306 and the `mysql` profile. Do not initialize, delete, or point test profiles at that working datadir.

Demo customer: `customer@travelgo.com` / `Customer@123`. Demo consultant: `consultant@travelgo.com` / `Consultant@123`. These are the original initializer's local demonstration accounts, separate from the earlier 8082 fixture accounts. SMTP is disabled and payments remain simulated.

## Changed files and verification evidence

- Configuration: `application.properties`, `application-mysql.properties`, new `application-persistence-demo.properties`, new `local.properties.example`.
- Backend: new `dto/PackageRequest.java`, `TourPackageService`, `TourPackageRepository`, `BookingRepository`, `BookingService`, `AdminTourPackageController`, `PackageContentController`.
- Seed recovery: `DataInitializer`, `CatalogueExpansionRunner`, new `OriginalCatalogueService`.
- UI: `templates/staff/packages.html` (deactivation wording and numeric limits).
- SQL: new `database/setup-mysql.sql`, `database/verify-persistence.sql`.
- Tests: new `PackagePersistenceIT`, `MySqlPackagePersistenceIT`, updated `FeatureConnectivityAuditIT` deactivation assertion.
- Source checkpoint: `checkpoints/package-persistence-20260913-094800`; this is not a database backup.

98 H2/regression test executions passed; 48 isolated MySQL test executions passed. Suites overlap and should not be described as 146 distinct tests. Logs: `target/package-persistence-h2.log`, `target/package-persistence-mysql.log`. Browser verification submitted Paris booking **#1**, one synthetic traveler, departure **2026-12-15**, and a six-night hotel stay ending **2026-12-21**. Stored package cost is **USD 1,299**, hotel cost **USD 1,080**, combined **USD 2,379**. It remains PENDING and unpaid, as expected.

The application was stopped and restarted without catalogue-install flags. The complete read-only SQL output was byte-for-byte identical before and after restart: `target/persistence-before-restart.txt` and `target/persistence-after-restart.txt`. Seven packages, the booking, traveler, hotel allocation and queued email all remained. Logging back in and opening `/customer/bookings/1` confirmed those same values on the website. This verifies an application restart, not restoration from backup or a MySQL Server crash.

No reconstruction of the package feature was needed. No existing live database credentials were recovered or live schema changed in this repair. That work remains pending the specific authorization requested after automatic approval review rejected checkpoint-credential recovery. Workbench itself was not operated; the supplied SQL was executed against the same MySQL Server endpoint that Workbench can connect to.

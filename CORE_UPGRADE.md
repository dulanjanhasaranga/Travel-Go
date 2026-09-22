# TravelGo core reliability upgrade

## What changed

- Travel consultants manage catalogue, bookings, and inquiries. Visa officers manage visas and payments. Administrative routes retain their existing admin restriction.
- Visa files are no longer publicly served. `GET /visa-documents/{id}/download` returns an attachment only to the booking owner or a visa officer, with a uniform 404 otherwise. Existing upload records continue to resolve within `uploads/visa-documents`; new files receive generated storage names. The directory can be configured with `travelgo.upload-directory`.
- Uploads allow PDF, JPEG, and PNG up to 10 MB. PDF signatures/end markers and image content/format/dimensions are checked. This is format validation, not malware scanning. Failed database transactions remove new files.
- Booking creation and traveler/hotel updates are transactional. Travel dates must leave the three-day payment deadline in the future. Traveler details must match the declared party size. Hotel checkout now matches the billed nights.
- Booking edits carry an `expectedUpdatedAt` form field to reject stale edits. Changing party size requires complete replacement traveler details. Customer modifications/cancellation stop after document review or any recorded payment.
- Visa creation, verification, charges, processing, and decisions enforce their allowed order and record the actor in the existing visa history. At least one document must exist before verification. Completeness remains an officer decision.
- Upfront payment uses the deadline issued with the charges. After expiry, officers can explicitly reissue unchanged charges through `POST /staff/visas/{id}/reissue`. Checkout never extends the deadline.
- Successful upfront payment starts processing. Final payment requires an approved visa and includes the optional hotel cost. Both customer and manual staff payments calculate their authoritative amounts from stored records and reject mismatched quotes.
- All mutable workflow operations acquire the booking row lock first and use READ COMMITTED transactions. Successful duplicate payment submissions return the existing payment. Repeated rejection does not create duplicate refunds, histories, or notifications.
- Rejection cancels the booking and refunds only the documentation fee against the successful upfront payment. Original payment amounts remain intact; partial refunds remain separate records. Existing revenue totals subtract processed refunds. Arbitrary refund-status editing is disabled.

## Verification

Verified locally with Maven, Java 26.0.1 (Java 17 compilation target), H2 2.4.240, and a separate MySQL 8.0.43 process:

- `mvn verify`: application compilation, 18 passing tests, and runnable JAR packaging.
- `mvn -Dtest=MySqlWorkflowIT test`: 17 passing workflow tests against the disposable MySQL database, including concurrent payment and refund requests.
- Tests cover rollback, traveler replacement, stale edits, invalid dates/counts, ownership, staff roles, CSRF, restricted downloads, upload format/size, rollback cleanup, visa transitions, deadline boundaries, quote tampering, staff-payment bypasses, refund amounts, and affected Thymeleaf page rendering.
- H2 tests use the `test` profile; the opt-in MySQL suite uses `mysql-test`. Both disable the application data initializer and local configuration imports. Test accounts and files are synthetic and confined to the isolated databases and `target/test-uploads`.
- The existing application database and original uploads were not changed. No deployed site or normal application server was started. HTML rendering was tested through MockMvc; no interactive browser testing was performed.

Logs are in `target/core-verification.log`, `target/mysql-verification.log`, and `target/surefire-reports`.

## Running and repeating checks

From the project directory:

```powershell
mvn verify
mvn spring-boot:run
```

The second command starts the normal application using its existing local database configuration. Maven and a working JDK must be available. The old `run.ps1` contains another machine's JDK path; use the Maven command above or update that path for your machine.

MySQL verification is opt-in. Its test profile is fixed to `127.0.0.1:33317/travelgo_disposable_test` with user `root`, blank password, and schema `create-drop`. Use only a separate loopback-bound disposable MySQL server for this profile. Never point it at the application database.

The disposable instance used for verification has its own files under `target/disposable-mysql`. It was stopped after verification. To repeat the suite with the installed MySQL binaries, start that instance without loading normal MySQL defaults:

```powershell
$mysqlTestBin = 'C:/Program Files/MySQL/MySQL Server 8.0/bin'
$mysqlTestData = Join-Path (Get-Location) 'target/disposable-mysql'
Start-Process -FilePath "$mysqlTestBin/mysqld.exe" -WindowStyle Hidden -ArgumentList @(
    '--no-defaults', ('--datadir="' + $mysqlTestData + '"'), '--port=33317',
    '--bind-address=127.0.0.1', '--mysqlx=OFF'
)
# After the server reports ready, confirm @@datadir refers to target/disposable-mysql.
& "$mysqlTestBin/mysql.exe" --no-defaults --protocol=TCP --host=127.0.0.1 --port=33317 --user=root --execute='SELECT @@datadir; CREATE DATABASE IF NOT EXISTS travelgo_disposable_test;'
mvn -Dtest=MySqlWorkflowIT test
& "$mysqlTestBin/mysqladmin.exe" --no-defaults --protocol=TCP --host=127.0.0.1 --port=33317 --user=root shutdown
```

On a fresh checkout, initialize the empty test data directory first with `mysqld --no-defaults --initialize-insecure --datadir=<absolute target/disposable-mysql path>`. Do not initialize over an existing data directory.

## Compatibility and boundaries

No database columns were added and no existing financial records were rewritten. The traveler relationship now removes replaced children correctly. Existing inconsistent financial records are rejected for staff reconciliation rather than repaired automatically.

Payments remain simulated. Required-document templates, later customer changes/refund policies, PDF receipts, journey timelines, live gateways, new reports, and deployment remain outside this upgrade.

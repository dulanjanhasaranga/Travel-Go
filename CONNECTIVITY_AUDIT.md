# TravelGo frontend, backend, and SQL connectivity audit

Audit date: 10 September 2026.

## Result

**The main system is connected, but not every feature is complete or functioning correctly.**

The live application uses MySQL. Thymeleaf renders the frontend, forms call Spring MVC controllers, services apply workflow rules, and JPA repositories read/write SQL records. The complete booking → document upload → verification → upfront payment → visa approval → final payment → booking confirmation journey passed through HTTP requests and SQL assertions on disposable MySQL.

### Evidence and boundaries

- Nine live public/authentication URLs returned HTTP 200: `/`, `/packages`, `/destinations`, `/visa-info`, `/about`, `/contact`, `/faq`, `/auth/login`, `/auth/register`.
- All 36 controller page routes were exercised using appropriate test accounts: **35 rendered; the role-permissions detail page failed**. Protected binary document downloads were tested separately.
- **336 rendered form instances** with static action URLs were inspected. The checked actions matched backend routes and required named request parameters. This is a count of forms repeated across fixture records, not 336 separate features.
- The expanded MySQL audit ran **26 test methods: 25 passed and 1 failed**. Four passing methods intentionally reproduce defects listed below; passing characterization tests do not mean those features are correct.
- Registration/login/logout, staff/user edits, settings, maintenance mode, catalogue create/edit/delete/search, and the complete customer workflow were tested with HTTP submissions and persisted record assertions.
- The earlier workflow checks cover transaction rollback, ownership, CSRF, payment deadlines/amounts, document validation, traveler replacement, and concurrent payment/rejection handling.
- Only read-only public requests were made to the running app. All created users, bookings, payments, documents, and staff changes were confined to test databases and `target/test-uploads`. No production application code was changed by this audit.
- Scope limits: rendered HTML and HTTP/SQL integration were tested. Browser JavaScript interactions, layout, mobile behavior, external image/CDN availability, and every possible input/state combination were not exhaustively tested. Dynamic modal actions assigned only by JavaScript are not covered by the static-action form scan.

## Confirmed issues

| Priority | Feature | Evidence | Effect and next change |
|---|---|---|---|
| High | Role-permissions page | `/admin/roles/{id}` throws a Thymeleaf error at line 62: `Iteration variable cannot be null`. Reproduced on H2 and MySQL. | The permission-editing screen does not render. Rename the loop variable `mod` to an unambiguous identifier such as `permissionModule`, including its references. |
| High | Granular role permissions | Posting permission IDs updates role-permission SQL records, but authenticated authorities contain only `ROLE_TRAVEL_CONSULTANT`. Removing grants does not remove role-based authorization. | Permission checkboxes do not control access. Map permission records into authorities and enforce them consistently, or clearly present permissions as informational until implemented. |
| High | Disabling an existing staff session | A consultant logged in, an admin disabled the account, and the same session still successfully created a catalogue category. The disabled flag and new category were checked in SQL. Fresh login is correctly blocked. | Account deactivation is not connected to revocation of existing catalogue access. Expire sessions on deactivation or recheck current active status before protected requests. Some newer workflow services already recheck active users, so behavior is inconsistent across modules. |
| Medium | Customer inquiry replies | Contact submission persisted. A staff reply persisted and marked the inquiry resolved, but created no customer notification and did not appear on the customer's notification page. | “Reply sent successfully” currently means saved internally. Add an authenticated customer reply/inbox view and notification; implement email separately if required. |
| Medium | Review validation and display | An owned pending booking accepted rating `99`; SQL stored it. The review text was not rendered on the package list or booking detail, and the inspected controllers do not load reviews into a view. | Enforce the allowed booking state and rating range 1–5 in the backend, then read saved reviews into the intended customer/public view. |
| Low | Remember me | The form submits `remember-me`, but the security configuration does not enable remember-me authentication. A test login with the checkbox selected issued no remember-me cookie. | Enable the feature or remove the checkbox. Normal session login/logout works. |

Source locations:

- [Role page template](C:/Users/User/Downloads/ISE/ISE/src/main/resources/templates/admin/role-detail.html:62)
- [Authentication authority mapping](C:/Users/User/Downloads/ISE/ISE/src/main/java/com/travelgo/service/CustomUserDetailsService.java:32)
- [Account status updates](C:/Users/User/Downloads/ISE/ISE/src/main/java/com/travelgo/service/UserService.java:132)
- [Inquiry reply persistence](C:/Users/User/Downloads/ISE/ISE/src/main/java/com/travelgo/service/ContactMessageService.java:35)
- [Review submission handler](C:/Users/User/Downloads/ISE/ISE/src/main/java/com/travelgo/controller/CustomerReviewController.java:41)
- [Login controls](C:/Users/User/Downloads/ISE/ISE/src/main/resources/templates/auth/login.html:152)

## Connected features and remaining boundaries

| Area | Assessment |
|---|---|
| Registration and regular email/password login | Connected to SQL, password hashing, sessions, logout, and fresh-login account activation checks. |
| Admin user/staff management | Create/update/assign-role/toggle-status forms persist. Existing-session revocation gap noted above. |
| Destinations, categories, hotels, packages | Create/edit/delete and public search connections passed. Public catalogue queries do not filter inactive records consistently; activation is shown in staff UI but no activation-toggle endpoint is exposed for these records. This latter observation is from source inspection. |
| Booking, traveler details, hotel add-on | Connected to SQL with transactional services. Checked creation, rollback, replacement, and stale/locked modifications. |
| Visa documents and decisions | Upload, storage, restricted download, verification, charges, approval/rejection, and history are connected. Required-document completeness remains a manual officer decision. |
| Simulated payments/refunds | Connected to booking and visa states and SQL records; tested amounts, deadlines, concurrency, and documentation-only refunds. External payment providers are intentionally not integrated. |
| Customer notifications | Visa workflow notifications are created and displayed. Inquiry replies are not connected; marking notifications read has no controller/service operation. |
| Company/contact details, business hours, maintenance | Settings submissions persist and affect public pages/access. |
| Google/Apple sign-in and password recovery | Demo-only controls. Social login functions submit the demo customer form; password recovery displays an alert. There is no real OAuth or reset workflow. |
| PDF receipts, external airline/embassy integrations | Not implemented in this phase; previously deferred. Stored flight descriptions and officer-entered visa decisions should not be mistaken for external integrations. |

## Reproducing the audit

Normal regression tests:

```powershell
mvn test
```

Opt-in audit against isolated H2:

```powershell
mvn -Dtest=FeatureConnectivityAuditIT test
```

Opt-in audit against the separate disposable MySQL instance described in `CORE_UPGRADE.md`:

```powershell
mvn -Dtest=MySqlFeatureAuditIT test
```

The audit intentionally returns a nonzero exit status while the role-permissions template remains broken. Classes are named `*IT` so this newly discovered failure is opt-in and does not silently alter the normal regression suite. `reproduce...` test methods are diagnostic characterizations of current defects; convert them to expected-behavior regression tests when implementing fixes.

Artifacts:

- [Connectivity audit tests](C:/Users/User/Downloads/ISE/ISE/src/test/java/com/travelgo/FeatureConnectivityAuditIT.java)
- [MySQL audit entry point](C:/Users/User/Downloads/ISE/ISE/src/test/java/com/travelgo/MySqlFeatureAuditIT.java)
- [Rendered route/form results](C:/Users/User/Downloads/ISE/ISE/target/feature-route-audit.txt)
- [MySQL audit log](C:/Users/User/Downloads/ISE/ISE/target/mysql-feature-audit.log)

Recommended order: repair the permission screen and permission enforcement, revoke disabled sessions, connect inquiry replies to customers, then complete review validation/display and the remaining authentication controls.

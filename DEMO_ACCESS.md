# TravelGO quick demo access

Quick Demo Access uses four dedicated accounts in the existing `users` table. It requires both the `demo` profile and `travelgo.demo.enabled=true`. With either switch absent, the login page hides the shortcuts and the demo endpoint returns 404. An active `prod` or `production` profile disables them even if both switches are supplied.

From the project directory, after building the application, start the local MySQL demonstration with:

```powershell
java -jar target/travelgo-0.0.1-SNAPSHOT.jar --spring.profiles.active=mysql,demo --travelgo.demo.enabled=true
```

The existing MySQL configuration still supplies the database connection. The demo profile does not replace the database or erase records. Forms continue to save real application records there. The profile disables outbound email and payment processing remains simulated.

Open `http://localhost:8080/auth/login` and choose a workspace:

| Button | Dedicated account | Destination |
| --- | --- | --- |
| Login as Admin | `demo.admin@travelgo.example.invalid` | `/admin/dashboard` |
| Login as Travel Consultant | `demo.catalogue@travelgo.example.invalid` | `/staff/packages` |
| Login as Visa Officer | `demo.visa@travelgo.example.invalid` | `/staff/visas` |
| Login as Customer | `demo.customer@travelgo.example.invalid` | `/customer/dashboard` |

If the database uses the existing `PACKAGE_MANAGER` catalogue role, the account reuses that role and the button says **Login as Package Manager**. Authentication maps this legacy name to the existing travel-consultant permissions without renaming stored roles or creating a duplicate catalogue role.

Each dedicated account has `is_demo_account=1` and an independently generated BCrypt password. There is no published password to enter. The POST `/auth/demo-login` endpoint accepts only the four fixed account keys, requires CSRF protection, rotates the session and CSRF token, and stores the authenticated Spring Security session. Its redirects are fixed in the backend. It does not accept arbitrary email addresses, role names, or redirect URLs.

Provisioning happens once at startup within a transaction and is safe to repeat. Existing valid dedicated accounts keep their IDs and passwords. An existing ordinary account using a reserved demo email is never adopted or promoted. An inactive, reassigned, or otherwise changed dedicated account causes a clear account-review error; startup does not silently repair it. The public login endpoint never creates accounts.

To disable shortcuts, stop the application and restart normally:

```powershell
java -jar target/travelgo-0.0.1-SNAPSHOT.jar --spring.profiles.active=mysql --travelgo.demo.enabled=false
```

The dedicated accounts and their demonstration records remain in MySQL, but those accounts cannot authenticate while demo mode is disabled. Existing demo sessions are also rejected. Ordinary sign-in continues to use existing accounts. New installations no longer create predictable default accounts: configure a first administrator explicitly with `TRAVELGO_BOOTSTRAP_ADMIN_EMAIL` and `TRAVELGO_BOOTSTRAP_ADMIN_PASSWORD` only when the database contains no administrator.

Inspect the dedicated accounts without selecting passwords:

```sql
SELECT u.id, u.name, u.email, r.role_name, u.is_active, u.is_demo_account
FROM users u JOIN roles r ON r.id = u.role_id
WHERE u.is_demo_account = 1
ORDER BY u.id;
```

The focused automated suite uses explicitly isolated H2 databases and excludes application startup seeders:

```powershell
mvn "-Dtest=DemoModeTests,DemoLoginTests,DemoLoginDisabledTests,DemoLoginDefaultTests,DemoLoginProductionTests" test
```

These tests cover the switches, production override, CSRF, session replacement, each role's actual destination, logout, legacy catalogue role compatibility, account collisions, non-adoption, inactive/reassigned accounts, and disabled demo sessions/password login. Browser verification is still necessary for the login buttons and responsive layout; Maven does not execute browser JavaScript.

# TravelGO free cloud demonstration release

This is a public academic demonstration for fictional records. It is intentionally separate from the `prod` profile and does not represent an always-on commercial service. Free Render services can spin down after inactivity, can restart, have no persistent disk, and cannot use SMTP; the release therefore stores uploaded visa documents privately in Supabase and sends account mail through Brevo HTTPS. See Render's [free-service limits](https://render.com/docs/free) and [Docker deployment guide](https://render.com/docs/docker).

## Included release safeguards

- `Dockerfile.cloud-evaluation` starts TravelGO only on port **8081** with the `cloud-evaluation` profile.
- `CloudEvaluationReleaseGate` rejects unsafe settings before it opens Aiven, Supabase, Brevo, or Cloudmersive connections.
- Aiven TLS uses a short-lived **driver-only** PKCS#12 truststore in `/tmp`; the standard Java HTTPS trust store remains unchanged for the other providers.
- Supabase documents stay in the private `travelgo-documents` bucket. Public buckets and public document URLs are rejected.
- Cloudmersive scans only files up to 3 MB. Failed or quota-limited scans reject the upload.
- Payments and Google sign-in remain disabled. The normal deployment uses a restricted `travelgo_demo_app` MySQL user.
- `render.yaml` declares the normal web service and leaves provider credentials as Render dashboard secrets. It uses Render-generated 256-bit Base64 values for the shared authentication and staff-MFA keys.

## Before deployment

1. Use only fictional names, documents, bookings, payments, and email addresses in this demonstration.
2. In Aiven, download the service CA certificate. Keep the file private.
3. In Supabase, keep the `travelgo-documents` bucket **private** and retain the server-only secret key privately.
4. In Cloudmersive, create one replacement free-tier key if the earlier key was exposed. Do not share it.
5. In Brevo, create a transactional **API key** and use a verified sender address. This is separate from the SMTP password.
6. Run this locally to generate the certificate value and private random values without sending them anywhere:

   ```powershell
   Set-Location 'C:\Users\User\Downloads\ISE\ISE'
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\new-cloud-evaluation-secrets.ps1 -AivenCaCertificatePath 'C:\path\to\aiven-ca.pem'
   ```

   Copy each result directly into the appropriate provider or Render secret field. Do not save the output in the repository or send it in chat.

## One-time empty schema setup

The normal web service refuses to create tables. For a brand-new, empty Aiven `defaultdb` schema, create a temporary Render **Web Service** from the private repository with these settings:

| Field | Value |
|---|---|
| Name | `travelgo-schema-init` |
| Runtime | Docker |
| Dockerfile path | `Dockerfile.cloud-evaluation-init` |
| Plan | Free |
| Auto-Deploy | Off |
| Health check path | Leave empty |

Add these environment variables in Render. Values marked private must be entered in the dashboard only.

| Variable | Value |
|---|---|
| `TRAVELGO_DB_URL` | `jdbc:mysql://AIVEN_HOST:AIVEN_PORT/defaultdb?sslMode=VERIFY_IDENTITY&serverTimezone=UTC` |
| `TRAVELGO_DB_USERNAME` | Your temporary Aiven administration username |
| `TRAVELGO_DB_PASSWORD` | private Aiven administration password |
| `TRAVELGO_AIVEN_CA_CERT_PEM_BASE64` | private local-script output |
| `TRAVELGO_DB_TRUSTSTORE_PASSWORD` | private local-script output |
| `TRAVELGO_CLOUD_EVALUATION_INIT_CONFIRM` | `INITIALIZE_EMPTY_AIVEN_SCHEMA` |
| `TRAVELGO_BOOTSTRAP_ADMIN_EMAIL` | your administrator email address |
| `TRAVELGO_BOOTSTRAP_ADMIN_PASSWORD` | private local-script output |
| `TRAVELGO_BOOTSTRAP_ADMIN_NAME` | administrator display name |

The container checks that `defaultdb` has no tables or views before Hibernate creates the schema. A dedicated non-web context then creates the mapped tables, baseline roles/settings, first administrator, and durable authentication guard, then exits. Read the deployment log for `Hosted schema initialization completed successfully`; delete this temporary service immediately after that successful message. Render may report a failed or stopped deployment because this one-time process exits without opening a web port. The success log, rather than the service status, confirms completion. If that message is absent, inspect the error before taking another action; do not rerun the initializer against a nonempty schema.

Then, using the temporary administration connection, run [cloud-evaluation-create-app-user.sql](../database/cloud-evaluation-create-app-user.sql) after replacing its password locally. Enter that same password in Render later as `TRAVELGO_DB_PASSWORD` and the username `travelgo_demo_app`.

## Normal Render service

Create the normal service from the repository using `render.yaml` as a Blueprint, or use the dashboard with these settings:

| Field | Value |
|---|---|
| Name | `travelgo-demo` |
| Runtime | Docker |
| Dockerfile path | `Dockerfile.cloud-evaluation` |
| Plan | Free |
| Health check path | `/healthz` |

For a manually created service, use the variables in `render.yaml`. Render can generate `TRAVELGO_DB_TRUSTSTORE_PASSWORD`, `TRAVELGO_AUTH_RATE_HMAC_KEY`, and `TRAVELGO_STAFF_MFA_ENCRYPTION_KEY`; do not regenerate those values after the first successful deployment. Set `TRAVELGO_PUBLIC_BASE_URL` to `https://travelgo-demo.onrender.com` if that service name is available, and use the same exact URL in Brevo when configuring account-email links.

The normal service starts only with the restricted `travelgo_demo_app` account, validates the complete schema, and will not start if any provider, private storage, TLS, mail, or security setting is incomplete. A successful `/healthz` response confirms both database and private document-storage readiness.

## Validation after deployment

1. Open the Render URL in a private browser window.
2. Register a fictional customer account, request an email verification, and use the received link.
3. Confirm login works only after the verification step.
4. Upload a harmless fictional document below 3 MB and confirm it appears only to the authorized visa workflow.
5. Sign in as the initial administrator, enrol staff MFA before creating staff accounts, and add fictional content and policies.
6. Confirm `/healthz` is healthy in Render and inspect provider dashboards for delivery/scanning failures without exposing keys or recipients.

Do not put real passport scans, payment information, customer records, or account passwords into this free demonstration.

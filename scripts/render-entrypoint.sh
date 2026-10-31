#!/usr/bin/env bash
set -euo pipefail

readonly trust_directory=/tmp/travelgo-aiven
readonly truststore="$trust_directory/truststore.p12"

fail() {
  printf '%s\n' 'Hosted demo startup could not prepare the database TLS truststore. Check the documented Render secret settings.' >&2
  exit 64
}

# This certificate is a short-lived build input, not an application property or log value.
if [[ -z "${TRAVELGO_AIVEN_CA_CERT_PEM_BASE64:-}" || -z "${TRAVELGO_DB_TRUSTSTORE_PASSWORD:-}" ]]; then
  fail
fi
if (( ${#TRAVELGO_AIVEN_CA_CERT_PEM_BASE64} > 65536 )); then
  fail
fi

umask 077
mkdir -p "$trust_directory"
chmod 0700 "$trust_directory"
certificate="$(mktemp "$trust_directory/ca.XXXXXX.pem")"
trap 'rm -f "$certificate"' EXIT

if ! printf '%s' "$TRAVELGO_AIVEN_CA_CERT_PEM_BASE64" | base64 --decode > "$certificate" 2>/dev/null; then
  fail
fi
certificate_bytes="$(wc -c < "$certificate")"
if (( certificate_bytes < 128 || certificate_bytes > 65536 )); then
  fail
fi
rm -f "$truststore"
if ! keytool -importcert -noprompt -alias aiven-ca -file "$certificate" \
    -keystore "$truststore" -storetype PKCS12 \
    -storepass:env TRAVELGO_DB_TRUSTSTORE_PASSWORD 2>/dev/null; then
  fail
fi
chmod 0600 "$truststore"
unset TRAVELGO_AIVEN_CA_CERT_PEM_BASE64

if [[ "${TRAVELGO_CLOUD_EVALUATION_INITIALIZER:-}" == "1" ]]; then
  exec java -Duser.timezone=Asia/Colombo -jar /app/travelgo.jar \
    --spring.profiles.active=cloud-evaluation-init \
    --spring.config.import= \
    --spring.main.web-application-type=none \
    --spring.jpa.hibernate.ddl-auto=create \
    --spring.sql.init.mode=never \
    --travelgo.data-initializer.enabled=true \
    --travelgo.demo.enabled=false \
    --travelgo.catalogue.install=false \
    --travelgo.catalogue.restore-original=false \
    --travelgo.mail.enabled=false \
    --travelgo.mail.dispatch-enabled=false \
    --travelgo.payments.simulated=true \
    --travelgo.stripe.enabled=false \
    --travelgo.payhere.enabled=false \
    --travelgo.auth.google.enabled=false \
    --travelgo.cloud-evaluation.release-acceptance=SYNTHETIC_DEMO_ONLY \
    --travelgo.cloud-evaluation.schema-init=CREATE_EMPTY_SCHEMA \
    "$@"
fi

exec java -Duser.timezone=Asia/Colombo -jar /app/travelgo.jar \
  --spring.profiles.active=cloud-evaluation \
  --spring.config.import= \
  --server.address=0.0.0.0 \
  --server.port=8081 \
  --spring.jpa.hibernate.ddl-auto=validate \
  --spring.sql.init.mode=never \
  --travelgo.data-initializer.enabled=false \
  --travelgo.demo.enabled=false \
  --travelgo.catalogue.install=false \
  --travelgo.catalogue.restore-original=false \
  --travelgo.bootstrap.admin.email= \
  --travelgo.bootstrap.admin.password= \
  --travelgo.bootstrap.admin.name= \
  --travelgo.payments.simulated=true \
  --travelgo.stripe.enabled=false \
  --travelgo.payhere.enabled=false \
  --travelgo.auth.google.enabled=false \
  --travelgo.bookings.require-supplier-confirmation=true \
  --travelgo.bookings.require-published-policy=true \
  --travelgo.cloud-evaluation.release-acceptance=SYNTHETIC_DEMO_ONLY \
  "$@"

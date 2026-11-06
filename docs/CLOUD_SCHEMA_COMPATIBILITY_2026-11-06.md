# Existing cloud schema compatibility

The owner requires the existing Aiven schema to remain unchanged. The earlier hosted release expected 45 tables, but the cloud schema has 44 and lacks `tour_package_transports`.

No current controller, DTO, service, JavaScript or template uses the `TourPackage.transports` association. Standalone transport management operates on `Transport` independently; package forms do not offer package-to-transport association editing. The missing relationship is therefore an unused persistence declaration, not a currently implemented website workflow.

Only the `cloud-evaluation` profile loads `META-INF/cloud-evaluation-orm.xml`. It marks that one field transient while preserving the annotated entity, every other association and all standalone transport behavior. Other profiles keep their original 45-table mappings. The accepted local release and customer resources are unchanged.

The hosted release gate requires this exact mapping resource and still requires `ddl-auto=validate`, SQL initialization disabled, the restricted database account, verified TLS, mandatory staff MFA, CSP, email verification and private scanned documents. No DDL, automatic schema update or initializer is enabled. This is an application compatibility release; it does not execute the declined migration.

Isolated tests establish that:

- The only mapping difference is the unused link table: 44 cloud tables versus 45 original tables.
- The 44-table schema passes actual Hibernate validation; removing another required table still fails.
- A source guard detects future application callers of the omitted association.
- The inherited booking, visa, simulated payment, cancellation, approval and administrator rendering fixtures run against the compatible mapping.

If package transport linking is implemented in future, it needs an explicit persistence design and an owner-approved migration before this override is removed. Do not silently use the transient field to store links.

Release branch: `hosting-compat-20261106`. Provider startup and live HTTPS health must be verified separately from isolated tests. The original `hosting-20261031` branch is retained as a rollback source but cannot start against the unchanged schema.

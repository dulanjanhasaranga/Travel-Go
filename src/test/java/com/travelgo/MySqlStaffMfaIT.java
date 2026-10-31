package com.travelgo;

/** Repeats durable MFA/replay/cooldown/recovery locking only on the guarded synthetic MySQL endpoint. */
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.test.context.ActiveProfiles(value = "mysql-test", inheritProfiles = false)
class MySqlStaffMfaIT extends StaffMfaIntegrationTests { }

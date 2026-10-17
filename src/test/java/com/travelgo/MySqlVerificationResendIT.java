package com.travelgo;

/** Repeats signup row-lock/cooldown acceptance only on the guarded disposable MySQL endpoint. */
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.test.context.ActiveProfiles(value = "mysql-test", inheritProfiles = false)
class MySqlVerificationResendIT extends VerificationResendTests { }

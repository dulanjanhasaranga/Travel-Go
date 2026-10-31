package com.travelgo;

/** Durable token/outbox commits on disposable MySQL; the mail transport remains mocked. */
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.test.context.ActiveProfiles(value = "mysql-test", inheritProfiles = false)
class MySqlAccountEmailDurabilityIT extends AccountEmailDurabilityTests { }

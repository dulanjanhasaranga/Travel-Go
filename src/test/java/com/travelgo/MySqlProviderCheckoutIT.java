package com.travelgo;

import org.springframework.test.context.ActiveProfiles;

/** Same booking-lock and provider review coverage, restricted to the guarded disposable MySQL profile. */
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles(value = "mysql-test", inheritProfiles = false)
class MySqlProviderCheckoutIT extends ProviderCheckoutPersistenceTests { }

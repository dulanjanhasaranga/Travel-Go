package com.travelgo;

/** Stable account binding checks against the disposable MySQL database only. */
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.test.context.ActiveProfiles(value = "mysql-test", inheritProfiles = false)
class MySqlGoogleIdentityIT extends GoogleIdentityPersistenceTests { }

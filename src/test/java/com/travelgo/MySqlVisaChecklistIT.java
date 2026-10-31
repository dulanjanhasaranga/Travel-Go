package com.travelgo;

/** Opt-in checklist/locking verification on the guarded disposable MySQL database, never application SQL. */
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.test.context.ActiveProfiles(value="mysql-test",inheritProfiles=false)
class MySqlVisaChecklistIT extends VisaChecklistTests {}

package com.travelgo;

/** The public account journey on the guarded disposable MySQL fixture only. */
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.test.context.ActiveProfiles(value = "mysql-test", inheritProfiles = false)
class MySqlAccountRecoveryFlowIT extends AccountRecoveryFlowTests { }

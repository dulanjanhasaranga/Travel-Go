package com.travelgo;

/** The same atomic settlement/refund checks on the guarded, disposable MySQL profile. */
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.test.context.ActiveProfiles(value = "mysql-test", inheritProfiles = false)
class MySqlFinancialSettlementIT extends FinancialSettlementSafetyTests { }

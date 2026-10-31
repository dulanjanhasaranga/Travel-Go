package com.travelgo;

/** Executes policy, supplier and cancellation workflows only on the guarded disposable MySQL server. */
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.test.context.ActiveProfiles(value="mysql-test",inheritProfiles=false)
class MySqlAgencyOperationsWorkflowIT extends AgencyOperationsWorkflowTests {}

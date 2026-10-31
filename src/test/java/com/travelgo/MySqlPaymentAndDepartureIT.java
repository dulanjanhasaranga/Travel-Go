package com.travelgo;

/** Opt-in inventory and payment locking checks against the disposable MySQL test server. */
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.test.context.ActiveProfiles(value = "mysql-test", inheritProfiles = false)
public class MySqlPaymentAndDepartureIT extends PaymentAndDepartureRegressionTests {
}

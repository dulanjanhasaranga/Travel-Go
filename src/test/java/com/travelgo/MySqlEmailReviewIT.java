package com.travelgo;

/** Row-lock, duplicate-review and audit rollback assertions against disposable MySQL, without SMTP. */
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.test.context.ActiveProfiles(value = "mysql-test", inheritProfiles = false)
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.config.import=",
        "spring.datasource.url=jdbc:mysql://127.0.0.1:33317/travelgo_disposable_test?useSSL=false&allowPublicKeyRetrieval=true",
        "travelgo.mail.enabled=false", "travelgo.mail.dispatch-enabled=false", "travelgo.mail.account-retry-enabled=false",
        "travelgo.payhere.enabled=false", "travelgo.stripe.enabled=false", "travelgo.auth.google.enabled=false",
        "travelgo.demo.enabled=false", "travelgo.bootstrap.admin.email=", "travelgo.bootstrap.admin.password="})
class MySqlEmailReviewIT extends EmailReviewTests {
    @org.junit.jupiter.api.BeforeEach
    void requireNativeMySql(@org.springframework.beans.factory.annotation.Autowired javax.sql.DataSource source) throws Exception {
        try (var connection = source.getConnection()) {
            org.junit.jupiter.api.Assertions.assertTrue(connection.getMetaData().getURL()
                    .startsWith("jdbc:mysql://127.0.0.1:33317/travelgo_disposable_test?"));
            org.junit.jupiter.api.Assertions.assertEquals("travelgo_disposable_test", connection.getCatalog());
        }
    }
}

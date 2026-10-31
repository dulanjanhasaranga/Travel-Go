package com.travelgo;

@org.springframework.test.context.ActiveProfiles(value="mysql-test",inheritProfiles=false)
@org.springframework.test.context.TestPropertySource(properties="spring.datasource.url=jdbc:mysql://127.0.0.1:33317/travelgo_disposable_test?useSSL=false&allowPublicKeyRetrieval=true")
class MySqlOfflineRefundSettlementIT extends OfflineRefundSettlementTests {
    @org.junit.jupiter.api.BeforeEach
    void requireNativeMySql(@org.springframework.beans.factory.annotation.Autowired javax.sql.DataSource source) throws Exception {
        try(var connection=source.getConnection()) {
            org.junit.jupiter.api.Assertions.assertTrue(connection.getMetaData().getURL()
                    .startsWith("jdbc:mysql://127.0.0.1:33317/travelgo_disposable_test?"));
            org.junit.jupiter.api.Assertions.assertEquals("travelgo_disposable_test", connection.getCatalog());
        }
    }
}

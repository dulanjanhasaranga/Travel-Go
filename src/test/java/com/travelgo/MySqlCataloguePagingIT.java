package com.travelgo;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in SQL paging checks on the separately initialized disposable MySQL instance. */
@SpringBootTest(properties = {"spring.config.import=", "spring.jpa.open-in-view=false",
        "spring.datasource.url=jdbc:mysql://127.0.0.1:33317/travelgo_disposable_test?useSSL=false&allowPublicKeyRetrieval=true",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@ActiveProfiles(value = "mysql-test", inheritProfiles = false)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class MySqlCataloguePagingIT extends CataloguePagingFreshnessTests {
    @Autowired DataSource dataSource;

    @BeforeEach void requireNativeDisposableDatabase() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:mysql://127.0.0.1:33317/travelgo_disposable_test"));
            assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
            try (var result = statement.executeQuery("SELECT DATABASE(), @@port")) {
                assertTrue(result.next());
                assertEquals("travelgo_disposable_test", result.getString(1));
                assertEquals(33317, result.getInt(2));
            }
        }
    }
}

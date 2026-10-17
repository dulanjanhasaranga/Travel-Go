package com.travelgo.config;

import com.travelgo.TestDatabaseSafetyConfiguration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static com.travelgo.config.AuthenticationRequestLimiter.RequestKind.*;

/** Real transactional shared store, synthetic identities, no provider or application database. */
@SpringBootTest(properties={"spring.config.import=", "spring.datasource.url=jdbc:h2:mem:shared-auth-budget;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "travelgo.mail.enabled=false", "travelgo.mail.dispatch-enabled=false", "travelgo.mail.account-retry-enabled=false"})
@ActiveProfiles("test") @Import(TestDatabaseSafetyConfiguration.class)
public class SharedAuthenticationRequestLimiterTests {
    static final String KEY=Base64.getEncoder().encodeToString(new byte[32]);
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    SharedAuthenticationRequestLimiter first,second;
    AuthenticationAbuseProperties properties(int capacity) {
        return new AuthenticationAbuseProperties(true,capacity,4,2,900,10,3,3600,20,3600,3,900,30,900);
    }
    @BeforeEach void fixture() {
        jdbc.update("DELETE FROM authentication_rate_cells");
        jdbc.update("INSERT INTO authentication_rate_cells(opaque_key,admitted_count,expires_at_ms) VALUES(?,0,0)",SharedAuthenticationRequestLimiter.GUARD);
        first=new SharedAuthenticationRequestLimiter(properties(100),KEY,jdbc,manager);
        second=new SharedAuthenticationRequestLimiter(properties(100),KEY,jdbc,manager);
    }
    @Test void independentInstancesAndReconstructionShareTheSameBudget() {
        assertTrue(first.acquire(LOGIN,"192.0.2.10","person@example.invalid").allowed());
        assertTrue(second.acquire(LOGIN,"192.0.2.10","PERSON@example.invalid").allowed());
        var restarted=new SharedAuthenticationRequestLimiter(properties(100),KEY,jdbc,manager);
        var denied=restarted.acquire(LOGIN,"192.0.2.10"," person@example.invalid ");
        assertFalse(denied.allowed()); assertTrue(denied.retryAfterSeconds()>=1 && denied.retryAfterSeconds()<=900);
    }
    @Test void expiryReclaimsCapacityWithoutEvictingUnexpiredLimits() {
        var limited=new SharedAuthenticationRequestLimiter(properties(2),KEY,jdbc,manager);
        assertTrue(limited.acquire(LOGIN,"192.0.2.11","one@example.invalid").allowed());
        assertFalse(limited.acquire(LOGIN,"192.0.2.12","two@example.invalid").allowed());
        assertEquals(2L,jdbc.queryForObject("SELECT COUNT(*) FROM authentication_rate_cells WHERE opaque_key<>?",Long.class,SharedAuthenticationRequestLimiter.GUARD));
        jdbc.update("UPDATE authentication_rate_cells SET expires_at_ms=0 WHERE opaque_key<>?",SharedAuthenticationRequestLimiter.GUARD);
        assertTrue(limited.acquire(LOGIN,"192.0.2.12","two@example.invalid").allowed());
    }
    @Test void sourceBudgetStopsPasswordSprayingAndDenialDoesNotConsumeOtherBudgets() {
        assertTrue(first.acquire(LOGIN,"192.0.2.13","a@example.invalid").allowed());
        assertTrue(first.acquire(LOGIN,"192.0.2.13","a@example.invalid").allowed());
        assertFalse(first.acquire(LOGIN,"192.0.2.13","a@example.invalid").allowed());
        assertTrue(second.acquire(LOGIN,"192.0.2.13","b@example.invalid").allowed());
        assertTrue(second.acquire(LOGIN,"192.0.2.13","c@example.invalid").allowed());
        assertFalse(second.acquire(LOGIN,"192.0.2.13","d@example.invalid").allowed());
        assertTrue(second.acquire(LOGIN,"192.0.2.14","a@example.invalid").allowed());
    }
    @Test void concurrentInstancesNeverAdmitAboveTheSharedLimit() throws Exception {
        var pool=Executors.newFixedThreadPool(6);
        var start=new CountDownLatch(1);
        try {
            List<Future<Boolean>> results=new ArrayList<>();
            for(int i=0;i<12;i++) {
                var limiter=i%2==0?first:second;
                results.add(pool.submit(()->{start.await();return limiter.acquire(LOGIN,"192.0.2.15","one@example.invalid").allowed();}));
            }
            start.countDown(); int allowed=0;
            for(var result:results) if(result.get(20,TimeUnit.SECONDS)) allowed++;
            assertEquals(2,allowed);
        } finally {start.countDown();pool.shutdownNow();}
    }
    @Test void rowsDoNotContainRawAddressesEmailsOrTokens() {
        first.acquire(EMAIL_REQUEST,"192.0.2.16","private@example.invalid");
        var rows=jdbc.queryForList("SELECT opaque_key FROM authentication_rate_cells WHERE opaque_key<>?",String.class,SharedAuthenticationRequestLimiter.GUARD);
        assertEquals(2,rows.size());
        assertTrue(rows.stream().allMatch(key->key.matches("b:[a-f0-9]{64}")));
    }
    @Test void missingGuardOrCorruptCountersFailClosedWithoutLocalFallback() {
        jdbc.update("DELETE FROM authentication_rate_cells");
        assertFalse(first.acquire(LOGIN,"192.0.2.17","one@example.invalid").allowed());
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM authentication_rate_cells",Long.class));
        jdbc.update("INSERT INTO authentication_rate_cells(opaque_key,admitted_count,expires_at_ms) VALUES(?,1,0)",SharedAuthenticationRequestLimiter.GUARD);
        assertFalse(second.acquire(LOGIN,"192.0.2.17","one@example.invalid").allowed());
    }
    @Test void endpointFamiliesHaveIndependentAndSharedBudgets() {
        for(int i=0;i<3;i++) assertTrue(first.acquire(EMAIL_REQUEST,"192.0.2.18","one@example.invalid").allowed());
        assertFalse(second.acquire(EMAIL_REQUEST,"192.0.2.18","one@example.invalid").allowed());
        assertTrue(second.acquire(REGISTRATION,"192.0.2.18","one@example.invalid").allowed());
        assertTrue(second.acquire(TOKEN_SUBMISSION,"192.0.2.18","ignored-sensitive-token").allowed());
    }
    @Test void malformedKeysCannotSelectAnUnprotectedSharedStore() {
        for(String key:List.of("", "placeholder", KEY+" ",Base64.getEncoder().encodeToString(new byte[31]))) {
            var failure=assertThrows(IllegalStateException.class,()->new SharedAuthenticationRequestLimiter(properties(100),key,jdbc,manager));
            assertFalse(failure.getMessage().contains(key)&&!key.isEmpty());
        }
    }
    @Test void secondBudgetWriteFailureRollsBackFirstCounterAndGuardClock() {
        var inserts = new java.util.concurrent.atomic.AtomicInteger();
        var failingJdbc = new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource())) {
            @Override public int update(String sql, Object... arguments) {
                if (sql.startsWith("INSERT INTO authentication_rate_cells") && inserts.incrementAndGet() == 2)
                    throw new org.springframework.dao.DataAccessResourceFailureException("Synthetic second budget write failure");
                return super.update(sql, arguments);
            }
        };
        var failing = new SharedAuthenticationRequestLimiter(properties(100), KEY, failingJdbc, manager);
        assertFalse(failing.acquire(LOGIN, "192.0.2.25", "rollback@example.invalid").allowed());
        assertEquals(2, inserts.get(), "Reach the write after the first counter and guard update");
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM authentication_rate_cells", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT expires_at_ms FROM authentication_rate_cells WHERE opaque_key=?",
                Long.class, SharedAuthenticationRequestLimiter.GUARD));
        assertTrue(second.acquire(LOGIN, "192.0.2.25", "rollback@example.invalid").allowed());
        assertEquals(3L, jdbc.queryForObject("SELECT COUNT(*) FROM authentication_rate_cells", Long.class));
    }
}

package com.travelgo.config;

import java.sql.ResultSet;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable budgets shared by every application using the same MySQL store/key. */
public final class SharedAuthenticationRequestLimiter extends AuthenticationRequestLimiter {
    public static final String GUARD="admission-guard-v1";
    private final AuthenticationAbuseProperties properties;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private record Stored(String key,int count,long expiry) { }

    public SharedAuthenticationRequestLimiter(AuthenticationAbuseProperties properties,String key,
            JdbcTemplate jdbc,PlatformTransactionManager manager) {
        super(properties,keyBytes(key));
        this.properties=properties; this.jdbc=jdbc;
        transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(5);
    }
    private static byte[] keyBytes(String value) {
        try {
            if (value==null || !value.matches("[A-Za-z0-9+/]{43}=")) throw new IllegalArgumentException();
            byte[] bytes=Base64.getDecoder().decode(value);
            if(bytes.length!=32) throw new IllegalArgumentException();
            return bytes;
        } catch(IllegalArgumentException invalid) {
            throw new IllegalStateException("The shared authentication store requires a private Base64 32-byte HMAC key.");
        }
    }
    public static void validateKey(String value) { Arrays.fill(keyBytes(value),(byte)0); }

    @Override public Decision acquire(RequestKind kind,String source,String email) {
        if(!properties.enabled()) return new Decision(true,0);
        List<Rule> rules=rulesFor(kind,source,email);
        try {
            Decision result=transaction.execute(status -> admit(rules));
            return result==null ? denied(60_000) : result;
        } catch(RuntimeException unavailable) {
            // A missing migration/guard, contention, timeout or database outage
            // never resets a budget or falls back to process-local protection.
            return denied(60_000);
        }
    }
    private Decision admit(List<Rule> rules) {
        // One bounded admission lock provides atomic cross-budget/capacity checks.
        // It is provisioned by reviewed DDL; no automatic startup inserts occur.
        Stored guard=jdbc.queryForObject("SELECT opaque_key,admitted_count,expires_at_ms FROM authentication_rate_cells WHERE opaque_key=? FOR UPDATE",
                (rs,row)->cell(rs),GUARD);
        if(guard==null) throw new IllegalStateException("Authentication store is unavailable.");
        if(guard.count()!=0 || guard.expiry()<0) throw new IllegalStateException("Authentication store state is invalid.");
        long now=Math.max(guard.expiry(),databaseMillis());
        jdbc.update("DELETE FROM authentication_rate_cells WHERE opaque_key<>? AND expires_at_ms<=?",GUARD,now);
        Map<String,Stored> retained=new HashMap<>();
        long wait=0;
        for(Rule rule:rules) {
            String key="b:"+rule.key();
            List<Stored> found=jdbc.query("SELECT opaque_key,admitted_count,expires_at_ms FROM authentication_rate_cells WHERE opaque_key=?",
                    (rs,row)->cell(rs),key);
            if(!found.isEmpty()) {
                Stored value=found.get(0); retained.put(key,value);
                if(value.count()<1 || value.expiry()<now) throw new IllegalStateException("Authentication store state is invalid.");
                if(value.count()>=rule.budget().limit()) wait=Math.max(wait,value.expiry()-now);
            }
        }
        if(wait>0) return denied(wait);
        int needed=rules.size()-retained.size();
        if(needed>0) {
            Long count=jdbc.queryForObject("SELECT COUNT(*) FROM authentication_rate_cells WHERE opaque_key<>?",Long.class,GUARD);
            if(count==null) throw new IllegalStateException("Authentication store is unavailable.");
            if(count+needed>properties.maximumBuckets()) {
                Long first=jdbc.queryForObject("SELECT MIN(expires_at_ms) FROM authentication_rate_cells WHERE opaque_key<>?",Long.class,GUARD);
                return denied(first==null?1000:Math.max(1,first-now));
            }
        }
        jdbc.update("UPDATE authentication_rate_cells SET expires_at_ms=? WHERE opaque_key=?",now,GUARD);
        for(Rule rule:rules) {
            String key="b:"+rule.key();
            if(retained.containsKey(key)) jdbc.update("UPDATE authentication_rate_cells SET admitted_count=admitted_count+1 WHERE opaque_key=?",key);
            else jdbc.update("INSERT INTO authentication_rate_cells(opaque_key,admitted_count,expires_at_ms) VALUES(?,1,?)",key,Math.addExact(now,rule.budget().windowMillis()));
        }
        return new Decision(true,0);
    }
    private long databaseMillis() {
        Long value=jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Long>) connection -> {
            String product=connection.getMetaData().getDatabaseProductName();
            String sql=switch(product) {case "MySQL" -> "SELECT UTC_TIMESTAMP(3)"; case "H2" -> "SELECT CURRENT_TIMESTAMP";
                default -> throw new IllegalStateException("The authentication store database is unsupported.");};
            try(var statement=connection.prepareStatement(sql)) {
                org.springframework.jdbc.datasource.DataSourceUtils.applyTransactionTimeout(statement,Objects.requireNonNull(jdbc.getDataSource()));
                try(var result=statement.executeQuery()) {
                if(!result.next()) throw new IllegalStateException("The authentication store clock is unavailable.");
                if("H2".equals(product)) return result.getObject(1,java.time.OffsetDateTime.class).toInstant().toEpochMilli();
                return result.getTimestamp(1,Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant().toEpochMilli();
                }
            }
        });
        if(value==null || value<0) throw new IllegalStateException("The authentication store clock is unavailable.");
        return value;
    }
    private static Stored cell(ResultSet result) throws java.sql.SQLException {
        return new Stored(result.getString(1),result.getInt(2),result.getLong(3));
    }
}

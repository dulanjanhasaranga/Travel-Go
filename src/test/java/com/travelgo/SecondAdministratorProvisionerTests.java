package com.travelgo;

import com.travelgo.operations.SecondAdministratorProvisioner;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SecondAdministratorProvisionerTests {
    JdbcDataSource dataSource;
    final String actor="reviewer@example.invalid", newEmail="second@example.invalid", actorPassword="Synthetic-reviewer123!", newPassword="Synthetic-second123!";
    @BeforeEach void fixture() throws Exception {
        dataSource=new JdbcDataSource(); dataSource.setURL("jdbc:h2:mem:ops-provision-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (var connection=dataSource.getConnection(); var statement=connection.createStatement()) {
            statement.execute("CREATE TABLE roles(id BIGINT PRIMARY KEY,role_name VARCHAR(50))");
            statement.execute("CREATE TABLE permissions(id BIGINT PRIMARY KEY,permission_name VARCHAR(100))");
            statement.execute("CREATE TABLE role_permissions(role_id BIGINT,permission_id BIGINT)");
            statement.execute("CREATE TABLE users(id BIGINT AUTO_INCREMENT PRIMARY KEY,name VARCHAR(100),email VARCHAR(150) UNIQUE,password VARCHAR(255),role_id BIGINT,is_active BOOLEAN,is_demo_account BOOLEAN,created_at TIMESTAMP,updated_at TIMESTAMP)");
            statement.execute("CREATE TABLE audit_logs(id BIGINT AUTO_INCREMENT PRIMARY KEY,action VARCHAR(255),entity_name VARCHAR(255),entity_id BIGINT,username VARCHAR(255),timestamp TIMESTAMP,details VARCHAR(2000))");
            statement.execute("INSERT INTO roles VALUES(1,'ADMIN'),(2,'CUSTOMER')");
            statement.execute("INSERT INTO permissions VALUES(1,'USER_MANAGE'),(2,'ROLE_MANAGE')");
            statement.execute("INSERT INTO role_permissions VALUES(1,1),(1,2)");
            account(connection,actor,new BCryptPasswordEncoder().encode(actorPassword),1,true,false);
        }
    }
    SecondAdministratorProvisioner.Request request() { return new SecondAdministratorProvisioner.Request(actor,actorPassword,"Authorized reviewer",newEmail,newPassword); }
    String provision(SecondAdministratorProvisioner.Request request, boolean apply) throws Exception { try (var connection=dataSource.getConnection()) { return SecondAdministratorProvisioner.provision(connection,request,apply); } }
    void account(Connection connection,String email,String password,int role,boolean active,boolean demo) throws Exception {
        try (var statement=connection.prepareStatement("INSERT INTO users(name,email,password,role_id,is_active,is_demo_account) VALUES('Fixture',?,?,?,?,?)")) {
            statement.setString(1,email); statement.setString(2,password); statement.setInt(3,role); statement.setBoolean(4,active); statement.setBoolean(5,demo); statement.executeUpdate();
        }
    }
    long count(String table) throws Exception { try(var connection=dataSource.getConnection(); var statement=connection.createStatement();var rows=statement.executeQuery("SELECT COUNT(*) FROM "+table)) { rows.next(); return rows.getLong(1); } }
    @Test void dryRunAuthenticatesButCreatesNeitherAccountNorAudit() throws Exception {
        assertTrue(provision(request(),false).contains("Dry run")); assertEquals(1,count("users")); assertEquals(0,count("audit_logs"));
    }
    @Test void explicitApplyCreatesOneHashedAdministratorAndTransactionalAudit() throws Exception {
        provision(request(),true); assertEquals(2,count("users")); assertEquals(1,count("audit_logs"));
        try (var connection=dataSource.getConnection(); var statement=connection.createStatement();var rows=statement.executeQuery("SELECT password,role_id,is_active,is_demo_account FROM users WHERE email='second@example.invalid'")) {
            assertTrue(rows.next()); assertNotEquals(newPassword,rows.getString(1)); assertTrue(new BCryptPasswordEncoder().matches(newPassword,rows.getString(1)));
            assertEquals(1,rows.getLong(2)); assertTrue(rows.getBoolean(3)); assertFalse(rows.getBoolean(4));
        }
    }
    @Test void repeatsDoNotReplacePasswordsOrCreateAdditionalAudits() throws Exception {
        provision(request(),true); String repeated=provision(new SecondAdministratorProvisioner.Request(actor,actorPassword,"Another name",newEmail,"Different-password123!"),true);
        assertTrue(repeated.contains("already exists")); assertEquals(2,count("users")); assertEquals(1,count("audit_logs"));
        try (var connection=dataSource.getConnection();var statement=connection.createStatement();var rows=statement.executeQuery("SELECT password FROM users WHERE email='second@example.invalid'")) { rows.next(); assertTrue(new BCryptPasswordEncoder().matches(newPassword,rows.getString(1))); }
    }
    @Test void existingCustomerIdentityIsNeverPromoted() throws Exception {
        try (var connection=dataSource.getConnection()) { account(connection,newEmail,"customer-original-hash",2,true,false); }
        assertThrows(IllegalStateException.class,()->provision(request(),true)); assertEquals(2,count("users")); assertEquals(0,count("audit_logs"));
    }
    @Test void wrongCredentialsMissingPermissionsAndWeakPasswordsFailWithoutChanges() throws Exception {
        assertThrows(IllegalArgumentException.class,()->provision(new SecondAdministratorProvisioner.Request(actor,"Wrong-password123!","Reviewer",newEmail,newPassword),true));
        assertThrows(IllegalArgumentException.class,()->provision(new SecondAdministratorProvisioner.Request(actor,actorPassword,"Reviewer",newEmail,"short"),true));
        try (var connection=dataSource.getConnection();var statement=connection.createStatement()) { statement.execute("DELETE FROM role_permissions WHERE permission_id=2"); }
        assertThrows(IllegalArgumentException.class,()->provision(request(),true)); assertEquals(1,count("users")); assertEquals(0,count("audit_logs"));
    }
    @Test void broaderProvisioningIsBlockedWhenTwoAdministratorsAlreadyExist() throws Exception {
        try (var connection=dataSource.getConnection()) { account(connection,"other-admin@example.invalid","fixture-only-hash",1,true,false); }
        assertThrows(IllegalStateException.class,()->provision(request(),true)); assertEquals(2,count("users")); assertEquals(0,count("audit_logs"));
    }
    @Test void failedAuditRollsBackTheNewAdministrator() throws Exception {
        try (var connection=dataSource.getConnection();var statement=connection.createStatement()) { statement.execute("DROP TABLE audit_logs"); }
        assertThrows(SQLException.class,()->provision(request(),true)); assertEquals(1,count("users"));
    }
}

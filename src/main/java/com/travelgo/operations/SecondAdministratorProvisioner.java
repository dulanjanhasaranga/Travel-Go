package com.travelgo.operations;

import com.travelgo.util.PasswordPolicy;
import com.travelgo.util.SafeMySqlUrl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.sql.*;
import java.util.Locale;
import java.util.Set;

/** Offline recovery procedure for a single-administrator installation, never a self-approval HTTP route. */
public final class SecondAdministratorProvisioner {
    private SecondAdministratorProvisioner() {}

    public static void main(String[] args) {
        boolean apply=args.length==1 && "--apply".equals(args[0]);
        if (args.length>0 && !apply) { System.err.println("Only the explicit --apply option is supported."); System.exit(1); }
        try {
            String url=environment("TRAVELGO_DB_URL");
            validateDatabaseUrl(url);
            String username=environment("TRAVELGO_DB_USERNAME");
            if ("root".equalsIgnoreCase(username.trim())) throw new IllegalArgumentException("Use a restricted account with the reviewed provisioning privileges.");
            var request=new Request(environment("TRAVELGO_PROVISION_ACTOR_EMAIL"), environment("TRAVELGO_PROVISION_ACTOR_PASSWORD"),
                    environment("TRAVELGO_PROVISION_ADMIN_NAME"),environment("TRAVELGO_PROVISION_ADMIN_EMAIL"),environment("TRAVELGO_PROVISION_ADMIN_PASSWORD"));
            try (var connection=DriverManager.getConnection(url,username,environment("TRAVELGO_DB_PASSWORD"))) {
                System.out.println(provision(connection,request,apply));
            }
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            // Validation messages are fixed text and never include supplied names, emails or credentials.
            System.err.println(invalid.getMessage()); System.exit(1);
        } catch (Exception unavailable) {
            System.err.println("Provisioning failed. No credentials are displayed; ask the operator to inspect database access and schema compatibility."); System.exit(1);
        }
    }

    public static String provision(Connection connection, Request supplied, boolean apply) throws SQLException {
        Request request=supplied.validated();
        boolean previousAutoCommit=connection.getAutoCommit();
        if (!previousAutoCommit) throw new IllegalArgumentException("Provisioning requires its own dedicated connection.");
        connection.setAutoCommit(false);
        try {
            long roleId;
            try (var statement=connection.prepareStatement("SELECT id FROM roles WHERE role_name='ADMIN' FOR UPDATE"); var rows=statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("The existing administrator role is required; this tool does not install reference data.");
                roleId=rows.getLong(1);
                if (rows.next()) throw new IllegalStateException("Ambiguous administrator roles require operator review.");
            }
            try (var statement=connection.prepareStatement("SELECT password,role_id,is_active,is_demo_account FROM users WHERE LOWER(email)=? FOR UPDATE")) {
                statement.setString(1,request.actorEmail);
                try (var rows=statement.executeQuery()) {
                    if (!rows.next()) throw new IllegalArgumentException("The authorizing administrator credentials are invalid.");
                    boolean authorized=rows.getLong(2)==roleId && rows.getBoolean(3) && !rows.getBoolean(4)
                            && new BCryptPasswordEncoder().matches(request.actorPassword,rows.getString(1));
                    if (rows.next() || !authorized) throw new IllegalArgumentException("The authorizing administrator credentials are invalid.");
                }
            }
            var permissionNames=new java.util.HashSet<String>();
            try (var statement=connection.prepareStatement("SELECT p.permission_name FROM permissions p JOIN role_permissions rp ON rp.permission_id=p.id WHERE rp.role_id=?")) {
                statement.setLong(1,roleId); try (var rows=statement.executeQuery()) { while (rows.next()) permissionNames.add(rows.getString(1)); }
            }
            if (!permissionNames.containsAll(Set.of("USER_MANAGE","ROLE_MANAGE")))
                throw new IllegalArgumentException("The authorizing administrator requires the existing user and role management permissions.");
            try (var statement=connection.prepareStatement("SELECT role_id,is_active,is_demo_account FROM users WHERE LOWER(email)=? FOR UPDATE")) {
                statement.setString(1,request.email); try (var rows=statement.executeQuery()) {
                    if (rows.next()) {
                        boolean existing=rows.getLong(1)==roleId && rows.getBoolean(2) && !rows.getBoolean(3);
                        if (rows.next() || !existing) throw new IllegalStateException("That email is already assigned. This tool never promotes or changes an existing account.");
                        connection.rollback();
                        return "The active administrator already exists; no account or password was changed.";
                    }
                }
            }
            try (var statement=connection.prepareStatement("SELECT COUNT(*) FROM users WHERE role_id=? AND is_active=TRUE AND is_demo_account=FALSE")) {
                statement.setLong(1,roleId); try (var rows=statement.executeQuery()) {
                    rows.next(); if (rows.getLong(1)!=1) throw new IllegalStateException("This procedure is limited to an installation with exactly one active administrator. Use the normal approval workflow otherwise.");
                }
            }
            if (!apply) { connection.rollback(); return "Checks passed. Dry run only: no administrator or audit record was created."; }
            long createdId;
            try (var statement=connection.prepareStatement("INSERT INTO users(name,email,password,role_id,is_active,is_demo_account,created_at,updated_at) VALUES(?,?,?,?,TRUE,FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1,request.name); statement.setString(2,request.email);
                statement.setString(3,new BCryptPasswordEncoder(10).encode(request.password)); statement.setLong(4,roleId);
                if (statement.executeUpdate()!=1) throw new SQLException("Account insert failed.");
                try (var keys=statement.getGeneratedKeys()) { if (!keys.next()) throw new SQLException("Account identity unavailable."); createdId=keys.getLong(1); }
            }
            try (var statement=connection.prepareStatement("INSERT INTO audit_logs(action,entity_name,entity_id,username,timestamp,details) VALUES('SECOND_ADMIN_PROVISIONED','USER',?,?,CURRENT_TIMESTAMP,?)")) {
                statement.setLong(1,createdId); statement.setString(2,request.actorEmail);
                statement.setString(3,"Controlled offline second-administrator provisioning; existing ADMIN permissions retained. No prior account was modified.");
                if (statement.executeUpdate()!=1) throw new SQLException("Audit insert failed.");
            }
            connection.commit();
            return "Second administrator provisioned and audited. Remove provisioning credentials from the process environment now.";
        } catch (RuntimeException | SQLException failed) {
            connection.rollback(); throw failed;
        } finally { connection.setAutoCommit(previousAutoCommit); }
    }

    private static String environment(String name) {
        String value=System.getenv(name);
        if (value==null || value.isBlank()) throw new IllegalArgumentException("Set the required provisioning environment variables; no default credentials are available.");
        return value;
    }
    static void validateDatabaseUrl(String url) {
        SafeMySqlUrl.validate(url);
    }
    public static final class Request {
        private final String actorEmail, actorPassword, name, email, password;
        public Request(String actorEmail,String actorPassword,String name,String email,String password) {
            this.actorEmail=actorEmail; this.actorPassword=actorPassword; this.name=name; this.email=email; this.password=password;
        }
        private Request validated() {
            String cleanActor=email(actorEmail), cleanEmail=email(email);
            if (cleanActor.equals(cleanEmail)) throw new IllegalArgumentException("The second administrator must have a distinct verified identity.");
            if (name==null || name.trim().isEmpty() || name.trim().length()>100 || name.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Supply the administrator's real name, between 1 and 100 characters.");
            if (actorPassword==null || actorPassword.isBlank()) throw new IllegalArgumentException("The authorizing administrator credentials are required.");
            PasswordPolicy.requireValid(password);
            return new Request(cleanActor,actorPassword,name.trim(),cleanEmail,password);
        }
        private static String email(String input) {
            String value=input==null?"":input.trim().toLowerCase(Locale.ROOT);
            if (value.length()>150 || !value.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new IllegalArgumentException("Supply valid administrator email addresses.");
            return value;
        }
        @Override public String toString() { return "Second administrator request (credentials redacted)"; }
    }
}

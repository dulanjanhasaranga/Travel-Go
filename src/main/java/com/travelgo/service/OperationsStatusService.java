package com.travelgo.service;

import com.travelgo.repository.OutboundEmailRepository;
import com.travelgo.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;

/** Selected dependency checks. Provider delivery, backups and launch approval need separate evidence. */
@Service
public class OperationsStatusService {
    private final DataSource dataSource;
    private final UserRepository users;
    private final OutboundEmailRepository emails;
    private final VisaDocumentStorage documents;
    private final Clock clock;
    private final boolean mailEnabled, dispatchEnabled, simulatedPayments, stripeEnabled, googleEnabled;

    public OperationsStatusService(DataSource dataSource, UserRepository users, OutboundEmailRepository emails, Clock clock,
            @Value("${travelgo.upload-directory:uploads/visa-documents}") String documents,
            @Value("${travelgo.mail.enabled:false}") boolean mailEnabled,
            @Value("${travelgo.mail.dispatch-enabled:false}") boolean dispatchEnabled,
            @Value("${travelgo.payments.simulated:true}") boolean simulatedPayments,
            @Value("${travelgo.stripe.enabled:false}") boolean stripeEnabled,
            @Value("${travelgo.auth.google.enabled:false}") boolean googleEnabled) {
        this(dataSource,users,emails,clock,documents,mailEnabled,dispatchEnabled,simulatedPayments,stripeEnabled,googleEnabled,
                new FilesystemVisaDocumentStorage(documents));
    }

    @Autowired
    public OperationsStatusService(DataSource dataSource, UserRepository users, OutboundEmailRepository emails, Clock clock,
            @Value("${travelgo.upload-directory:uploads/visa-documents}") String documents,
            @Value("${travelgo.mail.enabled:false}") boolean mailEnabled,
            @Value("${travelgo.mail.dispatch-enabled:false}") boolean dispatchEnabled,
            @Value("${travelgo.payments.simulated:true}") boolean simulatedPayments,
            @Value("${travelgo.stripe.enabled:false}") boolean stripeEnabled,
            @Value("${travelgo.auth.google.enabled:false}") boolean googleEnabled, VisaDocumentStorage storage) {
        this.dataSource=dataSource; this.users=users; this.emails=emails; this.clock=clock;
        this.documents=storage;
        this.mailEnabled=mailEnabled; this.dispatchEnabled=dispatchEnabled; this.simulatedPayments=simulatedPayments;
        this.stripeEnabled=stripeEnabled; this.googleEnabled=googleEnabled;
    }

    public DependencyStatus dependencies() {
        boolean database=false, storage=false;
        try (var connection=dataSource.getConnection()) { database=connection.isValid(2); }
        catch (Exception unavailable) { /* Public status must never contain connection exceptions or secrets. */ }
        try {
            storage=documents.healthy();
        } catch (RuntimeException unavailable) { /* Same deliberately generic result. */ }
        return new DependencyStatus(database,storage);
    }

    public Snapshot snapshot() {
        var dependencies=dependencies();
        long administrators=-1, queued=-1, review=-1, freeBytes=-1;
        if (dependencies.database()) {
            try {
                administrators=users.findByRoleRoleName("ADMIN").stream().filter(u->u.isActive() && !u.isDemoAccount()).count();
                queued=emails.countByStatus("QUEUED");
                review=emails.countByStatus("REVIEW_REQUIRED") + emails.countByStatus("REVIEW_REQUIRED_CONFLICT")
                        + emails.countByStatus("SENDING");
            } catch (RuntimeException unavailable) { administrators=-1; queued=-1; review=-1; }
        }
        if (dependencies.documents()) {
            try { freeBytes=documents.availableBytes(); }
            catch (Exception unavailable) { /* No storage-path or operating-system exception is rendered. */ }
        }
        return new Snapshot(Instant.now(clock),dependencies,administrators,queued,review,freeBytes,
                mailEnabled,dispatchEnabled,simulatedPayments,stripeEnabled,googleEnabled);
    }

    public record DependencyStatus(boolean database, boolean documents) {
        public boolean healthy() { return database && documents; }
    }
    public record Snapshot(Instant checkedAt, DependencyStatus dependencies, long activeAdministrators,
                           long queuedEmails, long reviewEmails, long freeDocumentBytes,
                           boolean mailEnabled, boolean dispatchEnabled, boolean simulatedPayments,
                           boolean stripeEnabled, boolean googleEnabled) {}
}

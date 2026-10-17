package com.travelgo;

import com.travelgo.entity.Role;
import com.travelgo.entity.User;
import com.travelgo.repository.OutboundEmailRepository;
import com.travelgo.repository.UserRepository;
import com.travelgo.service.OperationsStatusService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OperationsStatusTests {
    @TempDir Path directory;
    final Clock clock=Clock.fixed(Instant.parse("2030-01-01T10:00:00Z"),ZoneOffset.UTC);
    final DataSource source=mock(DataSource.class);
    final UserRepository users=mock(UserRepository.class);
    final OutboundEmailRepository emails=mock(OutboundEmailRepository.class);
    OperationsStatusService service(Path documents) { return new OperationsStatusService(source,users,emails,clock,documents.toString(),false,false,true,false,false); }

    @Test void healthRequiresBothDatabaseAndDocumentStorageAndClosesTheConnection() throws Exception {
        Connection connection=mock(Connection.class); when(source.getConnection()).thenReturn(connection); when(connection.isValid(2)).thenReturn(true);
        assertTrue(service(directory).dependencies().healthy()); verify(connection).close();
        assertFalse(service(directory.resolve("missing")).dependencies().healthy());
    }
    @Test void databaseExceptionsBecomeGenericUnavailableResultsAndNeverExposeCredentials() throws Exception {
        when(source.getConnection()).thenThrow(new java.sql.SQLException("password=private-source-secret jdbc:mysql://private-host/database"));
        var snapshot=service(directory).snapshot();
        assertFalse(snapshot.dependencies().healthy()); assertEquals(-1,snapshot.queuedEmails());
        assertFalse(snapshot.toString().contains("private-source-secret")); assertFalse(snapshot.toString().contains(directory.toString()));
        verifyNoInteractions(users,emails);
    }
    @Test void activeRealAdministratorsAreCountedAndUncertainEmailsRequireReview() throws Exception {
        Connection connection=mock(Connection.class); when(source.getConnection()).thenReturn(connection); when(connection.isValid(2)).thenReturn(true);
        var role=new Role("ADMIN","test"); var active=new User("Active","active@example.invalid","hash",role);
        var inactive=new User("Inactive","inactive@example.invalid","hash",role); inactive.setActive(false);
        var demo=new User("Demo","demo@example.invalid","hash",role); demo.setDemoAccount(true);
        when(users.findByRoleRoleName("ADMIN")).thenReturn(List.of(active,inactive,demo));
        when(emails.countByStatus("QUEUED")).thenReturn(3L); when(emails.countByStatus("SENDING")).thenReturn(2L); when(emails.countByStatus("REVIEW_REQUIRED")).thenReturn(1L);
        when(emails.countByStatus("REVIEW_REQUIRED_CONFLICT")).thenReturn(4L);
        var snapshot=service(directory).snapshot();
        assertEquals(1,snapshot.activeAdministrators()); assertEquals(3,snapshot.queuedEmails()); assertEquals(7,snapshot.reviewEmails());
        assertEquals(Instant.now(clock),snapshot.checkedAt()); assertTrue(snapshot.simulatedPayments()); assertFalse(snapshot.mailEnabled());
    }
    @Test void failedAdministrativeCountsAreMarkedUnavailableWithoutTakingThePublicHealthOffline() throws Exception {
        Connection connection=mock(Connection.class); when(source.getConnection()).thenReturn(connection); when(connection.isValid(2)).thenReturn(true);
        when(users.findByRoleRoleName("ADMIN")).thenThrow(new IllegalStateException("private database metadata"));
        var snapshot=service(directory).snapshot();
        assertTrue(snapshot.dependencies().healthy()); assertEquals(-1,snapshot.activeAdministrators()); assertEquals(-1,snapshot.reviewEmails());
    }
}

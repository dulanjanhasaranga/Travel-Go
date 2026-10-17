package com.travelgo.service;

import com.travelgo.repository.OutboundEmailRepository;
import com.travelgo.repository.UserRepository;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OperationsStatusStorageTests {
    private final DataSource source = mock(DataSource.class);
    private final UserRepository users = mock(UserRepository.class);
    private final OutboundEmailRepository emails = mock(OutboundEmailRepository.class);
    private final VisaDocumentStorage storage = mock(VisaDocumentStorage.class);
    private final Clock clock = Clock.fixed(Instant.parse("2030-01-01T10:00:00Z"), ZoneOffset.UTC);

    @Test void selectedRemoteBackendIsHealthyEvenWhenLocalDirectoryDoesNotExist() throws Exception {
        healthyDatabase();
        when(storage.healthy()).thenReturn(true);
        when(storage.availableBytes()).thenReturn(-1L);

        var snapshot = service().snapshot();

        assertTrue(snapshot.dependencies().healthy());
        assertEquals(-1L, snapshot.freeDocumentBytes());
        assertEquals(Instant.now(clock), snapshot.checkedAt());
        verify(storage).healthy();
        verify(storage).availableBytes();
        verifyNoMoreInteractions(storage);
    }

    @Test void unavailableSelectedBackendDoesNotUseLocalDiskCapacity() throws Exception {
        healthyDatabase();
        when(storage.healthy()).thenReturn(false);

        var snapshot = service().snapshot();

        assertTrue(snapshot.dependencies().database());
        assertFalse(snapshot.dependencies().documents());
        assertEquals(-1L, snapshot.freeDocumentBytes());
        verify(storage).healthy();
        verify(storage, never()).availableBytes();
        verifyNoMoreInteractions(storage);
    }

    private OperationsStatusService service() {
        // An invalid local path detects accidental filesystem fallback on all host platforms.
        return new OperationsStatusService(source, users, emails, clock, "invalid\0local-directory",
                false, false, true, false, false, storage);
    }

    private void healthyDatabase() throws Exception {
        var connection = mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);
    }
}

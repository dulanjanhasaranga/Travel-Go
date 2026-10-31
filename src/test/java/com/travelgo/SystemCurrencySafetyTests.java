package com.travelgo;

import com.travelgo.entity.SystemSettings;
import com.travelgo.entity.User;
import com.travelgo.repository.RoleRepository;
import com.travelgo.repository.SystemSettingsRepository;
import com.travelgo.service.AdminAccess;
import com.travelgo.service.SystemSettingsService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SystemCurrencySafetyTests {
    SystemSettingsRepository repository = mock(SystemSettingsRepository.class);
    AdminAccess access = mock(AdminAccess.class);
    RoleRepository roles = mock(RoleRepository.class);
    SystemSettingsService service;
    SystemSettings settings;
    User actor = new User();

    @BeforeEach void fixture() {
        service = new SystemSettingsService(repository, access, roles);
        settings = new SystemSettings(); settings.setId(7L); settings.setCurrency("USD"); settings.setTimezone("UTC");
        when(repository.lockSettings()).thenReturn(List.of(settings)); when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(access.requireAdministrator("SYSTEM_SETTINGS_MANAGE")).thenReturn(actor);
    }

    @Test void supportedCurrencyAllowsValidTimezoneAndAuditsTheActualChange() {
        assertSame(settings, service.updateGeneralSettings("USD", "Asia/Colombo"));
        assertEquals("USD", settings.getCurrency()); assertEquals("Asia/Colombo", settings.getTimezone());
        verify(access).record(actor, "UPDATE_SETTINGS", "SystemSettings", 7L, "Updated time zone metadata; USD currency retained.");
    }

    @Test void unsupportedCurrencyNeverRelabelsExistingAmountsOrTimezone() {
        var failure = assertThrows(IllegalArgumentException.class, () -> service.updateGeneralSettings("LKR", "Asia/Colombo"));
        assertTrue(failure.getMessage().contains("Currency conversion is not implemented"));
        assertEquals("USD", settings.getCurrency()); assertEquals("UTC", settings.getTimezone());
        verify(repository, never()).lockSettings(); verify(repository, never()).save(any()); verify(access, never()).record(any(), anyString(), anyString(), any(), anyString());
    }

    @Test void historicalNonUsdCurrencyCannotBeSilentlyRepairedBySubmittingUsd() {
        settings.setCurrency("LKR");
        var failure = assertThrows(IllegalStateException.class, () -> service.updateGeneralSettings("USD", "Asia/Colombo"));
        assertTrue(failure.getMessage().contains("Review the existing catalogue and financial records"));
        assertEquals("LKR", settings.getCurrency()); assertEquals("UTC", settings.getTimezone()); verify(repository, never()).save(any());
    }

    @Test void invalidCurrencyOrTimezoneDoesNotMutateSettings() {
        assertThrows(IllegalArgumentException.class, () -> service.updateGeneralSettings("INVALID", "UTC"));
        assertThrows(IllegalArgumentException.class, () -> service.updateGeneralSettings("USD", "Invalid/timezone"));
        assertEquals("USD", settings.getCurrency()); assertEquals("UTC", settings.getTimezone()); verify(repository, never()).save(any());
    }
}

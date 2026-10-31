package com.travelgo;

import com.travelgo.service.LkrEstimateService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class LkrEstimateTests {
    private static final Clock NOW = Clock.fixed(Instant.parse("2030-02-10T10:00:00Z"), ZoneOffset.UTC);
    private static final String SOURCE = "https://www.cbsl.gov.lk/en/rates-and-indicators/exchange-rates/daily-indicative-usd-spot-exchange-rates";
    private LkrEstimateService service(boolean enabled, String rate, String date, String source, int maxAge) {
        return new LkrEstimateService(NOW, enabled, rate, date, source, maxAge);
    }

    @Test void usesDecimalRoundingAndDoesNotChangeTheUsdInput() {
        var service = service(true, "320.1234", "2030-02-10", SOURCE, 7);
        var usd = new BigDecimal("12.34");
        assertTrue(service.available());
        assertEquals("≈ LKR 3,950.32", service.format(usd));
        assertEquals(new BigDecimal("3950.32"), service.amount(usd));
        assertEquals(new BigDecimal("12.34"), usd);
        assertTrue(service.description().contains("rate dated 2030-02-10"));
        assertEquals(SOURCE, service.getSource());
    }

    @Test void defaultsDisabledAndAllowsZeroAmounts() {
        var disabled = service(false, "320", "2030-02-10", SOURCE, 7);
        assertFalse(disabled.available()); assertNull(disabled.format(BigDecimal.TEN));
        assertNull(disabled.getRate()); assertNull(disabled.getSource());
        assertEquals("≈ LKR 0.00", service(true, "320", "2030-02-10", SOURCE, 7).format(BigDecimal.ZERO));
    }

    @ParameterizedTest @ValueSource(strings = {"", "invalid", "0", "-320", "1000001", "1E999999", "320.123456789"})
    void invalidRatesFailClosed(String rate) {
        assertFalse(service(true, rate, "2030-02-10", SOURCE, 7).available());
    }

    @ParameterizedTest @ValueSource(strings = {"", "invalid", "2030-02-11", "2030-02-02"})
    void missingFutureAndStaleDatesFailClosed(String date) {
        assertFalse(service(true, "320", date, SOURCE, 7).available());
    }

    @Test void freshnessBoundaryUsesSriLankaDateAndCannotBeExtendedBeyondSevenDays() {
        assertTrue(service(true, "320", "2030-02-03", SOURCE, 7).available());
        assertFalse(service(true, "320", "2030-02-02", SOURCE, 999).available());
        assertFalse(service(true, "320", "2030-02-09", SOURCE, 0).available());
        var nextDayInColombo = Clock.fixed(Instant.parse("2030-02-10T20:00:00Z"), ZoneOffset.UTC);
        assertFalse(new LkrEstimateService(nextDayInColombo, true, "320", "2030-02-03", SOURCE, 7).available());
    }

    @ParameterizedTest @ValueSource(strings = {"", "http://example.com/rate", "javascript:alert(1)", "https://user:password@example.com", "https://example.com/#fragment", "invalid"})
    void rejectsUnsafeOrUnattributedSources(String source) {
        assertFalse(service(true, "320", "2030-02-10", source, 7).available());
    }

    @Test void rejectsInvalidAmounts() {
        var service = service(true, "320", "2030-02-10", SOURCE, 7);
        assertNull(service.format(null)); assertNull(service.format(new BigDecimal("-1")));
        assertNull(service.format(new BigDecimal("1000000000000")));
        assertNull(service.format(new BigDecimal("0.000000001")));
    }
}

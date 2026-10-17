package com.travelgo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Informational presentation only. Never used for a quote, payment, invoice or refund. */
@Service("lkrEstimates")
public class LkrEstimateService {
    private final Clock clock;
    private final boolean enabled;
    private final BigDecimal rate;
    private final LocalDate asOf;
    private final String source;
    private final int maximumAgeDays;

    public LkrEstimateService(Clock clock,
            @Value("${travelgo.pricing.lkr-estimates.enabled:false}") boolean enabled,
            @Value("${travelgo.pricing.lkr-estimates.rate:}") String rate,
            @Value("${travelgo.pricing.lkr-estimates.as-of:}") String asOf,
            @Value("${travelgo.pricing.lkr-estimates.source:}") String source,
            @Value("${travelgo.pricing.lkr-estimates.maximum-age-days:7}") int maximumAgeDays) {
        this.clock = clock;
        this.enabled = enabled;
        this.rate = parseRate(rate);
        this.asOf = parseDate(asOf);
        this.source = safeSource(source);
        this.maximumAgeDays = Math.min(Math.max(maximumAgeDays, 0), 7);
    }

    public boolean available() {
        if (!enabled || rate == null || asOf == null || source == null) return false;
        long age = ChronoUnit.DAYS.between(asOf, LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Colombo"))));
        return age >= 0 && age <= maximumAgeDays;
    }

    public String format(BigDecimal usd) {
        var converted = amount(usd);
        return converted == null ? null : "≈ LKR " + decimal(converted);
    }

    public BigDecimal amount(BigDecimal usd) {
        if (!available() || usd == null || usd.scale() > 8 || usd.precision() > 22 || usd.signum() < 0
                || usd.compareTo(new BigDecimal("999999999999.99")) > 0) return null;
        return usd.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    public String description() {
        return available() ? "1 USD = LKR " + decimal(rate) + " · rate dated " + asOf : null;
    }

    public BigDecimal getRate() { return available() ? rate : null; }
    public LocalDate getAsOf() { return available() ? asOf : null; }
    public String getSource() { return available() ? source : null; }

    private static BigDecimal parseRate(String value) {
        try {
            if (value == null || value.length() > 20) return null;
            var parsed = new BigDecimal(value.trim());
            return parsed.signum() > 0 && parsed.compareTo(new BigDecimal("1000000")) <= 0 && parsed.scale() <= 8 ? parsed : null;
        } catch (RuntimeException invalid) { return null; }
    }

    private static LocalDate parseDate(String value) {
        try { return value == null ? null : LocalDate.parse(value.trim()); }
        catch (RuntimeException invalid) { return null; }
    }

    private static String safeSource(String value) {
        try {
            if (value == null || value.length() > 500) return null;
            var uri = URI.create(value.trim());
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getRawUserInfo() == null && uri.getRawFragment() == null ? uri.toString() : null;
        } catch (RuntimeException invalid) { return null; }
    }

    private static String decimal(BigDecimal value) {
        var number = NumberFormat.getNumberInstance(Locale.US);
        number.setMinimumFractionDigits(2);
        number.setMaximumFractionDigits(2);
        return number.format(value);
    }
}

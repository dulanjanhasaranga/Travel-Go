package com.travelgo;

import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Fictional rate and isolated inherited workflow fixtures; never a live exchange-rate quote. */
@TestPropertySource(properties={"travelgo.pricing.lkr-estimates.enabled=true", "travelgo.pricing.lkr-estimates.rate=320",
        "travelgo.pricing.lkr-estimates.as-of=2030-01-01", "travelgo.pricing.lkr-estimates.source=https://www.cbsl.gov.lk/"})
class LkrEstimateMvcTests extends WorkflowIntegrationTests {
    @Test void catalogueAndPackageDetailKeepUsdAndOfferOptionalEstimates() throws Exception {
        for (var path : new String[]{"/", "/packages", "/packages/" + tour.getId(), "/destinations/" + tour.getDestination().getId()}) {
            mvc.perform(get(path)).andExpect(status().isOk())
                    .andExpect(content().string(containsString("Show LKR estimates")))
                    .andExpect(content().string(containsString("≈ LKR 32,000.00")))
                    .andExpect(content().string(containsString("Prices and payments are in USD")));
        }
        assertEquals(new BigDecimal("100.00"), packageRepo.findById(tour.getId()).orElseThrow().getBasePrice());
    }

    @Test void bookingCheckoutAndHistoryNeverConvertAmountsOrQuoteInputs() throws Exception {
        var booking = create(true);
        quote(booking);
        for (var path : new String[]{"/customer/bookings", "/customer/bookings/" + booking.getId(),
                "/customer/payments/checkout/" + booking.getId(), "/packages/" + tour.getId() + "/book"}) {
            mvc.perform(get(path).with(user(customer.getEmail()).roles("CUSTOMER")))
                    .andExpect(status().isOk()).andExpect(content().string(containsString("Show LKR estimates")));
        }
        mvc.perform(get("/customer/payments/checkout/" + booking.getId()).with(user(customer.getEmail()).roles("CUSTOMER")))
                .andExpect(content().string(containsString("≈ LKR 12,800.00")))
                .andExpect(content().string(containsString("name=\"amount\" value=\"40.00\"")));
        assertEquals(new BigDecimal("100.00"), bookings.findById(booking.getId()).orElseThrow().getTotalPackageAmount());
        assertTrue(payments.findByBookingId(booking.getId()).isEmpty());
    }

    @Test void staleRateDisappearsFromRenderedPagesWithoutChangingUsdPrices() throws Exception {
        clock.instant = clock.instant.plus(Duration.ofDays(8));
        mvc.perform(get("/packages/" + tour.getId())).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Show LKR estimates"))))
                .andExpect(content().string(not(containsString("≈ LKR"))))
                .andExpect(content().string(containsString("$100.00")));
    }
}

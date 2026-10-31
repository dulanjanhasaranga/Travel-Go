package com.travelgo;

import com.travelgo.entity.SystemSettings;
import com.travelgo.repository.SystemSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Saved brand settings must reach the rendered site without exposing unsafe legacy URLs. */
@SpringBootTest(properties = {"spring.config.import=", "spring.jpa.open-in-view=true"})
@ActiveProfiles("test")
@Import(TestDatabaseSafetyConfiguration.class)
@Transactional
class WebsiteBrandingMvcTests {
    @Autowired SystemSettingsRepository settings;
    @Autowired WebApplicationContext context;
    private MockMvc mvc;
    private SystemSettings brand;

    @BeforeEach void fixture() {
        brand = settings.findFirstByOrderByIdAsc().orElseGet(() -> settings.save(new SystemSettings()));
        brand.setWebsiteStatus("ACTIVE");
        brand.setCompanyName("Synthetic Travel Agency");
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test void savedLogoAndNameAppearInPublicAndAuthenticationViews() throws Exception {
        brand.setLogoUrl("/images/agency/approved-logo.png");
        settings.saveAndFlush(brand);
        for (String route : new String[] {"/", "/auth/login"}) {
            mvc.perform(get(route)).andExpect(status().isOk())
                .andExpect(content().string(containsString("src=\"/images/agency/approved-logo.png\"")))
                .andExpect(content().string(containsString("Synthetic Travel Agency")));
        }
    }

    @Test void unsafeHistoricalLogoFallsBackWithoutChangingItsStoredRecord() throws Exception {
        brand.setLogoUrl("javascript:unsafeHistoricalLogo");
        settings.saveAndFlush(brand);
        mvc.perform(get("/auth/login")).andExpect(status().isOk())
            .andExpect(content().string(containsString("src=\"/images/brand/travelgo-airborne.png\"")))
            .andExpect(content().string(not(containsString("javascript:unsafeHistoricalLogo"))));
        org.junit.jupiter.api.Assertions.assertEquals("javascript:unsafeHistoricalLogo", settings.findById(brand.getId()).orElseThrow().getLogoUrl());
    }
}

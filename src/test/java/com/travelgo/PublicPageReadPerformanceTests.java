package com.travelgo;

import com.travelgo.entity.SystemSettings;
import com.travelgo.repository.SystemSettingsRepository;
import jakarta.persistence.EntityManagerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;

/** Actual secured MVC requests, guarded H2 and Hibernate statement/transaction metrics. */
@SpringBootTest(properties={"spring.config.import=","spring.jpa.open-in-view=true",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
class PublicPageReadPerformanceTests extends WorkflowIntegrationTests {
    @Autowired EntityManagerFactory entityManagers;
    @Autowired SystemSettingsRepository settings;

    @Test void publicPageReadsHaveBoundedStatementsAndTransactions() throws Exception {
        agency();
        var stats=entityManagers.unwrap(SessionFactory.class).getStatistics();
        Map<String,long[]> limits=new LinkedHashMap<>();
        limits.put("/",new long[]{5,2});limits.put("/packages",new long[]{6,2});
        limits.put("/destinations",new long[]{4,2});limits.put("/auth/login",new long[]{2,1});
        Map<String,long[]> results=new LinkedHashMap<>();
        for(String route:limits.keySet()){
            stats.clear();mvc.perform(get(route).with(anonymous()).secure(true).with(request->{
                request.setServerName("travel-go.dulanjan-travelgo.prod.page");
                request.setRemoteAddr("198.51.100.22");return request;
            })).andExpect(status().isOk());
            results.put(route,new long[]{stats.getPrepareStatementCount(),stats.getTransactionCount()});
        }
        Path output=Path.of("target/page-read-query-counts.json");
        Files.writeString(output,new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(results));
        assertAll(results.entrySet().stream().map(entry->()->{
            long[] actual=entry.getValue(),limit=limits.get(entry.getKey());
            assertTrue(actual[0]<=limit[0],entry.getKey()+" statements="+actual[0]+", limit="+limit[0]);
            assertTrue(actual[1]<=limit[1],entry.getKey()+" transactions="+actual[1]+", limit="+limit[1]);
        }));
    }

    @Test void publicPageSettingsRemainFreshAcrossRequestsAndMaintenanceStillApplies() throws Exception {
        SystemSettings agency=agency();agency.setCompanyName("First synthetic agency");settings.saveAndFlush(agency);
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("First synthetic agency")));
        agency=settings.findById(agency.getId()).orElseThrow();agency.setCompanyName("Updated synthetic agency");agency.setWebsiteStatus("MAINTENANCE");settings.saveAndFlush(agency);
        mvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/maintenance"));
        mvc.perform(get("/auth/login")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Updated synthetic agency")));
        agency=settings.findById(agency.getId()).orElseThrow();agency.setWebsiteStatus("ACTIVE");settings.saveAndFlush(agency);
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Updated synthetic agency")));
    }

    private SystemSettings agency(){
        return settings.findFirstByOrderByIdAsc().orElseGet(()->{var value=new SystemSettings();value.setCompanyName("Synthetic page performance agency");return settings.saveAndFlush(value);});
    }
}

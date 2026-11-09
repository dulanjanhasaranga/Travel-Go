package com.travelgo.service;

import com.travelgo.entity.SystemSettings;
import com.travelgo.entity.TravelPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads the shared page metadata in one transaction, including first-run settings initialization. */
@Service
public class PageLayoutService {
    private final SystemSettingsService settings;
    private final TravelPolicyService policies;

    public PageLayoutService(SystemSettingsService settings, TravelPolicyService policies) {
        this.settings = settings;
        this.policies = policies;
    }

    @Transactional
    public Layout load() {
        return new Layout(settings.getSettings(), policies.current().orElse(null));
    }

    public record Layout(SystemSettings settings, TravelPolicy policy) { }
}

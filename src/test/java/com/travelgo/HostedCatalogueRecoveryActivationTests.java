package com.travelgo;

import com.travelgo.config.HostedPublicCatalogueRecovery;
import com.travelgo.service.PublicCatalogueRecoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HostedCatalogueRecoveryActivationTests {
    private final ApplicationContextRunner context=new ApplicationContextRunner()
            .withUserConfiguration(HostedPublicCatalogueRecovery.class)
            .withBean(PublicCatalogueRecoveryService.class,()->mock(PublicCatalogueRecoveryService.class));
    @Test void ordinaryLocalProfilesCannotActivateRecovery(){
        context.withPropertyValues("spring.profiles.active=test",
                "travelgo.cloud-evaluation.public-catalogue-recovery="+PublicCatalogueRecoveryService.VERSION)
                .run(value->assertFalse(value.containsBean("restoreReviewedPublicCatalogue")));
    }
    @Test void cloudReleaseRequiresTheExactReviewedRecoveryPlan(){
        for(String plan:new String[]{"", "true", "unreviewed-snapshot"})
            context.withPropertyValues("spring.profiles.active=cloud-evaluation","travelgo.cloud-evaluation.public-catalogue-recovery="+plan)
                    .run(value->assertFalse(value.containsBean("restoreReviewedPublicCatalogue")));
        context.withPropertyValues("spring.profiles.active=cloud-evaluation",
                "travelgo.cloud-evaluation.public-catalogue-recovery="+PublicCatalogueRecoveryService.VERSION)
                .run(value->assertTrue(value.containsBean("restoreReviewedPublicCatalogue")));
    }
}

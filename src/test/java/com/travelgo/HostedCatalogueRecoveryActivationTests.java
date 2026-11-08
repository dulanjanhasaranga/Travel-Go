package com.travelgo;

import com.travelgo.config.HostedPublicCatalogueRecovery;
import com.travelgo.service.PublicCatalogueRecoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HostedCatalogueRecoveryActivationTests {
    @Test void visibilityRepairRunsOnlyForItsExactPlanAndAfterRecovery() throws Exception {
        var service=mock(PublicCatalogueRecoveryService.class);
        when(service.restoreOnce()).thenReturn(new PublicCatalogueRecoveryService.Result(false,0));
        new HostedPublicCatalogueRecovery().restoreReviewedPublicCatalogue(service,"").run(new org.springframework.boot.DefaultApplicationArguments());
        verify(service,never()).repairImportedParisVisibility();clearInvocations(service);
        new HostedPublicCatalogueRecovery().restoreReviewedPublicCatalogue(service,"correct-restored-paris-20261108").run(new org.springframework.boot.DefaultApplicationArguments());
        var order=inOrder(service);order.verify(service).restoreOnce();order.verify(service).repairImportedParisVisibility();
    }
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

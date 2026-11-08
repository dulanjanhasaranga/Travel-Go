package com.travelgo.config;

import com.travelgo.service.PublicCatalogueRecoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Only the explicit, reviewed hosted recovery release invokes this operation. */
@Configuration(proxyBeanMethods=false)
@Profile("cloud-evaluation")
@ConditionalOnProperty(name="travelgo.cloud-evaluation.public-catalogue-recovery",havingValue=PublicCatalogueRecoveryService.VERSION)
public class HostedPublicCatalogueRecovery {
    private static final Logger log=LoggerFactory.getLogger(HostedPublicCatalogueRecovery.class);
    @Bean ApplicationRunner restoreReviewedPublicCatalogue(PublicCatalogueRecoveryService recovery){
        return args->{var result=recovery.restoreOnce();log.info("Public catalogue recovery {}: applied={}, inserted={}",
                PublicCatalogueRecoveryService.VERSION,result.applied(),result.insertedRecords());};
    }
}

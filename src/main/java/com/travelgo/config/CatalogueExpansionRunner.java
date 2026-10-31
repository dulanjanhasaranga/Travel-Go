package com.travelgo.config;
import com.travelgo.service.CatalogueExpansionService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
@Configuration
@Profile("!test & !mysql-test")
public class CatalogueExpansionRunner {
 @Bean @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="travelgo.catalogue.restore-original",havingValue="true")
 ApplicationRunner restoreOriginalCatalogue(com.travelgo.service.OriginalCatalogueService catalogue){return args->catalogue.install();}
 @Bean @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="travelgo.catalogue.install",havingValue="true")
 ApplicationRunner expandCatalogue(CatalogueExpansionService catalogue){return args->catalogue.install();}
}

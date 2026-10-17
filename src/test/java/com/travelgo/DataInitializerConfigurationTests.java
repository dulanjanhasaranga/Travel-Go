package com.travelgo;

import com.travelgo.config.DataInitializer;
import com.travelgo.repository.PermissionRepository;
import com.travelgo.repository.RoleRepository;
import com.travelgo.repository.SystemSettingsRepository;
import com.travelgo.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Checks startup selection with mocked collaborators; never opens a database connection. */
class DataInitializerConfigurationTests {
    private final RoleRepository roles=mock(RoleRepository.class);
    private final PermissionRepository permissions=mock(PermissionRepository.class);
    private final UserRepository users=mock(UserRepository.class);
    private final SystemSettingsRepository settings=mock(SystemSettingsRepository.class);
    private final PasswordEncoder passwords=mock(PasswordEncoder.class);

    private ApplicationContextRunner configuration() {
        return new ApplicationContextRunner()
                .withInitializer(context->context.getEnvironment().setActiveProfiles("mysql"))
                .withUserConfiguration(DataInitializer.class);
    }

    private ApplicationContextRunner withMockedDependencies() {
        return configuration()
                .withBean(RoleRepository.class,()->roles)
                .withBean(PermissionRepository.class,()->permissions)
                .withBean(UserRepository.class,()->users)
                .withBean(SystemSettingsRepository.class,()->settings)
                .withBean(PasswordEncoder.class,()->passwords);
    }

    @Test void explicitDisableExcludesSeederEvenWithoutRepositoryDependencies() {
        configuration().withPropertyValues("travelgo.data-initializer.enabled=false").run(context->{
            assertThat(context).hasNotFailed().doesNotHaveBean(DataInitializer.class);
            assertThat(context).doesNotHaveBean(RoleRepository.class);
        });
        verifyNoInteractions(roles,permissions,users,settings,passwords);
    }

    @Test void missingPropertyRetainsInitializerForExistingStartupCompatibility() {
        withMockedDependencies().run(context->{
            assertThat(context).hasNotFailed().hasSingleBean(DataInitializer.class);
            verifyNoInteractions(roles,permissions,users,settings,passwords);
        });
    }

    @Test void explicitEnableRetainsInitializerWithoutRunningDatabaseWorkInThisContext() {
        withMockedDependencies().withPropertyValues("travelgo.data-initializer.enabled=true").run(context->{
            assertThat(context).hasNotFailed().hasSingleBean(DataInitializer.class);
            verifyNoInteractions(roles,permissions,users,settings,passwords);
        });
    }

    @ParameterizedTest @ValueSource(strings={"test","mysql-test"})
    void explicitEnableCannotOverrideExistingTestProfileExclusions(String profile) {
        configuration().withInitializer(context->context.getEnvironment().setActiveProfiles(profile))
                .withPropertyValues("travelgo.data-initializer.enabled=true").run(context->{
                    assertThat(context).hasNotFailed().doesNotHaveBean(DataInitializer.class);
                });
    }
}

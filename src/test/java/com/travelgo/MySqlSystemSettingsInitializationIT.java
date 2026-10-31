package com.travelgo;

/** Repeats singleton initialization under MySQL's actual transaction/locking behavior on guarded synthetic data. */
@org.springframework.test.context.ActiveProfiles(value = "mysql-test", inheritProfiles = false)
@org.springframework.test.context.TestPropertySource(properties = "spring.datasource.url=jdbc:mysql://127.0.0.1:33317/travelgo_disposable_test?useSSL=false&allowPublicKeyRetrieval=true")
class MySqlSystemSettingsInitializationIT extends SystemSettingsInitializationTests { }

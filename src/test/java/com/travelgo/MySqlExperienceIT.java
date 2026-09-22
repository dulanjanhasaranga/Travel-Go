package com.travelgo;
/** Opt-in checks on the separate disposable MySQL server, never the application database. */
@org.springframework.test.context.ActiveProfiles(value="mysql-test", inheritProfiles=false)
public class MySqlExperienceIT extends ExperienceUpgradeTests {}

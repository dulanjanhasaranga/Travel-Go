package com.travelgo;

/** Explicit opt-in only: mvn -Dtest=MySqlWorkflowIT test, against the disposable local server. */
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.test.context.ActiveProfiles(value="mysql-test", inheritProfiles=false)
public class MySqlWorkflowIT extends WorkflowIntegrationTests {}

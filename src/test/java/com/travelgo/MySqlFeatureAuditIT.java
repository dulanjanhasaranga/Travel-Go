package com.travelgo;
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.test.context.ActiveProfiles(value="mysql-test", inheritProfiles=false)
public class MySqlFeatureAuditIT extends FeatureConnectivityAuditIT {}

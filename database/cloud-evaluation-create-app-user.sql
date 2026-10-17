-- Run once only after the guarded cloud-evaluation initializer has succeeded.
-- Connect with the temporary Aiven administration account, not from the web application.
-- Replace only the quoted password locally with a new private Base64 password. Do not
-- commit it, put it in a screenshot, or paste it into chat.

CREATE USER 'travelgo_demo_app'@'%' IDENTIFIED BY 'REPLACE_WITH_A_NEW_PRIVATE_PASSWORD';
GRANT SELECT, INSERT, UPDATE, DELETE ON `defaultdb`.* TO 'travelgo_demo_app'@'%';
SHOW GRANTS FOR 'travelgo_demo_app'@'%';

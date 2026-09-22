-- Run once on the intended local MySQL Server in Workbench before starting TravelGO.
-- Do not DROP an existing schema. Hibernate update preserves rows and creates missing tables.
CREATE DATABASE IF NOT EXISTS travelgo CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE travelgo;
SELECT DATABASE() AS selected_schema, @@port AS server_port, VERSION() AS mysql_version;

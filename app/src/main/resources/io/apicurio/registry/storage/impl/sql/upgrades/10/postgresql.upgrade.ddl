-- *********************************************************************
-- DDL for the Apicurio Registry - Database: PostgreSQL
-- Upgrades the DB schema from version 9 to version 10.
-- *********************************************************************

UPDATE apicurio SET prop_value = 10 WHERE prop_name = 'db_version';

ALTER TABLE artifactreferences DROP PRIMARY KEY , ADD PRIMARY KEY (tenantId, contentId, name, artifactId)

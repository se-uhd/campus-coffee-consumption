-- Provision the least-privilege database role the prod app runs as, instead of the Cloud SQL master
-- `postgres` superuser. Run this ONCE per instance as a superuser (e.g. the Cloud SQL `postgres` user)
-- against the application database before the first deploy:
--
--   psql "host=... user=postgres dbname=postgres" \
--     -v app_password="$(gcloud secrets versions access latest --secret=db-app-password)" \
--     -f scripts/sql/create-app-role.sql
--
-- The role is a plain LOGIN role with NO superuser, NO CREATEROLE, NO CREATEDB attributes. It is granted
-- exactly what the app and Flyway need: connect to the database, use and create within the `public` schema
-- (Flyway runs CREATE/ALTER/DROP DDL there), and full DML on current and future objects in that schema.
-- Keep the master `postgres` user for one-time provisioning and break-glass only; set DB_USERNAME to this
-- role for the running service so an app-process compromise cannot reach the rest of the instance.

\set app_role campus_coffee_app

-- :app_password is passed with `-v app_password=...` so the secret never lands in this committed file.
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'app_role', :'app_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'app_role')
\gexec

GRANT CONNECT ON DATABASE postgres TO :app_role;

-- Schema-level: the app role may resolve and create objects in `public` (Flyway migrations).
GRANT USAGE, CREATE ON SCHEMA public TO :app_role;

-- DML on every existing table and sequence in `public`.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO :app_role;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO :app_role;

-- And on everything Flyway creates later, so a new migration's tables/sequences are usable without a re-grant.
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :app_role;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO :app_role;

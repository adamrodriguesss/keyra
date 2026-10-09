---
name: new-migration
description: Create the next Flyway migration file for a schema change (new table, column, index, constraint, seed data). Use whenever the database schema needs to change.
---

Create a new Flyway migration for: $ARGUMENTS

1. List `src/main/resources/db/migration/` and find the highest existing `V<n>__*.sql` version (start at `V1` if empty).
2. Create `V<n+1>__<snake_case_description>.sql` (two underscores) in that directory.
3. Write PostgreSQL DDL. Use `bigint generated always as identity` or `uuid` primary keys consistent with existing tables, explicit `not null`/`unique`/FK constraints, and name constraints/indexes explicitly.
4. Never modify an existing migration file — if an earlier one is wrong, fix it forward in the new file.
5. Update/create the matching JPA entity in the `entity` package so its mappings match the DDL exactly (column names, nullability, lengths).
6. Run `./mvnw test -Dtest=KeyraApplicationTests` (needs Docker) to confirm Flyway applies cleanly and Hibernate validates against the schema.

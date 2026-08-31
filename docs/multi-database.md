# Supported databases

The starter targets **H2, MySQL/MariaDB, PostgreSQL and Microsoft SQL Server**. The reference
consumer (`rama-spring-demo`) has a profile per engine and its full integration suite runs green
against all four.

| Engine | Profile | Driver the consumer adds | Verified |
|---|---|---|---|
| H2 (in-memory) | `h2` (default) | `com.h2database:h2` | 50/50 |
| PostgreSQL 16 | `postgres` | `org.postgresql:postgresql` | 50/50 |
| MySQL 8.4 | `mysql` | `com.mysql:mysql-connector-j` | 50/50 |
| SQL Server 2022 | `mssql` | `com.microsoft.sqlserver:mssql-jdbc` | 50/50 |

The starter deliberately ships **no JDBC driver**. Add the one you use with `runtime` scope; Spring
Boot's dependency management already pins a version for all four.

## Running the demo against an engine

```bash
docker run -d --name rama-pg    -e POSTGRES_PASSWORD=R@ma2025 -e POSTGRES_DB=rama_demo -p 5432:5432 postgres:16-alpine
docker run -d --name rama-mysql -e MYSQL_ROOT_PASSWORD=R@ma2025 -e MYSQL_DATABASE=rama_demo -p 3306:3306 mysql:8.4
docker run -d --name rama-mssql -e ACCEPT_EULA=Y -e MSSQL_SA_PASSWORD=R@ma2025 -p 1433:1433 mcr.microsoft.com/mssql/server:2022-latest

mvn -pl rama-spring-demo spring-boot:run -Dspring-boot.run.profiles=postgres
mvn -pl rama-spring-demo -am verify -Dspring.profiles.active=mysql
```

`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME` and `DB_PASSWORD` override the connection without
editing a profile, e.g. `-DDB_PORT=55432`. SQL Server needs the database to exist first
(`CREATE DATABASE rama_demo`); the other three create it from the container's env.

The integration tests are not idempotent across runs — several insert rows behind a unique
constraint — so drop and recreate the schema between runs on a persistent engine.

## What the changelogs do per engine

Column types come from **one shared file**, `db/changelog/_dbms-types.yaml`, shipped in the starter
and included as the first entry of `rama-spring-starter-master.yaml`.

| Property | mssql | mysql/mariadb | postgresql | h2 |
|---|---|---|---|---|
| `${clobType}` | `nvarchar(max)` | `longtext` | `text` | `CLOB` |
| `${timestampType}` | `datetime2(7)` | `timestamp(6)` | `timestamp(6)` | `TIMESTAMP(6)` |

Two constraints drive those choices:

* **`clobType` must be an N-type on SQL Server.** Eight starter entities annotate the matching
  field `@Nationalized` (`Revision`, `UserConfig`, `ClientConfig`, `ClientUserConfig`, `SystemLog`,
  `MasterItem`, `MasterGroup`). Reading a `varchar` column through a nationalized mapping fails at
  runtime with *"The conversion from varchar to NCHAR is unsupported."*
* **`timestampType` must keep sub-second precision on MySQL.** MySQL's bare `timestamp` is
  whole-second. `GenericEntityService.updateEntity` detects a lost update by comparing the
  `updatedAt` the client echoed against the stored one (starter#41); at second granularity two
  writers inside the same second both read an unchanged value, both pass the check, and one write
  is lost.

### Two ordering rules, and both bite silently

> **1. Within the file: `dbms:`-scoped definitions come before the unscoped default.** Liquibase
> keeps the **first** definition of a property that is valid for the database it is running
> against, and discards later ones — including more specific ones. Backwards, you get the generic
> type on every engine and nothing is logged.

> **2. Across files: include `_dbms-types.yaml` first.** Parameters are global to a Liquibase run,
> so whichever definition is parsed first wins for everything parsed afterwards, across file
> boundaries. Include it later and it is silently ineffective for whatever was already parsed.

Consumers should put it at the very top of their own master changelog, ahead of the starter's:

```yaml
databaseChangeLog:
  - include: { file: db/changelog/rama-spring-dbms-types.yaml }   # first
  - include: { file: db/changelog/rama-spring-starter-master.yaml }
  - include: { file: db/changelog/yourTable.yaml }
```

That one line covers your tables as well as the starter's.

**Keep your own `_dbms-types.yaml` if you want to override anything** — include yours first and the
starter's values back off, same rule working in your favour. The two files coexist because the
names differ, and that is not cosmetic: Liquibase does not merge or shadow two changelogs that
resolve to the same classpath path, it refuses to start with
`Found 2 files with the path 'db/changelog/_dbms-types.yaml'`. ramaservice and his-service both ship
that filename already, which is why the starter's is `rama-spring-`-prefixed like every other
changelog it publishes. Anything new in that directory needs the prefix too.

Rule 2 is not hypothetical. Before starter#48 the definitions were inlined in each starter
changelog in the wrong order, and what happened to a consumer depended entirely on where it
included things:

* **ramaservice** was unaffected. Its `includeAll: db/changelog/upgrade/` is parsed *before*
  `rama-spring-starter-master.yaml`, and `upgrade/api.yaml` includes a correctly-ordered
  `_dbms-types.yaml` — so `nvarchar(max)` was registered first and won for the whole run, the
  starter's own tables included. The rule that caused the defect is what shielded it.
* **his-service** was not. Its copy is included from table changelogs at master lines 22+, long
  after the starter's block at line 10, so the generic types won for everything. It runs on MySQL,
  where the consequence is the timestamp precision loss rather than the SQL Server read failure.

`rama-spring-portability.changelog.yaml` widens MySQL timestamp columns in databases created before
starter#48. It carries no SQL Server half, for the reason above — no SQL Server database ever
received the wrong type. Fresh databases need neither: `_dbms-types.yaml` gets `CREATE TABLE` right.

## Quartz

The starter contributes `spring.quartz.job-store-type=jdbc` and the clustered `jobStore.*` defaults
through `RamaQuartzDefaultsEnvironmentPostProcessor` — an `EnvironmentPostProcessor`, not
`@PropertySource`, because Boot evaluates `QuartzAutoConfiguration.JdbcStoreTypeConfiguration`'s
`@ConditionalOnProperty` during configuration parsing, before any auto-configuration's
`@PropertySource` exists (starter#49).

Consumers still have to include `db/changelog/rama-spring-quartz.changelog.xml` in their master
changelog; the starter never creates `QRTZ_*` tables on its own.

* **PostgreSQL** stores `JOB_DATA` as `BYTEA` (the changelog's `blob_type` for that engine, since
  `BLOB` would mean an OID large object). Quartz's `StdJDBCDelegate` reads it with `getBlob`, so it
  needs `PostgreSQLDelegate` instead — the starter selects that automatically from
  `spring.datasource.url`. The symptom when it is missing is
  `Bad value for type long : \xaced0005…`.
* **H2 in PostgreSQL mode** rejects `BLOB`; set `spring.liquibase.parameters.blob_type=BYTEA` and
  the PostgreSQL delegate by hand.
* **In-memory scheduler**: `spring.quartz.job-store-type=memory` now works — the starter withholds
  its JDBC-only defaults, which `RAMJobStore` would reject with
  `NoSuchMethodException: No setter for property 'isClustered'`.
* `rama.quartz.apply-defaults=false` disables the whole contribution.

## Writing portable migrations

* **A unique index on a nullable column is not portable.** SQL Server treats NULLs as equal, so at
  most one row may have NULL, where H2, MySQL and PostgreSQL allow any number. Split the changeset
  by `dbms` and give SQL Server a filtered index — see `book.yaml` in the demo:

  ```yaml
  - changeSet:
      id: …-book-isbn-unique
      dbms: '!mssql'
      changes:
        - createIndex: { tableName: book, indexName: ux_book__isbn, unique: true, columns: [ { column: { name: isbn } } ] }
  - changeSet:
      id: …-book-isbn-unique-mssql
      dbms: mssql
      changes:
        - sql:
            sql: CREATE UNIQUE INDEX ux_book__isbn ON book (isbn) WHERE isbn IS NOT NULL
  ```

* **Carry `validCheckSum: ANY`** on any changeset that uses `${clobType}` or `${timestampType}`. The
  resolved value is part of the checksum, so changing what a property resolves to on one engine
  makes Liquibase refuse to run against schemas created earlier.

* **`modifyDataType` drops nullability** on both MySQL and SQL Server, and cannot restate it. Use
  raw `sql` with the full column definition when converting an existing column.

* **Prefer Liquibase's own type names** (`clob`, `timestamp`, `BIGINT`) over engine-specific ones:
  Liquibase already maps them per engine, and `timestamp` in particular is *not* what it looks like
  on SQL Server — Liquibase turns it into `datetime2`, but hand-written SQL saying `timestamp` gets
  `rowversion`.

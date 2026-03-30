# rama-spring-starter

Internal Spring Boot starter extracted from `ramaservice`.

Use this package when a service needs Rama platform entities, repositories, storage/API/template infrastructure, audit/revision support, and the standard shared Spring stack without copying app code.

## Quick Start

Build and install locally:

```bash
cd /Users/tantee/IdeaProjects/rama-spring-starter
mvn -DskipTests clean install
```

Add the consumer dependency:

```xml
<dependency>
    <groupId>org.rama.starter</groupId>
    <artifactId>rama-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Minimal config:

```properties
rama.liquibase.enabled=true
rama.document.gotenberg-server=http://localhost:3000
```

## Documentation

- [Package Overview](./docs/overview.md)
- [Consumer Manual](./docs/consumer-manual.md)
- [Extension Points](./docs/extension-points.md)
- [Publishing Guide](./docs/publishing.md)

## Modules

- `rama-spring-core`
- `rama-spring-autoconfigure`
- `rama-spring-boot-starter`

`rama-spring-boot-starter` is now the full internal application bundle. Consumer services should normally add this single dependency, then only add database drivers and app-specific libraries on top.

## Package Namespace

```text
org.rama.starter
```

## Included Scope

- `system*`, `api*`, `asset*`, `master*`, and `revision`
- repository base infrastructure
- generic entity/API/storage/template services
- audit and revision listeners
- shared helper classes and annotations
- optional Mongo support
- optional Meilisearch support
- optional GraphQL validation/scalar wiring
- starter-owned Liquibase changelogs
- bundled Spring application stack:
  - `spring-boot-starter`
  - `spring-boot-starter-web`
  - `spring-boot-starter-webflux`
  - `spring-boot-starter-data-jpa`
  - `spring-boot-starter-data-mongodb`
  - `spring-boot-starter-validation`
  - `spring-boot-starter-security`
  - `spring-boot-starter-graphql`
  - `spring-boot-starter-liquibase`
  - `spring-boot-starter-actuator`
  - `spring-cloud-starter-vault-config`

## Excluded Scope

- all `patient*`
- all `encounter*`
- hospital-specific workflow logic
- controllers and bootstrap
- patient/encounter GraphQL logic
- concrete patient/document Mongo and Meilisearch implementations
- jobs/listeners tied to hospital workflows
- `ApiFormUrlEncodedUtil`

## Verification

```bash
mvn -DskipTests verify
```

## Public Release Readiness

The project is now prepared for Maven Central style publishing:

- metadata and license are present in the parent POM
- source/javadoc/signing plugins are configured
- GitLab CI includes tag-driven Central publish preparation
- Liquibase changelogs are guarded so existing tables are marked ran instead of recreated
- release flow uses `v...` tags only

## Consumer Dependency Rule

For most Rama services:

- add `rama-spring-boot-starter`
- add your JDBC driver
- add app-specific libraries only

Typical example:

```xml
<dependencies>
    <dependency>
        <groupId>org.rama.starter</groupId>
        <artifactId>rama-spring-boot-starter</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </dependency>

    <dependency>
        <groupId>com.microsoft.sqlserver</groupId>
        <artifactId>mssql-jdbc</artifactId>
        <scope>runtime</scope>
    </dependency>
</dependencies>
```

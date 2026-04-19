# rama-spring-demo

Reference consumer of `rama-spring-boot-starter`. Serves two purposes:

1. **End-to-end integration tests** — `mvn verify` from the reactor root exercises the starter as a real Spring Boot 4 app would.
2. **Living documentation** — new consumers read this module to learn the canonical patterns: entity + repository, GraphQL CRUD, Liquibase, Quartz jobs, API-key auth, role-guarded mutations, `@TrackRevision`, `@EntityEvent`.

## Running locally

```bash
cd rama-spring-starter
mvn -pl rama-spring-demo spring-boot:run
```

GraphiQL at http://localhost:8080/graphiql. Zero infrastructure — boots on H2 in-memory.

Try a mutation in GraphiQL (set header `X-API-KEY: demo-admin-key`):

```graphql
mutation {
  createBook(input: {title: "Clean Code", author: "Uncle Bob", isbn: "9780132350884"}) {
    id title
  }
}
```

## Running tests

```bash
mvn -pl rama-spring-demo -am verify
```

Every test is an integration test (`*IT.java`, run by Failsafe). They boot Spring against H2.

## Features demonstrated

1. **`Book` entity + GraphQL CRUD** — `entity/book/Book.java`, `controller/book/BookController.java`, `graphql/book/book.graphqls`. Delegates to `GenericEntityService`.
2. **`@TrackRevision`** — `entity/book/BookReview.java`. Updates produce rows in the starter-managed `revision` table; test: `controller/book/BookReviewControllerIT.java`.
3. **Quartz job** — `job/BookAuditJob.java` extends `QuartzJobBean`; `DemoJobScheduler` registers it via `QuartzService` when `demo.jobs.book-audit.enabled=true` (Quartz auto-config is excluded in the H2 profile due to starter default JDBC properties; job is tested by direct invocation).
4. **API-key authentication** — `security/DemoSecurityConfig.java` wires the starter's `ApiKeyAuthFilter`. Seed migration `db/changelog/seed-apiKey.yaml` provides two demo keys (`demo-user-key`, `demo-admin-key`).
5. **Role-guarded mutation** — `archiveBook` in `BookController.java` checks `SecurityContext` for `ROLE_ADMIN`; `graphql/auth/demoAuth.graphqls` also declares `@auth(roles: ["ROLE_ADMIN"])` as schema documentation.
6. **`@EntityEvent`** — `Book` publishes `BookCreated`/`BookUpdated`; `listener/book/BookEventListener.java` consumes them.

## Optional: MSSQL profile

For devs who want to verify against prod DB instead of H2, start a local MSSQL, then:

```bash
mvn -pl rama-spring-demo spring-boot:run -Dspring-boot.run.profiles=mssql
```

Credentials come from `application-mssql.properties`.

## What this demo does NOT cover

- MongoDB sync (`rama.mongo.enabled`)
- Meilisearch sync (`rama.meilisearch.enabled`)
- Document template processing (DOCX to PDF via Gotenberg)
- FTP integration (`rama.ftp.enabled`)
- Base64 file upload via `StorageService`
- Keycloak / OAuth2 / Eureka / Vault

For production examples of these features, see `../ramaservice` and `../his-service`, or the starter's own unit tests under `rama-spring-core/src/test`.

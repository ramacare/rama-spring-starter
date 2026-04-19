# Demo Module Design

**Date:** 2026-04-16
**Branch origin:** `4-auth-graphql-directive`
**Status:** Approved design, awaiting implementation plan

## Context

`rama-spring-starter` is a multi-module Maven reactor that publishes `rama-spring-boot-starter` to GitHub Pages. Current modules: `rama-spring-core`, `rama-spring-autoconfigure`, `rama-spring-boot-starter`. Tests in the reactor today are all unit tests — nothing exercises the starter as a downstream Spring Boot application would.

Real consumers (`../ramaservice`, `../his-service`) prove the starter works in production, but they are large, private, and carry hospital-specific workflow. New contributors and CI both lack a small, self-contained consumer app they can point at.

## Goal

Add a `rama-spring-demo` module that:

1. Consumes the local-build starter (via reactor resolution), proving wiring end-to-end on every `mvn verify`.
2. Serves as a readable reference implementation for new consumers — a new teammate can skim it in 10 minutes and know how to scaffold their own service.
3. Showcases 6 representative starter features without dragging in infra (Mongo, Meilisearch, MinIO, Keycloak, Vault).

Non-goals: docker-compose, MongoDB/Meilisearch/MinIO/Gotenberg demos, document template processing, Keycloak/OAuth2, Eureka, Vault, frontend.

## Module layout & Maven wiring

New module at `rama-spring-demo/`, added to the root reactor:

```xml
<modules>
    <module>rama-spring-core</module>
    <module>rama-spring-autoconfigure</module>
    <module>rama-spring-boot-starter</module>
    <module>rama-spring-demo</module>
</modules>
```

`rama-spring-demo/pom.xml`:

- Parent: `org.rama:rama-spring-starter-parent:1.0.0-SNAPSHOT` (inherits Spring Boot 4.0.3 transitively)
- `<artifactId>rama-spring-demo</artifactId>`, `<packaging>jar</packaging>`
- Depends on `org.rama:rama-spring-boot-starter:${project.version}` — reactor resolves to current source, no local `mvn install` required
- Adds pieces the starter doesn't bundle: `querydsl-jpa-spring` + `querydsl-apt` (jakarta classifier), `com.h2database:h2` runtime, Lombok (optional + APT)
- Test-scope: `spring-boot-starter-test`, `spring-boot-starter-graphql-test`, `spring-boot-starter-security-test`
- Build plugins: `spring-boot-maven-plugin` (for `spring-boot:run`), `maven-compiler-plugin` with Lombok + Querydsl APT, `maven-failsafe-plugin` bound to `integration-test`/`verify`
- Publish guards in `<properties>`: `<maven.deploy.skip>true</maven.deploy.skip>`, `<maven.javadoc.skip>true</maven.javadoc.skip>`, `<maven.source.skip>true</maven.source.skip>` — the demo never ships to GitHub Pages
- Test split (mirrors `ramaservice/pom.xml`): Failsafe runs `*IT.java` on `mvn verify`; Surefire runs `*Test.java`. The demo has no `*Test.java` files — every test boots Spring, so every test is `*IT.java`. This means `mvn test` is fast and empty; `mvn verify` runs the full suite.

**Package namespace:** `org.rama.demo.*`. Lives under `org.rama.*` without colliding because the starter uses specific subpackages (`org.rama.entity.*`, `org.rama.service.*`, …). Demo code stays inside `org.rama.demo.entity`, `org.rama.demo.controller`, `org.rama.demo.repository`, etc.

## Application bootstrap

`src/main/java/org/rama/demo/DemoApplication.java`:

```java
@SpringBootApplication
@EntityScan(basePackages = {"org.rama.demo.entity", "org.rama.entity"})
@EnableJpaRepositories(
    basePackages = {"org.rama.demo.repository", "org.rama.repository"},
    repositoryBaseClass = BaseRepositoryImpl.class
)
@EnableAsync
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
```

No Mongo repos, no Eureka, no Vault — out of scope.

## Resources layout

```
src/main/resources/
  application.properties                 # default (h2 profile)
  application-mssql.properties           # optional MSSQL override for manual testing
  db/
    db.changelog-master.yaml             # demo master, includes starter master + demo tables
    changelog/
      book.yaml
      bookReview.yaml
      seed-apiKey.yaml                   # seeds demo API keys
  graphql/
    book/book.graphqls
    book/bookReview.graphqls
    auth/demoAuth.graphqls
```

## `application.properties` (H2 default)

```properties
spring.application.name=rama-spring-demo
spring.profiles.active=h2

spring.datasource.url=jdbc:h2:mem:demo;MODE=LEGACY;DB_CLOSE_DELAY=-1
spring.datasource.driver-class-name=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect
spring.jpa.hibernate.ddl-auto=none

spring.liquibase.enabled=true
spring.liquibase.change-log=classpath:/db/db.changelog-master.yaml
spring.liquibase.contexts=h2

spring.graphql.http.path=/graphql
spring.graphql.schema.locations=classpath*:graphql/**/
spring.graphql.graphiql.enabled=true

encrypt.key=1234567812345678

# Feature flags — turn off what isn't demoed to keep boot fast
rama.mongo.enabled=false
rama.meilisearch.enabled=false
rama.ftp.enabled=false
rama.api-key.enabled=true
rama.quartz.allowed-job-packages=org.rama.demo.job

# Quartz — in-memory for the demo (RAMJobStore)
spring.quartz.job-store-type=memory

# Demo-specific toggles
demo.jobs.book-audit.enabled=false
demo.jobs.book-audit.cron=0 0/5 * * * ?

server.port=8080
```

**Liquibase contexts:** `contexts=h2` keeps the starter's MSSQL-only tuning changesets (e.g. `006-mssql-physical-tuning.yaml`) dormant. The implementation plan's first step will audit whether those changesets already have `dbms:mssql` guards — if not, the plan adds that narrow fix upstream.

**Quartz:** `memory` job-store avoids needing `QRTZ_*` tables on H2. The starter's Quartz auto-config is compatible.

**`application-mssql.properties`** (optional, documented in README): MSSQL datasource + `spring.liquibase.contexts=mssql` + `spring.quartz.job-store-type=jdbc`. For devs verifying against prod DB.

## Features

Six features, numbered to match the brainstorming conversation (features 6 — file upload — skipped intentionally; starter unit tests already cover `StorageService`).

### Feature 1 — Book entity + GraphQL CRUD (+ feature 7 folded in)

Packages:

```
org.rama.demo.entity.book.Book
org.rama.demo.event.book.BookCreated        (implements IEntityEvent)
org.rama.demo.event.book.BookUpdated        (implements IEntityEvent)
org.rama.demo.repository.book.BookRepository
org.rama.demo.controller.book.BookController
org.rama.demo.listener.book.BookEventListener   (@EventListener, logs only)
```

`Book`: `@Entity @Data @NoArgsConstructor @RequiredArgsConstructor`, implements `Auditable`, `@EntityEvent(createdEvent = BookCreated.class, updatedEvent = BookUpdated.class)`. Fields:

- `id` — String UUID, generated in `@PrePersist` if caller didn't supply one
- `@NonNull title`
- `author`, `isbn` (unique), `publishedYear`
- `@Enumerated(EnumType.STRING) StatusCode statusCode = StatusCode.active`
- `@Embedded UserstampField userstampField = new UserstampField()`
- `@Embedded TimestampField timestampField = new TimestampField()`

`BookRepository extends BaseRepository<Book, String>, SoftDeleteRepository<Book, String>, QuerydslPredicateExecutor<Book>`, annotated `@GraphQlRepository` so Spring GraphQL auto-generates a `books(filter: ...)` query binding.

`BookController`: `@Controller @RequiredArgsConstructor`, three `@MutationMapping`s (`createBook`, `updateBook`, `deleteBook`) delegating to `GenericEntityService` (instance methods — Spring bean). `deleteBook` uses `hardDeleteEntity` to show the full CRUD path; soft-delete is implicit on reads via `SoftDeleteRepository.withoutTerminated()`. A fourth mutation `archiveBook` (feature 5 below) soft-deletes with `@auth` guard.

`BookEventListener`: `@Component` with two `@EventListener` methods that log `"Book created: {id}"` / `"Book updated: {id}"`. Exists to prove the `@EntityEvent` machinery fires after commit.

**GraphQL schema** (`graphql/book/book.graphqls`):

```graphql
extend type Query {
    book(id: ID!): Book
    books(filter: BookFilter): [Book!]!
}
extend type Mutation {
    createBook(input: BookInput!): Book
    updateBook(input: BookInput!): Book
    deleteBook(input: BookDeleteInput!): Book
}
type Book {
    id: ID!
    title: String!
    author: String
    isbn: String
    publishedYear: Int
    statusCode: StatusCode
    createdAt: DateTime
    createdBy: String
}
input BookInput { id: ID, title: String!, author: String, isbn: String, publishedYear: Int }
input BookDeleteInput { id: ID! }
input BookFilter { title: String, author: String }
```

**Liquibase** `db/changelog/book.yaml`: `NOT tableExists` pre-condition, columns matching the entity, unique index `ux_book__isbn`, index `ix_book__status_code`, timestamp columns using `${timestampType}`.

### Feature 2 — BookReview with `@TrackRevision`

Packages:

```
org.rama.demo.entity.book.BookReview
org.rama.demo.repository.book.BookReviewRepository
org.rama.demo.controller.book.BookReviewController
```

`BookReview`: same `Auditable` pattern, plus `@TrackRevision`. Fields:

- `id` (String UUID)
- `@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "book_id") Book book`
- `reviewer`, `rating` (1-5), `comment` (CLOB)
- `StatusCode`, `UserstampField`, `TimestampField`

Repository and controller mirror Book. Controller exposes `createBookReview` and `updateBookReview` only (mutations are what trigger revision rows — no delete needed).

`bookReview.yaml` migration: FK to `book.id`, index `ix_book_review__book_id`, `comment` column uses `${clobType}`. Revision rows land in the starter-managed `revision` table (already created by `rama-spring-starter-master.yaml`).

### Feature 3 — Quartz `SmartSyncJob` over Books

Packages:

```
org.rama.demo.job.BookAuditJob
org.rama.demo.job.DemoJobScheduler          (ApplicationRunner)
```

`BookAuditJob extends SmartSyncJob`, `@Component`, iterates `Book` rows paginated via Querydsl (`JPAQueryFactory.selectFrom(QBook.book).orderBy(QBook.book.id.asc())`) and logs `"Audited book: {id} — {title}"`. Job body is intentionally trivial; the goal is to prove the Quartz wiring path, not real work.

`DemoJobScheduler implements ApplicationRunner`, injects `QuartzService`. On startup, if `demo.jobs.book-audit.enabled=true`, schedules `BookAuditJob` with the cron from `demo.jobs.book-audit.cron`. Default is `false` — running the demo locally shouldn't spam logs; tests enable it via property override.

`rama.quartz.allowed-job-packages=org.rama.demo.job` in `application.properties` lets the starter's allowlist accept the job.

### Feature 4 — API key authentication

Package:

```
org.rama.demo.security.DemoSecurityConfig
```

Starter's `RamaStarterSecurityAutoConfiguration` backs off when a consumer defines their own `SecurityFilterChain`. We want the starter's API-key filter anyway, so we declare our own chain that explicitly wires `ApiKeyAuthFilter`:

```java
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class DemoSecurityConfig {
    private final ApiKeyAuthFilter apiKeyAuthFilter;

    @Bean
    SecurityFilterChain filter(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(a -> a
                .requestMatchers("/graphiql", "/graphql").permitAll()
                .requestMatchers("/actuator/**").permitAll()
                .anyRequest().authenticated())
            .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

GraphQL endpoint is `permitAll` at the HTTP layer; the `@auth` directive enforces per-mutation roles (matches `ramaservice/.../WebSecurityConfig.java` pattern).

**Seed** `db/changelog/seed-apiKey.yaml`: inserts rows into the starter's `api_key` table:

- `demo-user-key` with roles `ROLE_USER`
- `demo-admin-key` with roles `ROLE_USER,ROLE_ADMIN`

Wrapped in `context: h2` so MSSQL profile can seed differently if needed.

### Feature 5 — `@auth` GraphQL directive

Adds one admin-guarded mutation to exercise the directive from branch `4-auth-graphql-directive`:

```graphql
# graphql/auth/demoAuth.graphqls
extend type Mutation {
    archiveBook(input: BookDeleteInput!): Book @auth(role: "ROLE_ADMIN")
}
```

Controller method lives in `BookController` (one-liner, avoids a separate class):

```java
@MutationMapping
public Optional<Book> archiveBook(@Argument BookDeleteInput input) {
    return genericEntityService.deleteEntity(
        Book.class, bookRepository, Map.of("id", input.id()), "id");
}
```

(`deleteEntity` = soft-delete via `StatusCode.terminated`.)

### Feature 7 — `@EntityEvent`

Already folded into feature 1 via `Book`'s `@EntityEvent(createdEvent = BookCreated.class, updatedEvent = BookUpdated.class)` and `BookEventListener`. No separate entity.

## Testing strategy

Test layout (`src/test/java/org/rama/demo/`):

```
DemoApplicationTests.java                      # @SpringBootTest smoke — context loads
controller/book/BookControllerIT.java          # CRUD + @EntityEvent listener assertion
controller/book/BookReviewControllerIT.java    # revision rows land in `revision` table
job/BookAuditJobIT.java                        # invokes executeInternal, asserts query hit
security/ApiKeyAuthIT.java                     # missing/invalid key → 401; valid key → 200
security/AuthDirectiveIT.java                  # role-based access over the @auth directive
```

**Rules**

- All tests are `@Tag("integration")` and named `*IT.java` (picked up by Failsafe). The demo has zero unit tests — starter already has unit coverage; the demo's value is end-to-end wiring proof.
- `@SpringBootTest` against the H2 profile. `@ActiveProfiles("h2")` kept explicit even though it's default.
- `@Transactional` at class level for rollback — except tests observing post-commit behavior (revision, `@EntityEvent`), which use `TransactionTemplate` to commit inside the test method.
- GraphQL tests use `HttpGraphQlTester` from `spring-boot-starter-graphql-test` — hits the real `/graphql` endpoint over MockMvc, exercising the full filter chain.
- Naming: `methodName_whenCondition_shouldOutcome`. AssertJ assertions.

**Post-commit event test gotcha:** `BookControllerIT` asserts `BookEventListener` fired after commit. Uses `@RecordApplicationEvents` plus `TransactionTemplate.execute` to wrap the create in a committed transaction, then asserts `ApplicationEvents#stream(BookCreated.class)` contains the event.

**Revision test:** `BookReviewControllerIT` creates a Book + BookReview, updates the review, commits via `TransactionTemplate`, then asserts `revisionRepository.findByEntityNameAndEntityId("BookReview", id)` returns two rows with the correct `actionType` (INSERT + UPDATE).

## Documentation

`rama-spring-demo/README.md` (~100 lines):

1. **What this is** — "Demo consumer of rama-spring-boot-starter. Run with `mvn -pl rama-spring-demo spring-boot:run`; browse GraphiQL at `http://localhost:8080/graphiql`."
2. **Running tests** — `mvn verify -pl rama-spring-demo -am`
3. **Each feature** — one paragraph per feature, linking to the controller/entity/test class.
4. **MSSQL profile** — activation instructions for devs who want to verify against prod DB.
5. **What this demo doesn't cover** — MongoDB sync, Meilisearch, document templates, FTP. Points at starter unit tests and `../his-service` / `../ramaservice`.

**Root docs updates**

- `CLAUDE.md` — add `rama-spring-demo` to the Modules list; add a commands block: `mvn -pl rama-spring-demo spring-boot:run`, `mvn -pl rama-spring-demo -am verify`.
- Root `README.md` — one sentence in the intro mentioning the demo module.

## CI

Out of scope for this task. The demo ships as a Maven module in the default reactor; existing CI picks it up via `mvn verify`. No new GitHub Actions workflow, no Testcontainers, no Docker.

## Out of scope (explicit)

- Docker / docker-compose
- MongoDB, Meilisearch, MinIO, Gotenberg
- Document template processing
- Keycloak / OAuth2
- Eureka / Vault
- Frontend (GraphiQL is the UI)

## Open items for the implementation plan

1. **Liquibase MSSQL-only changesets:** audit `rama-spring-autoconfigure/src/main/resources/db/changelog/*.yaml` for changesets that break on H2. Either wrap them in `dbms:mssql` preconditions upstream, or rely on `spring.liquibase.contexts=h2` skipping them. First plan step.
2. **`StatusCode` scalar / enum in GraphQL:** verify the starter already registers `StatusCode` as a GraphQL enum type; if not, add a one-off schema snippet to the demo.
3. **`DateTime` scalar:** confirm starter's GraphQL scalar registration covers `DateTime` or `LocalDateTime`. If not, swap schema to `String` ISO format.
4. **`@PrePersist` UUID generator:** decide whether to put it on `Book` directly (one-off) or extract a small `@IdentifierGenerator`-style helper in the demo. Default: inline `@PrePersist` for readability.

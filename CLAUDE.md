# CLAUDE.md

This file provides guidance to Claude Code when working with the rama-spring-starter repository.

## Commands

### Build
```bash
mvn clean install              # Build and install to local Maven cache
mvn compile                    # Compile only
mvn -DskipTests verify         # Verify without tests
```

### Testing
```bash
mvn test                       # Run all tests
mvn test -pl rama-spring-core  # Run tests for core module only
```

### Publishing
Publishing happens via GitHub Actions on tag push. See docs/publishing.md.

## Architecture

**Reusable Spring Boot 4.0.3 starter** for the Rama healthcare platform. Multi-module Maven project under `org.rama` package.

### Demo module
```bash
mvn -pl rama-spring-demo spring-boot:run            # Run the demo app locally (GraphiQL at :8080)
mvn -pl rama-spring-demo -am verify                 # Run the demo's integration tests
```

### Modules
- `rama-spring-core` -- Runtime code: entities, repositories, services, utilities
- `rama-spring-autoconfigure` -- Spring Boot auto-configuration, properties, bean wiring
- `rama-spring-boot-starter` -- Consumer-facing dependency bundle (includes full Spring stack)
- `rama-spring-demo` -- Reference consumer app + end-to-end integration tests (not published)

### Key Packages (`org.rama.*`)
- `entity` -- Base types (`Auditable`, `StatusCode`, `Response`), domain entities (`api`, `asset`, `master`, `security`, `system`)
- `event` -- Entity lifecycle events: `EntityCreated`, `EntityUpdated`, `IEntityEvent`; triggered via `@EntityEvent` annotation
- `repository` -- `BaseRepository<T,ID>`, `SoftDeleteRepository`, domain repositories
- `service` -- `GenericEntityService`, `GenericApiService`, `StorageService`, `RevisionService`, `EntityEventService`, `TransactionRunnerService`, `CertificateService`, `VaultService`
- `service.document` -- Document processing: `PdfService`, `BarcodeService`, `ImageService`, `AbstractSignService`
- `service.document.template` -- DOCX template processing: `DocxTemplateProcessor`, `ReplacementProcessor`
- `service.document.replacement` -- `ReplacementObjectHook`, `ReplacementStringHook`, `ReplacementHooks`
- `service.document.transformers` -- `ReplacementTransformer`
- `service.document.printTemplate` -- `TemplatePreprocessor`
- `ftp` -- FTP integration: `FtpService`, `FtpConnection`, `FtpConnectionManager`, `FtpProperties`
- `security` -- API key authentication: `ApiKeyService`, `ApiKeyAuthFilter`, `ApiKey` entity
- `mongo` -- MongoDB sync: `IndexAwareMongoTemplate`, `MongoSyncService`, listeners
- `meilisearch` -- Meilisearch sync: `MeilisearchService`, listeners, mappers
- `util` -- Shared utilities: `DateTimeUtil`, `EncryptionUtil`, `QueryUtil`, `NumberUtil`, etc.
- `autoconfigure` -- `RamaStarterAutoConfiguration`, `RamaStarterSecurityAutoConfiguration`, `RamaStarterProperties`

### Entity Pattern
Every JPA entity implements `Auditable` and embeds `UserstampField` + `TimestampField`. Use `@Data @NoArgsConstructor` from Lombok. Use `StatusCode` enum for lifecycle state.

### Repository Pattern
All repositories extend `BaseRepository<T, ID>`. Add `SoftDeleteRepository` for soft-delete with `withoutTerminated()`. Add `QuerydslPredicateExecutor` for filtering.

### Global Hibernate Listener Pattern
All global Hibernate event listeners (revision, mongo sync, meilisearch) follow the same pattern:
- **Listener** handles `TransactionSynchronization.afterCommit` directly (not the service)
- **Listener** extracts data from the Hibernate event, then calls the service's `@Async @Transactional` method through the Spring proxy
- **Service** provides data extraction helpers and the `@Async @Transactional` save/sync method
- `requiresPostCommitHandling()` returns `false` — the listener manages post-commit via Spring's `TransactionSynchronizationManager`

This avoids self-invocation in the service (calling `this.method()` bypasses the CGLIB proxy, so `@Async` and `@Transactional` would not activate).

### Auto-Configuration
Most beans are registered with `@ConditionalOnMissingBean`. Consumer applications can override any starter bean.

**Never put a bean-inspecting condition on a nested `@Configuration`.** `@ConditionalOnBean` and
`@ConditionalOnMissingBean` are `REGISTER_BEAN`-phase conditions. `ConfigurationClassParser` calls
`processMemberClasses` at the top of `doProcessConfigurationClass` but only records the class itself
afterwards, so a nested member configuration is evaluated *ahead* of the `@Bean` methods around it —
against a bean factory that may not hold the definitions it is asking about. When that happens the
whole class is skipped: every bean in it vanishes with no error, no warning and no log line. This cost
us `@IdempotentMutation` silently guarding nothing in production (starter#46).

The rule covers the **whole nested class, its `@Bean` methods included** — not just the class-level
annotation. starter#47 was the same defect one level down: after #46 removed the class-level condition,
two `@ConditionalOnBean(Scheduler.class)` gates on `@Bean` methods *inside* that nested class still
never matched, so the idempotency cleanup job was never scheduled and `system_request_dedup` grew
without bound. `@AutoConfiguration(afterName = ...)` did not save it — the condition runs before the
ordering applies. Moving a condition from the nested class onto its own `@Bean` methods is not a fix.

So: inside a nested member `@Configuration`, use only `PARSE_CONFIGURATION`-phase conditions —
`@ConditionalOnProperty`, `@ConditionalOnClass`, or a custom `ConfigurationCondition` that declares the
phase (see `RamaIdempotencyCorsAutoConfiguration.OnAugmenterEligible`). Note `@ConditionalOnMissingBean`
is the same `REGISTER_BEAN` phase as `@ConditionalOnBean` and fails the other way — matching too eagerly
and shadowing a consumer's override.

Anything that must inspect the bean factory belongs on a `@Bean` method of a **top-level**
auto-configuration. When the bean being waited on is registered by the *consumer* — any
`org.rama.repository.*` repository, which only ever arrives via their
`@EnableJpaRepositories(basePackages = {..., "org.rama.repository"})` — prefer `ObjectProvider` over a
condition, and fail loudly rather than backing off silently. `@AutoConfiguration(afterName = ...)` does
not help there either: it orders auto-configurations against each other, and a consumer's registrar is
not one.

Jackson mappers are framed in the JVM default time zone, not Jackson's built-in UTC default. Every starter service injects **Boot's managed `JsonMapper`** (`jacksonJsonMapper`), framed via the `ramaStarterTimeZoneCustomizer` bean; the static mappers in `JsonConverter` and `JsonEncryptConverter` are framed the same way. `CanonicalJson` is deliberately pinned to UTC so idempotency hashes stay stable across deployments. `spring.jackson.time-zone` overrides the default — the starter's customizer is ordered `HIGHEST_PRECEDENCE` so Boot's own customizer runs after it and wins. Note `ramaStarterObjectMapper` is a fallback that does **not** register when Boot's Jackson auto-config is present; override `JsonMapper` to replace what the services use. See starter#39.

**Feature flags** (all prefixed with `rama.`, default `true`):
- `rama.jpa.enabled` -- JPA entity scanning
- `rama.static-values.enabled` -- Static value resolver
- `rama.revision.enabled` -- Revision/audit trail
- `rama.mongo.enabled` -- MongoDB sync
- `rama.mongo.deferred-indexes-enabled` -- MongoDB deferred index creation. When on, `IndexAwareMongoTemplate` is registered `@Primary` so all Mongo access is tracked; `DeferredIndexManager` runs its own flush thread (no consumer `@EnableScheduling` needed)
- `rama.mongo.deferred-index-threshold` -- Cumulative uses of a field-set before its index is created (default `100`)
- `rama.mongo.deferred-index-flush-interval` -- How often accumulated usage is examined (default `10m`; `0` disables the flush)
- `rama.meilisearch.enabled` -- Meilisearch sync
- `rama.meilisearch.initialize-indexes` -- Meilisearch index auto-initialization
- `rama.graphql.enabled` -- GraphQL scalars and directives
- `rama.liquibase.enabled` -- Starter fallback Liquibase migrations. The starter's `ramaStarterLiquibase` bean is guarded with `@ConditionalOnMissingBean(SpringLiquibase.class)` and only runs when no Spring Boot `liquibase` bean exists. When `spring.liquibase.change-log` is set, Spring Boot's default runs the consumer's master changelog (which must `<include>` `rama-spring-starter-master.yaml` and optionally `rama-spring-quartz.changelog.xml`). Set to `false` to disable the fallback entirely
- `rama.liquibase.change-log` -- Changelog path for the fallback bean (default: `classpath:/db/changelog/rama-spring-starter-master.yaml`). Only used when the fallback bean is active
- `rama.ftp.enabled` -- FTP connection manager (default `false`)
- `rama.security.api-key.enabled` -- API key authentication filter

**Quartz properties** (Spring Boot, not `rama.` prefix):
- `spring.quartz.enabled` -- Enable/disable Quartz entirely (default `true`). Set to `false` to skip Quartz auto-config, `SchedulerController`, and `QuartzService`. Quartz schema (QRTZ_*) is NOT auto-created by the starter — consumers using the JDBC job store should `<include>` `db/changelog/rama-spring-quartz.changelog.xml` in their master changelog
- The starter provides sensible defaults via `rama-quartz-defaults.properties`: JDBC job-store, clustered mode, `QRTZ_` table prefix, 5 threads. Consumers can override any of these in their `application.properties`
- `SchedulerController` is conditionally loaded only when `QuartzService` bean exists (which requires a `Scheduler` bean from Quartz auto-config)

**Connection/service properties** (no `rama.` prefix):
- `meilisearch.host`, `meilisearch.master-key` -- Meilisearch connection
- `minio.endpoint`, `minio.access-key`, `minio.secret-key` -- MinIO connection
- `encrypt.key` -- AES encryption key
- `document.*` -- Document processing (Gotenberg server, patterns)
- `app.file-storage-path`, `app.file-storage-location` -- File storage config
- `rama.ftp.host`, `rama.ftp.port`, `rama.ftp.username`, `rama.ftp.password` -- FTP connection

### Encryption
`EncryptionUtil` provides AES/CBC encryption. Used directly by `Encrypt` and `JsonEncryptConverter` JPA converters. Key set via `encrypt.key` property.

## Code Patterns

- Use Lombok (`@Data`, `@NoArgsConstructor`, `@Builder`, `@RequiredArgsConstructor`) for all entities and DTOs
- GraphQL controllers use `@Controller` (not `@RestController`) with `@MutationMapping`/`@QueryMapping`
- Mutations delegate to `GenericEntityService.createEntity()` / `updateEntity()` / `deleteEntity()`
- Use `@EntityEvent` annotation on entities to auto-publish `EntityCreated` / `EntityUpdated` events via `EntityEventService`
- API key authentication via `ApiKeyAuthFilter` — keys stored in `api_key` table, validated by `ApiKeyService`
- Liquibase migrations in `rama-spring-autoconfigure/src/main/resources/db/changelog/`
- Tests use JUnit 5 with `@Tag("unit")` or `@Tag("integration")`

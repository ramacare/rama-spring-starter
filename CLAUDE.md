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

**Feature flags** (all prefixed with `rama.`, default `true`):
- `rama.jpa.enabled` -- JPA entity scanning
- `rama.static-values.enabled` -- Static value resolver
- `rama.revision.enabled` -- Revision/audit trail
- `rama.mongo.enabled` -- MongoDB sync
- `rama.mongo.deferred-indexes-enabled` -- MongoDB deferred index creation
- `rama.meilisearch.enabled` -- Meilisearch sync
- `rama.meilisearch.initialize-indexes` -- Meilisearch index auto-initialization
- `rama.graphql.enabled` -- GraphQL scalars and directives
- `rama.liquibase.enabled` -- Starter Liquibase migrations
- `rama.ftp.enabled` -- FTP connection manager (default `false`)
- `rama.security.api-key.enabled` -- API key authentication filter

**Quartz properties** (Spring Boot, not `rama.` prefix):
- `spring.quartz.enabled` -- Enable/disable Quartz entirely (default `true`). Set to `false` to skip Quartz auto-config, `SchedulerController`, `QuartzService`, and QRTZ_* Liquibase migration
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

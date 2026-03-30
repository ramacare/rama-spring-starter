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

### Modules
- `rama-spring-core` -- Runtime code: entities, repositories, services, utilities
- `rama-spring-autoconfigure` -- Spring Boot auto-configuration, properties, bean wiring
- `rama-spring-boot-starter` -- Consumer-facing dependency bundle (includes full Spring stack)

### Key Packages (`org.rama.*`)
- `entity` -- Base types (`Auditable`, `StatusCode`, `Response`), domain entities (`api`, `asset`, `master`, `system`)
- `repository` -- `BaseRepository<T,ID>`, `SoftDeleteRepository`, domain repositories
- `service` -- `GenericEntityService`, `StorageService`, `RevisionService`, domain services
- `service.document` -- Document processing: `PdfService`, `BarcodeService`, `ImageService`, `AbstractSignService`
- `service.document.template` -- DOCX template processing: `DocxTemplateProcessor`, `ReplacementProcessor`
- `service.document.replacement` -- `ReplacementObjectHook`, `ReplacementStringHook`, `ReplacementHooks`
- `service.document.transformers` -- `ReplacementTransformer`
- `service.document.printTemplate` -- `TemplatePreprocessor`
- `mongo` -- MongoDB sync: `IndexAwareMongoTemplate`, `MongoSyncService`, listeners
- `meilisearch` -- Meilisearch sync: `MeilisearchService`, listeners, mappers
- `util` -- Shared utilities: `DateTimeUtil`, `EncryptionUtil`, `QueryUtil`, etc.
- `autoconfigure` -- `RamaStarterAutoConfiguration`, `RamaStarterProperties`

### Entity Pattern
Every JPA entity implements `Auditable` and embeds `UserstampField` + `TimestampField`. Use `@Data @NoArgsConstructor` from Lombok. Use `StatusCode` enum for lifecycle state.

### Repository Pattern
All repositories extend `BaseRepository<T, ID>`. Add `SoftDeleteRepository` for soft-delete with `withoutTerminated()`. Add `QuerydslPredicateExecutor` for filtering.

### Auto-Configuration
Most beans are registered with `@ConditionalOnMissingBean`. Consumer applications can override any starter bean. Feature flags: `rama.jpa.enabled`, `rama.mongo.enabled`, `rama.meilisearch.enabled`, `rama.revision.enabled`, `rama.graphql.enabled`.

### Encryption
`EncryptionUtil` provides AES/CBC encryption. Used directly by `Encrypt` and `JsonEncryptConverter` JPA converters. Key set via `rama.encryption.key` property.

## Code Patterns

- Use Lombok (`@Data`, `@NoArgsConstructor`, `@Builder`, `@RequiredArgsConstructor`) for all entities and DTOs
- GraphQL controllers use `@Controller` (not `@RestController`) with `@MutationMapping`/`@QueryMapping`
- Mutations delegate to `GenericEntityService.createEntity()` / `updateEntity()`
- Liquibase migrations in `rama-spring-autoconfigure/src/main/resources/db/changelog/`
- Tests use JUnit 5 with `@Tag("unit")` or `@Tag("integration")`

# Architecture Overview

## Package Namespace

All code lives under `org.rama`. Consumer applications use the same base package.

## Module Structure

```
rama-spring-starter-parent (pom)
  |- rama-spring-core           (runtime code)
  |- rama-spring-autoconfigure  (Spring Boot wiring)
  |- rama-spring-boot-starter   (consumer dependency bundle)
```

## Package Map (`org.rama.*`)

### Annotations
- `annotation` -- `@TrackRevision`, `@SyncToMongo`, `@SyncToMeilisearch`, `@TransformableMap`, `@ApiReturnType`

### Entities
- `entity` -- Base types: `Auditable`, `StatusCode`, `Response`, `PageableDTO`, `TimestampField`, `UserstampField`, converters (`JsonConverter`, `Encrypt`)
- `entity.api` -- `Api`, `ApiHeaderSet`
- `entity.asset` -- `AssetFile`
- `entity.master` -- `MasterGroup`, `MasterId`, `MasterItem`
- `entity.system` -- `SystemLog`, `SystemParameter`, `SystemTemplate`, `ClientConfig`

### Repositories
- `repository` -- `BaseRepository<T,ID>`, `BaseRepositoryImpl`, `SoftDeleteRepository`
- `repository.api` -- `ApiRepository`, `ApiHeaderSetRepository`
- `repository.asset` -- `AssetFileRepository`
- `repository.master` -- `MasterGroupRepository`, `MasterIdRepository`, `MasterItemRepository`
- `repository.revision` -- `RevisionRepository`
- `repository.system` -- `SystemLogRepository`, `SystemParameterRepository`, `SystemTemplateRepository`, `ClientConfigRepository`

### Services
- `service` -- `GenericEntityService`, `GenericApiService`, `GenericMongoService`, `StorageService`, `StorageProvider`, `VaultService`, `RevisionService`
- `service.master` -- `MasterIdService`, `MasterItemService`
- `service.system` -- `QuartzService`, `SystemLogService`, `SystemParameterService`, `ClientConfigService`
- `service.environment` -- `EnvironmentService`, `StaticValueService`, `StaticValueResolver`

### Document Processing
- `service.document` -- `PdfService`, `BarcodeService`, `BarcodeReaderService`, `ImageService`, `AbstractSignService`, `SigningMaterial`, `VerificationCodeService`
- `service.document.template` -- `TemplateProcessor`, `DocxTemplateProcessor`, `DocxTemplatePreprocessor`, `ReplacementProcessor`
- `service.document.template.docx` -- `ReplacePlaceholder`, `ReplaceSection`, `DocxTemplateHelper`
- `service.document.template.hooks` -- `MasterHooks`, `DatetimeHooks`, `GeneralHooks`, `CheckBoxHooks`, `JoinHooks`, `StringArrayHooks`
- `service.document.printTemplate` -- `TemplatePreprocessor`
- `service.document.replacement` -- `ReplacementHooks`, `ReplacementObjectHook`, `ReplacementStringHook`
- `service.document.transformers` -- `ReplacementTransformer`

### Controllers (GraphQL)
- `controller.api` -- `ApiController`, `ApiHeaderSetController`
- `controller.asset` -- `AssetFileController`
- `controller.master` -- `MasterGroupController`, `MasterIdController`, `MasterItemController`
- `controller.system` -- `ClientConfigController`, `SchedulerController`, `SystemLogController`, `SystemParameterController`, `SystemTemplateController`
- `controller` -- `RevisionController`

### Listeners
- `listener.global` -- Hibernate event listeners for audit (`GlobalAuditablePreInsertListener`, `GlobalAuditablePreUpdateListener`) and revision tracking (`GlobalPostInsertRevisionListener`, `GlobalPostUpdateRevisionListener`)

### MongoDB
- `mongo` -- `IndexAwareMongoTemplate`
- `mongo.document` -- `MasterItem` (Mongo document)
- `mongo.indexing` -- `DeferredIndexManager`, `IndexFieldExtractor`
- `mongo.listener` -- `GlobalPostInsertSyncToMongoListener`, `GlobalPostUpdateSyncToMongoListener`
- `mongo.mapper` -- `IMongoMapper`, `MongoMasterItemMapper`
- `mongo.service` -- `MongoSyncService`

### Meilisearch
- `meilisearch` -- `MeilisearchIndexInitializer`
- `meilisearch.listener` -- `GlobalPostInsertMeilisearchListener`, `GlobalPostUpdateMeilisearchListener`
- `meilisearch.mapper` -- `IMeilisearchMapper`, `DefaultMeilisearchMapper`
- `meilisearch.service` -- `MeilisearchService`, `MeilisearchErrorHandler`, `LoggingMeilisearchErrorHandler`

### GraphQL
- `graphql.directive` -- `EmailConstraint`, `AbstractPredefinedPatternConstraint`
- `graphql` -- `StarterGraphqlExceptionResolver`

### Utilities
- `util` -- `AgeUtil`, `DateTimeUtil`, `EncryptionUtil`, `HashUtil`, `HibernateUtil`, `MongoDBUtil`, `NumberUtil`, `QueryUtil`, `SanitizeUtil`, `StreamUtil`, `XMLUtil`, `ExceptionUtil`

### Auto-Configuration
- `autoconfigure` -- `RamaStarterAutoConfiguration`, `RamaStarterProperties`, `RamaStarterLiquibaseProperties`

## Bundled Spring Stack

`rama-spring-boot-starter` includes:
- `spring-boot-starter`, `spring-boot-starter-web`, `spring-boot-starter-webflux`
- `spring-boot-starter-data-jpa`, `spring-boot-starter-data-mongodb`
- `spring-boot-starter-validation`, `spring-boot-starter-security`
- `spring-boot-starter-graphql`, `spring-boot-starter-liquibase`
- `spring-boot-starter-actuator`
- `spring-cloud-starter-vault-config`

## What's NOT Included

- Patient, encounter, vital sign, document entities and logic
- Hospital-specific workflow (jobs, listeners, integrations)
- Application bootstrap and Keycloak config
- Domain-specific Mongo/Meilisearch mappers

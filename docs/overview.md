# Package Overview

## Purpose

`rama-spring-starter` packages reusable platform code from `ramaservice` into an installable internal starter.

The goal is to let consumer services depend on shared infrastructure directly instead of duplicating entities, repositories, services, and helper classes.

## Modules

### `rama-spring-core`

Contains the reusable runtime code:

- entities
- repositories
- repository base classes
- shared services
- shared annotations
- shared helpers/utilities
- template/print infrastructure
- optional Mongo support classes
- optional Meilisearch support classes
- GraphQL directive classes

### `rama-spring-autoconfigure`

Contains Spring Boot wiring:

- auto-configuration
- `@ConfigurationProperties`
- conditional bean registration
- Hibernate listener registration
- starter-owned Liquibase changelogs

### `rama-spring-boot-starter`

Consumer-facing dependency bundle.

Applications should normally depend on this module, not on `core` or `autoconfigure` directly.

This module now bundles the standard shared Spring stack used by Rama services, so consumer applications do not need to repeat the common Spring Boot starter list.

## Included Areas

### Shared entity families

- `system*`
- `api*`
- `asset*`
- `master*`
- `revision`

### Shared infrastructure

- base repository and repository implementation
- Querydsl helpers
- generic entity helper service
- generic API service
- storage/object-storage service
- vault service
- reusable print/template infrastructure
- barcode/image/pdf/template helpers
- audit/revision listeners and services
- environment/static-value helpers

### Shared annotations

- `ApiReturnType`
- `ApiReturnTypes`
- `TrackRevision`
- `SyncToMongo`
- `SyncToMeilisearch`
- `TransformableMap`

### Shared helpers

- `Response`
- `AgeUtil`
- `HashUtil`
- `XMLUtil`
- `HibernateUtil`
- `Encrypt`
- `JsonEncryptConverter`

### Optional platform extensions

- Mongo sync and indexing helpers
- Meilisearch sync and indexing helpers
- GraphQL validation/scalar wiring

### Bundled Spring stack

`rama-spring-boot-starter` includes:

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

## Excluded Areas

The starter intentionally excludes:

- all `patient*`
- all `encounter*`
- hospital-specific / EMR-specific logic
- controllers
- application bootstrap
- patient/encounter GraphQL application code
- patient/document/encounter Mongo concrete documents and mappers
- patient-specific Meilisearch concrete mappers
- hospital workflow jobs/listeners/integrations

## Design Principles

- only reusable code is extracted
- app-specific implementations stay in `ramaservice`
- optional infrastructure is guarded with conditions and feature flags
- consumer applications can override starter beans directly
- starter migrations are isolated from app changelogs

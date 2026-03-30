# rama-spring-starter

Reusable Spring Boot starter extracted from `ramaservice` for the Rama healthcare platform.

Provides shared entities, repositories, services, document processing, audit/revision tracking, and infrastructure integrations (MongoDB, Meilisearch, MinIO, Gotenberg) so consumer services don't duplicate platform code.

## Quick Start

Add the dependency (published to GitHub Pages Maven repo):

```xml
<repositories>
    <repository>
        <id>github-pages</id>
        <url>https://ramacare.github.io/rama-spring-starter</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>org.rama</groupId>
        <artifactId>rama-spring-boot-starter</artifactId>
        <version>4.0.1</version>
    </dependency>

    <!-- Add your JDBC driver -->
    <dependency>
        <groupId>com.microsoft.sqlserver</groupId>
        <artifactId>mssql-jdbc</artifactId>
        <scope>runtime</scope>
    </dependency>
</dependencies>
```

Minimal config:

```properties
rama.liquibase.enabled=true
encrypt.key=your-encryption-key
```

## Documentation

- [Architecture Overview](./docs/overview.md)
- [Consumer Integration Guide](./docs/consumer-manual.md)
- [Extension Points](./docs/extension-points.md)
- [Publishing Guide](./docs/publishing.md)

## Modules

| Module | Purpose |
|--------|---------|
| `rama-spring-core` | Entities, repositories, services, utilities |
| `rama-spring-autoconfigure` | Auto-configuration, properties, bean wiring |
| `rama-spring-boot-starter` | Consumer-facing dependency (includes full Spring stack) |

## Build

```bash
mvn clean install           # Build and install locally
mvn test                    # Run tests
mvn -DskipTests verify      # Verify without tests
```

## Tech Stack

- Spring Boot 4.0.3 / Spring Cloud 2025.1.1
- Java 17
- JPA/Hibernate (H2, MySQL, MSSQL, PostgreSQL)
- MongoDB (optional)
- Meilisearch (optional)
- MinIO (S3-compatible storage)
- Gotenberg (PDF generation)
- GraphQL with validation directives
- Liquibase migrations
- Quartz scheduler

## License

MIT

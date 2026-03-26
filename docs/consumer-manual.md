# Consumer Manual

## 1. Build and Install

If you are testing locally, install the starter into local Maven cache:

```bash
cd /Users/tantee/IdeaProjects/rama-spring-starter
mvn -DskipTests clean install
```

## 2. Add the Dependency

In the consumer service `pom.xml`:

```xml
<dependency>
    <groupId>org.rama.starter</groupId>
    <artifactId>rama-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

For most services, this is the only shared platform dependency you should need. Keep only:

- `rama-spring-boot-starter`
- JDBC driver
- app-specific libraries not meant to be centrally bundled

Example:

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

## 3. Configure the Starter

At minimum, configure the pieces your application uses.

Example:

```properties
rama.starter.storage.file-storage-location=s3
rama.starter.storage.minio-endpoint=http://localhost:9000
rama.starter.storage.minio-access-key=minioadmin
rama.starter.storage.minio-secret-key=minioadmin

rama.starter.document.gotenberg-server=http://localhost:3000
rama.starter.liquibase.enabled=true
```

Full example:

```yaml
rama:
  starter:
    storage:
      file-storage-path: ./data
      file-storage-location: s3
      minio-endpoint: http://localhost:9000
      minio-access-key: minioadmin
      minio-secret-key: minioadmin
    document:
      gotenberg-server: http://localhost:3000
      placeholder-pattern: "{{(.+?)}}"
    static-values:
      enabled: true
      group-key: $StaticValue
      current-username-fallback-key: AgentUpdateSystem
      refresh-ttl: 5m
    revision:
      enabled: true
    mongo:
      enabled: false
      deferred-indexes-enabled: true
    meilisearch:
      enabled: false
      host-url: http://localhost:7700
      api-key: ""
      initialize-indexes: true
    graphql:
      enabled: true
    liquibase:
      enabled: true
```

## 4. Use Optional Features

### Revision tracking

Enable revision support:

```properties
rama.starter.revision.enabled=true
```

Annotate your entity:

```java
@TrackRevision({"statusCode", "name"})
public class MyEntity {
}
```

### Mongo sync

Enable Mongo support:

```properties
rama.starter.mongo.enabled=true
```

Annotate your entity:

```java
@SyncToMongo(
    mongoClass = MyMongoDocument.class,
    mapperClass = MyMongoMapper.class
)
public class MyEntity {
}
```

### Meilisearch sync

Enable Meilisearch support:

```properties
rama.starter.meilisearch.enabled=true
rama.starter.meilisearch.host-url=http://localhost:7700
```

Annotate your entity:

```java
@SyncToMeilisearch(
    indexName = "my_index",
    searchableAttributes = {"name", "code"},
    filterableAttributes = {"statusCode"}
)
public class MyEntity {
}
```

### GraphQL support

Enable starter GraphQL wiring:

```properties
rama.starter.graphql.enabled=true
```

## 5. Notes For Consumer Apps

- if you want deferred Mongo index flush, enable scheduling in the app
- if you want async revision/sync calls, enable async execution in the app
- if your app needs real encryption, provide a `TextEncryptor` bean
- if your app has its own current-user fallback logic, replace `StaticValueResolver`
- if your app does not want part of the bundled stack, disable the starter feature by property or exclude the dependency explicitly at Maven level

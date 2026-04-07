# Consumer Integration Guide

## 1. Add the Dependency

```xml
<repositories>
    <repository>
        <id>github-pages</id>
        <name>GitHub Pages Maven Repository</name>
        <url>https://ramacare.github.io/rama-spring-starter</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>org.rama</groupId>
        <artifactId>rama-spring-boot-starter</artifactId>
        <version>4.0.1</version>
    </dependency>

    <!-- Your JDBC driver -->
    <dependency>
        <groupId>com.microsoft.sqlserver</groupId>
        <artifactId>mssql-jdbc</artifactId>
        <scope>runtime</scope>
    </dependency>
</dependencies>
```

For local development, install the starter to your local Maven cache:

```bash
cd rama-spring-starter
mvn -DskipTests clean install
```

## 2. Configuration

### Minimal

```properties
rama.liquibase.enabled=true
encrypt.key=your-32-char-aes-key
```

### Full Reference

```yaml
# Infrastructure config (original property prefixes - no rama. prefix needed)
app:
  file-storage-path: ./data          # Local fallback path (default: ./data)
  file-storage-location: s3          # "s3" or "local" (default: s3)

minio:
  endpoint: http://localhost:9000
  access-key: minioadmin
  secret-key: minioadmin

document:
  gotenberg-server: http://localhost:3000
  placeholder-pattern: "\\{\\{(.+?)\\}\\}"
  section-start-pattern: "..."
  section-end-pattern: "..."
  section-item-pattern: "..."
  repeat-attribute-property: RepeatAttribute
  maximum-pages-property: MaximumPages

meilisearch:
  host: http://localhost:7700
  master-key: ""

encrypt:
  key: ""                            # AES encryption key for @Convert(Encrypt.class)

# Starter feature flags (rama. prefix)
rama:
  jpa:
    enabled: true                    # Enable JPA infrastructure (default: true)
  static-values:
    enabled: true                    # Enable MasterItem-backed static values
    group-key: $StaticValue
    current-username-fallback-key: AgentUpdateSystem
    refresh-ttl: 5m                  # Cache TTL
  revision:
    enabled: true                    # Enable @TrackRevision listener
  mongo:
    enabled: false                   # Enable MongoDB sync
    deferred-indexes-enabled: true   # Auto-create indexes on usage patterns
  meilisearch:
    enabled: false                   # Enable Meilisearch sync
    initialize-indexes: true         # Create indexes on startup
  graphql:
    enabled: true                    # Enable GraphQL validation/scalar wiring
  liquibase:
    enabled: true                    # Run starter Liquibase migrations
```

## 3. Entity Pattern

Every JPA entity must implement `Auditable` and embed `UserstampField` + `TimestampField`:

```java
@Entity
@Data
@NoArgsConstructor
public class MyEntity implements Auditable {
    @Id
    @Column(updatable = false, nullable = false)
    private String id;

    private String name;

    @Enumerated(EnumType.STRING)
    private final StatusCode statusCode = StatusCode.active;

    @Embedded
    private final UserstampField userstampField = new UserstampField();

    @Embedded
    private final TimestampField timestampField = new TimestampField();
}
```

## 4. Repository Pattern

Extend `BaseRepository` (adds `refresh()`, `saveAndRefresh()`). Use `SoftDeleteRepository` for soft-delete with `withoutTerminated()`:

```java
@GraphQlRepository
public interface MyEntityRepository extends BaseRepository<MyEntity, String>,
        SoftDeleteRepository<MyEntity, String>,
        QuerydslPredicateExecutor<MyEntity> {
}
```

## 5. GraphQL Controller Pattern

```java
@Controller
@RequiredArgsConstructor
public class MyEntityController {
    private final MyEntityRepository repository;

    @MutationMapping
    public Optional<MyEntity> createMyEntity(@Argument Map<String, Object> input) {
        return GenericEntityService.createEntity(MyEntity.class, repository, input, "id");
    }

    @MutationMapping
    public Optional<MyEntity> updateMyEntity(@Argument Map<String, Object> input) {
        return GenericEntityService.updateEntity(MyEntity.class, repository, input, "id");
    }
}
```

## 6. Optional Features

### Revision Tracking

```properties
rama.revision.enabled=true
```

```java
@Entity
@TrackRevision
public class MyEntity implements Auditable { ... }
```

### MongoDB Sync

```properties
rama.mongo.enabled=true
```

```java
@Entity
@SyncToMongo(mongoClass = MyMongoDoc.class, mapperClass = MyMongoMapper.class)
public class MyEntity implements Auditable { ... }
```

Implement `IMongoMapper`:

```java
@Mapper
public interface MyMongoMapper extends IMongoMapper<MyEntity, MyMongoDoc> {
    MyMongoDoc map(MyEntity entity);
}
```

### Meilisearch Sync

```properties
rama.meilisearch.enabled=true
rama.meilisearch.host-url=http://localhost:7700
```

```java
@Entity
@SyncToMeilisearch(filterableAttributes = {"statusCode", "name"})
public class MyEntity implements Auditable { ... }
```

### Document Template Processing

The starter provides a full DOCX-to-PDF pipeline:

1. `TemplatePreprocessor` -- converts Word form controls to placeholders
2. `DocxTemplateProcessor` -- replaces `{{placeholder}}` with data
3. `PdfService` -- converts DOCX to PDF via Gotenberg, merges, trims, watermarks

Placeholder syntax: `{{key;attribute1="value1";attribute2="value2"}}`

Built-in attributes: `image`, `qrcode`, `barcode39`, `barcode128`, `html`, `checkbox`, `datetime`, `date`, `time`, `master`, `join`, `prefix`, `suffix`, `if`, `else`, `ifempty`

### Encryption

`EncryptionUtil` provides AES/CBC encryption. Set the key:

```properties
rama.encryption.key=your-32-char-aes-key
```

Use on entity fields:

```java
@Convert(converter = Encrypt.class)
private String sensitiveField;

@Convert(converter = JsonEncryptConverter.class)
@Column(length = 4000)
private Map<String, Object> encryptedJson;
```

## 7. Liquibase Migrations

The starter manages its own tables via `rama-spring-starter-master.yaml`. Include it in your app's changelog:

```yaml
# db.changelog-master.yaml
databaseChangeLog:
  - include:
      file: db/changelog/rama-spring-starter-master.yaml
  - include:
      file: db/changelog/your-app-tables.yaml
```

Starter-managed tables: `api`, `api_header_set`, `asset_file`, `master_group`, `master_id`, `master_item`, `revision`, `system_log`, `system_parameter`, `system_template`, `client_config`

## 8. FTP Support (Optional)

The starter provides FTP infrastructure for file exchange (e.g., HL7 lab/radiology integration). It is **disabled by default**.

### Enable FTP

1. Add the `commons-net` dependency to your `pom.xml` (it is optional in the starter and not pulled transitively):

```xml
<dependency>
    <groupId>commons-net</groupId>
    <artifactId>commons-net</artifactId>
    <version>3.12.0</version>
</dependency>
```

2. Enable FTP in `application.properties`:

```properties
rama.ftp.enabled=true
```

3. Configure servers:

```properties
ftp.servers.lab.host=ftp.example.com
ftp.servers.lab.port=21
ftp.servers.lab.username=user
ftp.servers.lab.password=secret
ftp.servers.lab.passive-mode=true
ftp.servers.lab.inbound-folder=/inbound
ftp.servers.lab.outbound-folder=/outbound
```

### Usage

Inject `FtpService` and use its methods:

```java
@Autowired
private FtpService ftpService;

// List files
List<String> files = ftpService.list("lab", "/inbound");

// Upload
ftpService.upload("lab", "/outbound", "message.hl7", inputStream, true);

// Download
byte[] data = ftpService.download("lab", "/inbound/result.hl7");

// Read/write text with encoding
ftpService.writeText("lab", "/outbound", "order.hl7", hl7Message, true);
String content = ftpService.readText("lab", "/inbound/result.hl7");
```

### Missing dependency warning

If `rama.ftp.enabled=true` but `commons-net` is not on the classpath, the starter logs a warning at startup with the required dependency snippet. FTP beans will **not** be created.

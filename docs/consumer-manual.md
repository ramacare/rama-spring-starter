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

### PDF Watermarking

`PdfService.addWatermarkBytesBlocking` stamps a diagonal, semi-transparent, centered watermark on every page using iText 8 plus the bundled THSarabunNew font — so Thai text renders correctly out of the box.

Overloads:

```java
// Defaults: 96pt, DeviceGray at 0.5 opacity
byte[] out = pdfService.addWatermarkBytesBlocking(pdfBytes, "ตัวอย่าง");

// Default size, custom color by name (accepts any iText WebColors name or hex)
byte[] out = pdfService.addWatermarkBytesBlocking(pdfBytes, "DRAFT", "red");

// Custom size + color name
byte[] out = pdfService.addWatermarkBytesBlocking(pdfBytes, "DRAFT", 72f, "#AA0000");

// Custom size + java.awt.Color (bridges to iText internally)
byte[] out = pdfService.addWatermarkBytesBlocking(pdfBytes, "DRAFT", 72f, java.awt.Color.RED);

// Custom size + iText Color directly
byte[] out = pdfService.addWatermarkBytesBlocking(pdfBytes, "DRAFT", 72f, new DeviceRgb(170, 0, 0));
```

Multi-line watermarks are supported (split on `\n`); lines stack perpendicular to the diagonal and stay centered as a block.

### PDF Signing (`AbstractSignService`)

`AbstractSignService` wraps iText's `PdfSigner` for PAdES-compliant digital signatures with an embedded signer-name block. Subclass it and implement `resolveSigningMaterial(alias, commonName)` to supply the certificate chain and private key (typically from your `CertificateService` or vault).

The bundled THSarabun font is the default, so Thai signer names render correctly without any extra setup:

```java
@Service
public class RamaSignService extends AbstractSignService {

    // 3-arg constructor: uses the bundled THSarabun as signer-block font
    public RamaSignService(ITSAClient tsa, HttpTsaConfiguration tsaConfig) {
        super(tsa, tsaConfig, "/images/your-org-logo.png");
    }

    @Override
    protected SigningMaterial resolveSigningMaterial(String alias, String commonName) throws Exception {
        // build your Certificate[] and PrivateKey
        return new SigningMaterial(chain, privateKey);
    }
}
```

Override the font with your own if needed:

```java
// 4-arg form: null or blank fontPath also falls back to the default
super(tsa, tsaConfig, "/fonts/my-custom-font.ttf", "/images/your-org-logo.png");
```

The bundled font classpath path is exposed as `AbstractSignService.DEFAULT_FONT_RESOURCE = "/org/rama/fonts/THSarabunNew.ttf"` — use the same path from your own code if you want to reuse the font elsewhere (e.g., in a custom iText layout).

> **Note on the font path:** the font ships under `/org/rama/fonts/` rather than `/fonts/` so it can't be accidentally shadowed by a consumer app's own `src/main/resources/fonts/THSarabunNew.ttf` when Spring Boot's fat-jar classloader searches `BOOT-INF/classes/` before `BOOT-INF/lib/*.jar`.

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

The starter ships its changelog files inside the JAR. **You must `<include>` them in your app's master changelog** — the standard Spring Boot way — and point `spring.liquibase.change-log` at that master.

```properties
spring.liquibase.change-log=classpath:/db/db.changelog-master.yaml
```

```yaml
# db.changelog-master.yaml
databaseChangeLog:
  - include:
      file: db/changelog/rama-spring-starter-master.yaml
  # Optional: include if you use Quartz-backed JDBC job store
  - include:
      file: db/changelog/rama-spring-quartz.changelog.xml
  - include:
      file: db/changelog/your-app-tables.yaml
```

Starter-managed tables: `api`, `api_header_set`, `asset_file`, `master_group`, `master_id`, `master_item`, `revision`, `system_log`, `system_parameter`, `system_template`, `client_config`, `api_key`

### How the starter coexists with Spring Boot's default Liquibase

The starter registers a fallback `ramaStarterLiquibase` bean guarded with `@ConditionalOnMissingBean(SpringLiquibase.class)`. The auto-config is ordered AFTER `LiquibaseAutoConfiguration`, so:

- **If you set `spring.liquibase.change-log`** (recommended): Spring Boot's default `liquibase` bean runs your master changelog. The starter's fallback bean backs off. Your master must `<include>` the starter changelog(s) as shown above.
- **If you do NOT configure `spring.liquibase.change-log`**: Spring Boot's default backs off (no changelog), the starter's fallback takes over and runs `rama.liquibase.change-log` (default: `rama-spring-starter-master.yaml`). Starter tables exist; your app tables do not. Only suitable for apps that *only* use starter tables.

To disable the starter's fallback entirely (e.g., if you want to fail fast when no Liquibase is configured), set `rama.liquibase.enabled=false`.

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

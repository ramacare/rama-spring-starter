# Consumer Integration Guide

## Upgrading

Behaviour changes that need action when you bump the starter. Anything not listed here is
additive.

### Upgrading past 4.3.1 — Jackson datetimes are framed in the JVM time zone

The starter's mappers previously left Jackson at its built-in UTC default. They now use the
JVM's zone. Two things change; the instant is preserved in both.

- **Deserialization** — a payload sent as `+07:00` used to materialize as `Z`, shifting the
  wall clock and, before 07:00 local, the calendar date. It now keeps the local frame. **If
  your code compensated for the old behaviour with a fixed offset, drop that compensation —
  it will now double-correct.** Compensation via `atZoneSameInstant(ZoneId.systemDefault())`
  is safe and needs no change.
- **Serialization** — `OffsetDateTime` used to be written with whatever offset the value
  carried; it is now normalized to the JVM zone. On a deployment pinned to
  `TZ=Asia/Bangkok` this is a no-op. **Tests asserting a literal offset in serialized JSON
  will fail on a CI runner in another zone** — assert on the instant instead.

Also worth knowing: `spring.jackson.time-zone` used to be silently ignored by the starter
and now works, and overriding the `ObjectMapper` bean never replaced the mapper the starter's
services use — override `JsonMapper`. See [Date/Time Frame](#datetime-frame) for the detail.

### Upgrading past 4.3.3 — the idempotency cleanup job is actually scheduled now

`system_request_dedup` was never evicted: `systemRequestDedupCleanupJobDetail` and
`systemRequestDedupCleanupTrigger` were gated on a `Scheduler` bean by a condition that ran
before Quartz had contributed one, so nothing was ever registered in the `rama-idempotency`
trigger group. Both beans are now gated on the Quartz classes being present instead, which is
order-independent. A second defect sat behind it: the delete itself threw
`TransactionRequiredException` under Hibernate 7.2, because Quartz reaches the job by
self-invocation and no transaction from the job applied.

**Nothing to change on your side**, but two things to know:

- **The table starts shrinking.** If it has been growing since you adopted idempotency, the job
  will evict everything expired on its first run (every `cleanup-interval`, default `5m`). To
  clear a large backlog up front rather than in one job run:
  ```sql
  DELETE FROM system_request_dedup WHERE expires_at < NOW();
  ```
- **Nothing was at risk while it was broken.** `lockAndExecute` treats an expired row as
  re-runnable, so stale rows never replayed a response or blocked a request — the table simply
  grew.

Check `QRTZ_TRIGGERS` for a row in group `rama-idempotency` after upgrading to confirm it took.
See starter#47.

### Upgrading past 4.3.2 — `rama.idempotency.default-ttl` now actually applies

`@IdempotentMutation` used to default to a hardcoded `ttl = "30s"`, which meant
`rama.idempotency.default-ttl` was read only by a method annotated `@IdempotentMutation(ttl = "")`
— effectively never. The annotation now defaults to blank and falls through to the property, so a
deployment can retune every unqualified mutation from one place.

**If you set `default-ttl` to something other than `30s`**, every `@IdempotentMutation` that does not
name its own `ttl` now picks that value up, where before it silently stayed at 30 seconds. Pin the old
behaviour by writing `@IdempotentMutation(ttl = "30s")` on the methods that need it — an explicit `ttl`
still wins over the property. If you never set `default-ttl`, nothing changes: the property's own
default is 30 seconds.

`rama.idempotency.lock-wait-timeout` is **removed**. It was never read by anything; the wait for a
contended dedup row has always been governed by the database's own lock timeout (PostgreSQL
`lock_timeout`, SQL Server `SET LOCK_TIMEOUT`). Delete it from your configuration — with
`spring-boot-configuration-processor` on the classpath it will now show as an unknown property.

Also worth knowing: the idempotency auto-configuration no longer backs off silently. If
`rama.idempotency.enabled` is true but no `SystemRequestDedupRepository` bean resolves — usually
because `org.rama.repository` is missing from your `@EnableJpaRepositories(basePackages = ...)` — the
starter now logs a warning at startup and any `@IdempotentMutation` call fails with a message naming
the fix, instead of running unguarded. See starter#46.

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
    deferred-index-threshold: 100    # Cumulative uses of a field-set before its index is created
    deferred-index-flush-interval: 10m  # How often accumulated usage is examined (0 disables the flush)
  meilisearch:
    enabled: false                   # Enable Meilisearch sync
    initialize-indexes: true         # Create indexes on startup
  graphql:
    enabled: true                    # Enable GraphQL validation/scalar wiring
    legacy-coercion:
      enabled: true                  # Restore pre-graphql-java-22 lenient scalar coercion
  liquibase:
    enabled: true                    # Run starter Liquibase migrations
  cors:
    enabled: true                    # Register bundled blanket CORS filter (HIGHEST_PRECEDENCE)
    allowed-origins: "*"
    allowed-methods: "POST, GET, OPTIONS, PUT, DELETE"
    allowed-headers:                 # Idempotency-Key is auto-merged from rama.idempotency.header-name
      - Accept
      - Content-Type
      - Origin
      - X-Requested-With
      - Last-Modified
      - Authorization
      - Referrer-Policy
  idempotency:
    enabled: true                    # @IdempotentMutation aspect (see Quartz + JPA section)
    header-name: Idempotency-Key
    default-ttl: 30s                 # TTL for any @IdempotentMutation that sets no ttl of its own
    cleanup-interval: 5m             # How often the Quartz job evicts expired rows
    cors:
      augment: true                  # Inject header-name into any CorsConfigurationSource bean
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

### Entity Lifecycle Events

`@EntityEvent` publishes a typed Spring `ApplicationEvent` after a JPA insert/update commits. Pair it with a custom event class implementing `IEntityEvent<T>` and a Spring `@EventListener` consumer to plug post-commit business logic into the entity lifecycle.

```properties
# No flag needed — listeners are wired by default whenever the starter is on the classpath.
```

Define an event class with a single-arg constructor:

```java
@Getter
@AllArgsConstructor
public class EncounterCreated implements IEntityEvent<Encounter> {
    private Encounter entity;
}
```

Annotate the entity:

```java
@Entity
@EntityEvent(createdEvent = EncounterCreated.class, updatedEvent = EncounterUpdated.class)
public class Encounter implements Auditable { ... }
```

Consume the event:

```java
@Service
public class OdooEncounterService {
    @EventListener
    public void onEncounterCreated(EncounterCreated event) {
        // Runs after the create transaction commits — safe to enqueue
        // outbound messages, kick off async work, etc. The entity is already
        // persisted and visible to other connections.
    }
}
```

**Semantics:**

- Both `createdEvent` and `updatedEvent` are optional; default to the generic `EntityCreated` / `EntityUpdated`. Set either to `EntityEmptyEvent.class` to opt out of that lifecycle phase.
- `afterCommit = true` (the default) registers a `TransactionSynchronization` that publishes after the entity-write transaction commits. With `afterCommit = false`, the event publishes inside the write transaction — useful when the listener needs to participate in the same tx, but listener failures will then roll back the entity write.
- `EntityEventService.publishEntityEvent` is the bean that does the work; consumers normally don't call it directly, but it's available for manual triggers (e.g. a backfill mutation that re-emits an event for a row that bypassed the listener path).

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

### Date/Time Frame

Jackson's own default context zone is UTC, so an unconfigured mapper re-frames every
incoming `+07:00` value to `Z`. The instant is preserved, but `toLocalDate()` and
`getHour()` then disagree with the caller — and for anything before 07:00 local, so does
the calendar date.

The starter frames its mappers in the JVM's default zone instead, matching
`OffsetDateTimeConverter`, `DateTimeUtil`, `QueryUtil` and `MongoDBUtil`:

- **Spring Boot's managed `JsonMapper`**, via the `ramaStarterTimeZoneCustomizer` bean. This
  is the mapper every starter service actually receives — `GenericEntityService`,
  `GenericApiService`, `SystemLogService`, `MeilisearchService` and
  `DefaultMeilisearchMapper` all inject `JsonMapper`, so they all share this one instance.
- **The static mappers in `JsonConverter` and `JsonEncryptConverter`**, used by `@Convert`
  JSON columns.

Two mappers are deliberately *not* framed in the JVM zone:

- `CanonicalJson` is pinned to UTC. Idempotency signatures are hashed off its output, so it
  must render identically on every deployment regardless of the container's `TZ`.
- `XMLUtil` is left at Jackson's defaults; it does not carry datetime payloads.

`ramaStarterObjectMapper` is a fallback that only registers when Boot's Jackson
auto-configuration is absent. In a normal Boot application its `@ConditionalOnMissingBean`
matches Boot's `jacksonJsonMapper` and it never registers — override `JsonMapper`, not
`ObjectMapper`, if you need to replace the mapper the services use.

Pin the zone explicitly in your container so the JVM, the mappers and the database agree:

```dockerfile
ENV TZ=Asia/Bangkok
```

To frame Jackson somewhere other than the JVM zone, set the standard Spring property. The
starter's customizer is ordered `HIGHEST_PRECEDENCE` and Boot's own runs after it, so an
explicit setting always overwrites the starter's default:

```properties
spring.jackson.time-zone=Asia/Bangkok
```

> **Upgrading from 4.3.1 or earlier.** Deserialization previously produced `Z`. Code that
> compensated by normalizing to the system zone (`atZoneSameInstant(ZoneId.systemDefault())`)
> is unaffected — that is idempotent once the mapper is correct. Code that added a fixed
> offset to undo the shift will now double-correct and must drop the compensation.
>
> **Serialization changes too.** Jackson converts values into the configured zone on write
> only once a zone is set explicitly; previously none was, so `OffsetDateTime` was written
> with whatever offset the value carried. It is now normalized to the JVM zone. On a
> deployment pinned to `TZ=Asia/Bangkok` this is a no-op — values read from the database
> already carry `+07:00` — but a JVM in a different zone will now write that zone's offset.
> The instant is always preserved. Tests that assert a literal offset in serialized output
> should assert on the instant instead, or they will pass locally and fail on a CI runner
> in a different zone.

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

## 9. CORS

The starter ships two complementary CORS facilities; pick the one that matches your existing CORS strategy.

### Blanket CORS filter — `rama.cors.enabled` (default **true**)

Servlet `Filter` registered at `HIGHEST_PRECEDENCE` that sets the `Access-Control-Allow-*` headers from `rama.cors.*` properties and short-circuits OPTIONS preflights with `200 OK` before any auth filter sees them. Bundled from the hand-rolled `CORSFilter` that `ramaservice` and `his-service` each carried separately — see issue #26.

The `Idempotency-Key` header (or whatever `rama.idempotency.header-name` resolves to) is automatically merged into the `allowedHeaders` list, so the frontend's `@ramathibodi/nuxt-commons` POST-side header (rama-modules#232) works without you having to maintain the list in two places.

**Disable** when you use Spring's idiomatic `CorsConfigurationSource`-based CORS handling (Spring Security `.cors()` or `WebMvcConfigurer.addCorsMappings`): the blanket filter would overwrite the headers your `CorsConfigurationSource` computes.

```properties
rama.cors.enabled=false
```

### `CorsConfigurationSource` augmenter — `rama.idempotency.cors.augment` (default **true**)

For consumers that **do** use Spring's idiomatic CORS, a `BeanPostProcessor` wraps every `CorsConfigurationSource` bean in `IdempotencyAwareCorsConfigurationSource`. The decorator returns a per-request copy of the delegate's `CorsConfiguration` with the idempotency header appended to `allowedHeaders` — never mutating the delegate. Strictly additive: it leaves the policy unchanged when `allowedHeaders == null` ("allow all"), contains `"*"` (wildcard), or already lists the header (case-insensitive).

```properties
rama.idempotency.cors.augment=false   # opt out
```

The augmenter and the blanket filter are independent — when both are on, the blanket filter's headers win because it writes them at `HIGHEST_PRECEDENCE` before any handler runs. If the blanket filter doesn't fit your needs, set `rama.cors.enabled=false` and let the augmenter keep your `CorsConfigurationSource` in sync with the idempotency header name.

## 10. GraphQL Legacy Scalar Coercion

`rama.graphql.legacy-coercion.enabled` (default **true**) wires graphql-java's `LegacyCoercingInputInterceptor.migratesValues()` as an Instrumentation so the built-in `Boolean` / `Float` / `Int` / `String` scalars accept the pre-graphql-java-22 lenient coercions (`Integer` ↔ `String`, `"true"` ↔ `Boolean`, etc.). Bundled from the hand-rolled `GraphQlStringCoercionConfig` `ramaservice` and `his-service` each carried — see issue #27.

Set to `false` to opt back into graphql-java's strict spec compliance for spec-clean clients:

```properties
rama.graphql.legacy-coercion.enabled=false
```

The customizer is registered via `@ConditionalOnMissingBean(name = "ramaStarterLegacyCoercionCustomizer")`, so consumers can still drop in their own `GraphQlSourceBuilderCustomizer` with a different interceptor configuration if they need to tune the coercion policy.

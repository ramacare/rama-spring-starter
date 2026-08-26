# Extension Points

## Override Model

Most starter beans use `@ConditionalOnMissingBean`. Declare your own bean of the same type to replace starter behavior.

## Common Override Points

| Bean Type | Default Behavior | Override When |
|-----------|-----------------|---------------|
| `StaticValueResolver` | Loads from MasterItem table with TTL cache | Custom static value source |
| `MeilisearchErrorHandler` | Logs errors + writes to SystemLog | Custom error handling |
| `ObjectMapper` | Lenient string coercion, framed in the JVM time zone | Custom serialization |
| `WebClient.Builder` | 5MB max in-memory | Different limits |
| `RuntimeWiringConfigurer` | Email validation + BigDecimal scalar | Custom GraphQL wiring |
| `StarterGraphqlExceptionResolver` | Generic error formatting | Custom error responses |

## Replacement Hooks (auto-wired)

Extend document template processing with custom hooks. Declare either as a `@Component` and the starter collects it — `RamaStarterAutoConfiguration` gathers every `ReplacementObjectHook` and `ReplacementStringHook` bean into `ReplacementHooks`, which `ReplacementProcessor` invokes on every placeholder. No wiring on your side:

### ReplacementObjectHook

Transform object values before string conversion. Includes `extractMrn()` helper.

```java
@Component
public class PatientHooks implements ReplacementObjectHook {
    @Override
    public Object process(Object replacement, Map<String, String> attributes) {
        if (attributes.containsKey("patient")) {
            // transform patient data
        }
        return replacement;
    }

    @Override
    public int getOrder() { return 1; }
}
```

### ReplacementStringHook

Transform string values after object-to-string conversion:

```java
@Component
public class MaskHooks implements ReplacementStringHook {
    @Override
    public String process(String replacement, Map<String, String> attributes) {
        if (attributes.containsKey("mask")) {
            return replacement.replaceAll(attributes.get("pattern"), attributes.get("mask"));
        }
        return replacement;
    }
}
```

## Replacement Transformers (consumer-wired)

`ReplacementTransformer` reshapes the **entire** replacement map before template processing —
useful when a value depends on the template being printed, or on patient/encounter context.

> **Unlike the hooks above, the starter does not apply these for you.** It defines the contract
> only. Declaring a `@Component` and expecting it to run will not work: nothing collects it, and
> the affected placeholder renders empty with no error. You must collect and apply the chain
> yourself. The split is deliberate — applying transformers needs a template code and patient
> context that the starter's `processTemplate(InputStream, Map)` has no knowledge of.

The contract:

```java
public interface ReplacementTransformer {
    Map<String, Object> transform(Map<String, Object> replacements, String mrn, String encounterId);

    /** "" (the default) applies to every template; otherwise only to this template code. */
    default String getTemplateCode() { return ""; }

    default int getOrder() { return Integer.MAX_VALUE; }
}
```

### 1. Implement it

Note the return type — the transformed map is returned, not mutated in place.

```java
@Component
@RequiredArgsConstructor
public class EReceiptTransformer implements ReplacementTransformer {
    private final StaticValueResolver staticValueService;

    @Override
    public Map<String, Object> transform(Map<String, Object> replacements, String mrn, String encounterId) {
        if ("90".equals(replacements.get("stationNo"))) {
            replacements.put("recipient", staticValueService.getStaticValue("EReceiptRecipient"));
        }
        return replacements;
    }

    @Override
    public String getTemplateCode() { return "receipt"; }
}
```

### 2. Apply the chain before calling `processTemplate`

```java
@Service
@RequiredArgsConstructor
public class DocumentDataEnricher {

    private final Map<String, ReplacementTransformer> replacementTransformers;

    public Map<String, Object> applyTransformers(String templateCode, Map<String, Object> replacements,
                                                 String mrn, String encounterId) {
        List<ReplacementTransformer> sorted = replacementTransformers.values().stream()
                .filter(t -> t.getTemplateCode() == null
                          || t.getTemplateCode().isEmpty()
                          || t.getTemplateCode().equals(templateCode))
                .sorted(Comparator.comparingInt(ReplacementTransformer::getOrder))
                .toList();

        for (ReplacementTransformer transformer : sorted) {
            replacements = transformer.transform(replacements, mrn, encounterId);
        }
        return replacements;
    }
}
```

Then, in your print service, before resolving the template:

```java
documentData = documentDataEnricher.applyTransformers(
        templateCode, documentData, document.getMrn(), document.getEncounterId());
```

**Reference implementation:** `DocumentDataEnricher` in `ramaservice`, `ramaservice-rewrite` and
`his-service` — all three carry the same class, called from their print service before template
resolution.

## Mongo Mapper

Implement `IMongoMapper` for custom JPA-to-MongoDB mapping:

```java
@Mapper
public interface MyMongoMapper extends IMongoMapper<MyEntity, MyMongoDocument> {
    MyMongoDocument map(MyEntity source);
}
```

Annotate the entity with `@SyncToMongo(mongoClass = ..., mapperClass = ...)`.

## Meilisearch Mapper

Implement `IMeilisearchMapper` when default Jackson serialization isn't enough:

```java
@Component
public class MyMeilisearchMapper implements IMeilisearchMapper<MyEntity> {
    @Override
    public Map<String, Object> toDocument(MyEntity entity) {
        // custom mapping
    }
}
```

## PDF Signing Service

`AbstractSignService` is an abstract class rather than a starter-provided bean — consumers subclass it to inject their signing material source (certificate store, vault, etc.). See [Consumer Manual § PDF Signing](./consumer-manual.md#pdf-signing-abstractsignservice) for a worked example.

The starter bundles `THSarabunNew.ttf` at `/org/rama/fonts/THSarabunNew.ttf` so Thai signer names render correctly. The 3-arg constructor uses this default; pass an explicit `fontPath` to the 4-arg form to override. Null or blank `fontPath` also falls back to the default.

## GraphQL Exception Resolver

Extend `StarterGraphqlExceptionResolver` for app-specific exceptions:

```java
@Component
public class AppExceptionResolver extends StarterGraphqlExceptionResolver {
    public AppExceptionResolver(Environment environment) {
        super(environment);
    }

    @Override
    protected List<GraphQLError> resolveCustomErrors(Throwable ex, DataFetchingEnvironment env) {
        if (ex instanceof MyAppException e) {
            return List.of(GraphqlErrorBuilder.newError()
                .message(e.getMessage())
                .errorType(ErrorClassification.errorClassification("AppError"))
                .build());
        }
        return null;
    }
}
```

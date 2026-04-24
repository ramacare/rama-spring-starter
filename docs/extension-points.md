# Extension Points

## Override Model

Most starter beans use `@ConditionalOnMissingBean`. Declare your own bean of the same type to replace starter behavior.

## Common Override Points

| Bean Type | Default Behavior | Override When |
|-----------|-----------------|---------------|
| `StaticValueResolver` | Loads from MasterItem table with TTL cache | Custom static value source |
| `MeilisearchErrorHandler` | Logs errors + writes to SystemLog | Custom error handling |
| `ObjectMapper` | Jackson defaults (lenient) | Custom serialization |
| `WebClient.Builder` | 5MB max in-memory | Different limits |
| `RuntimeWiringConfigurer` | Email validation + BigDecimal scalar | Custom GraphQL wiring |
| `StarterGraphqlExceptionResolver` | Generic error formatting | Custom error responses |

## Replacement Hooks

Extend document template processing with custom hooks:

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

### ReplacementTransformer

Transform the entire replacement data map before template processing:

```java
@Component
public class MyTransformer implements ReplacementTransformer {
    @Override
    public void transform(Map<String, Object> replacements, String mrn, String encounterId) {
        replacements.put("customField", computeValue(mrn));
    }

    @Override
    public String getTemplateCode() { return "MY_TEMPLATE"; }
}
```

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

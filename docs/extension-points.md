# Extension Points

## Override Model

Most starter beans are registered with `@ConditionalOnMissingBean`.

That means the consumer application can replace starter behavior by declaring its own bean of the same type.

## Common Override Points

- `TextEncryptor`
- `StaticValueResolver`
- `MeilisearchErrorHandler`
- `Client` for Meilisearch
- `MinioClient`
- `ObjectMapper`
- `WebClient.Builder`
- `RuntimeWiringConfigurer`
- any starter service bean

## Example Overrides

```java
import com.meilisearch.sdk.Client;
import org.rama.crypto.TextEncryptor;
import org.rama.meilisearch.service.MeilisearchErrorHandler;
import org.rama.service.environment.StaticValueResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RamaStarterOverrides {

    @Bean
    TextEncryptor textEncryptor() {
        return new MyTextEncryptor();
    }

    @Bean
    StaticValueResolver staticValueResolver() {
        return new MyStaticValueResolver();
    }

    @Bean
    MeilisearchErrorHandler meilisearchErrorHandler() {
        return new MyMeilisearchErrorHandler();
    }

    @Bean
    Client meilisearchClient() {
        return new Client("http://localhost:7700", "masterKey");
    }
}
```

## Application-Specific Mapper Pattern

The starter provides extension contracts, not your app’s concrete implementations.

### Mongo

Implement:

- `IMongoMapper`
- application-specific Mongo document class

Then annotate the JPA entity with `@SyncToMongo`.

### Meilisearch

Implement:

- `IMeilisearchMapper` when default Jackson conversion is not enough

Then annotate the entity with `@SyncToMeilisearch`.

## Encryption

The starter includes:

- `Encrypt`
- `JsonEncryptConverter`

Without a custom `TextEncryptor`, encryption is effectively no-op.

If the field must be truly encrypted, the application must provide a real `TextEncryptor` implementation.

## Static Value Resolution

Default behavior uses `StaticValueService` backed by `MasterItemService`.

If the app needs different behavior, replace `StaticValueResolver`.

## GraphQL Wiring

The starter wires common validation and scalars.

If the consumer app wants different GraphQL behavior, it can register its own `RuntimeWiringConfigurer`.

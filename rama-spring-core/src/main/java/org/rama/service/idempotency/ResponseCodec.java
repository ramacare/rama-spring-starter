package org.rama.service.idempotency;

import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.hibernate.collection.spi.PersistentCollection;
import org.hibernate.proxy.HibernateProxy;
import org.rama.entity.JsonConverter;
import org.rama.util.EncryptionUtil;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.cfg.ConstructorDetector;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.BeanPropertyWriter;
import tools.jackson.databind.ser.ValueSerializerModifier;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Encodes / decodes the cached response payload for the dedup row.
 *
 * <p>Uses the same Jackson 3 configuration as {@link JsonConverter} so cached
 * responses round-trip with the same semantics consumer code already gets
 * from the starter's JSON columns, plus two additions (starter#64):
 *
 * <ul>
 *   <li><b>Lazy JPA state is never loaded.</b> Encoding runs inside the mutation's
 *   transaction. Without help, Jackson calls the getters of an uninitialized
 *   Hibernate proxy or collection and loads it. When the referenced row does not
 *   exist that load throws and rolls the whole mutation back, and when it does
 *   exist an unrelated entity is dragged into the cache. A bean property holding an
 *   uninitialized proxy or collection is <i>omitted</i> — not written as {@code null},
 *   which a Lombok {@code @NonNull} setter would reject on replay; elsewhere (a root
 *   value, a list element) it is written as {@code null}. An initialized one is
 *   unwrapped. A replay therefore carries a lazy relation only if the original call
 *   had loaded it.</li>
 *   <li><b>The payload is encrypted at rest</b> with {@link EncryptionUtil} when
 *   {@code encrypt.key} is configured. A response can carry fields that are
 *   column-encrypted on the entity (e.g. a citizen ID), which are plaintext once
 *   loaded into the object.</li>
 * </ul>
 */
@Slf4j
public class ResponseCodec {

    private static final ObjectMapper MAPPER = JsonConverter.createObjectMapper()
            .rebuild()
            // Jackson 3 reads -parameters names, so a Lombok @RequiredArgsConstructor becomes
            // a properties creator even beside a no-arg one — and an omitted lazy relation
            // then reaches a @NonNull constructor parameter as null. Prefer the no-arg
            // constructor + setters whenever a class has one; records are unaffected.
            .constructorDetector(ConstructorDetector.DEFAULT.withAllowImplicitWithDefaultConstructor(false))
            .addModule(new SimpleModule("rama-idempotency-hibernate")
                    .addSerializer(HibernateProxy.class, new HibernateProxySerializer())
                    .addSerializer(PersistentCollection.class, new PersistentCollectionSerializer())
                    .setSerializerModifier(new OmitUnloadedProperties()))
            .build();

    private static final AtomicBoolean PLAINTEXT_WARNED = new AtomicBoolean();

    public String encode(Object value) {
        if (value == null) return null;
        String json = MAPPER.writeValueAsString(value);
        if (!EncryptionUtil.isConfigured()) {
            if (PLAINTEXT_WARNED.compareAndSet(false, true)) {
                log.warn("encrypt.key is not configured; idempotency responses are cached in system_request_dedup in PLAINTEXT.");
            }
            return json;
        }
        String encrypted = EncryptionUtil.encrypt(json);
        if (encrypted == null) {
            throw new IllegalStateException("Could not encrypt the idempotency response");
        }
        return encrypted;
    }

    public Object decode(String stored, Type returnType) {
        if (returnType == void.class || returnType == Void.class) return null;
        if (stored == null) {
            // No cached body (the response could not be encoded). An Optional-returning
            // method must not hand its caller a null.
            return MAPPER.constructType(returnType).hasRawClass(Optional.class) ? Optional.empty() : null;
        }
        String json = EncryptionUtil.isConfigured() ? decryptOrPassThrough(stored) : stored;
        JavaType jt = MAPPER.constructType(returnType);
        return MAPPER.readValue(json, jt);
    }

    /**
     * A row written before encryption was enabled — or by an older starter during a
     * rolling deploy — holds plain JSON, which does not decrypt. Rows live for the TTL
     * only, so read those as-is rather than failing the replay.
     */
    private static String decryptOrPassThrough(String stored) {
        String decrypted = EncryptionUtil.decrypt(stored);
        return decrypted != null ? decrypted : stored;
    }

    private static boolean isUnloaded(Object value) {
        return (value instanceof HibernateProxy || value instanceof PersistentCollection<?>)
                && !Hibernate.isInitialized(value);
    }

    private static final class OmitUnloadedProperties extends ValueSerializerModifier {
        @Override
        public List<BeanPropertyWriter> changeProperties(SerializationConfig config, BeanDescription.Supplier beanDesc,
                                                         List<BeanPropertyWriter> beanProperties) {
            List<BeanPropertyWriter> wrapped = new ArrayList<>(beanProperties.size());
            for (BeanPropertyWriter writer : beanProperties) {
                wrapped.add(new OmitWhenUnloadedWriter(writer));
            }
            return wrapped;
        }
    }

    private static final class OmitWhenUnloadedWriter extends BeanPropertyWriter {
        OmitWhenUnloadedWriter(BeanPropertyWriter base) {
            super(base);
        }

        @Override
        public void serializeAsProperty(Object bean, JsonGenerator gen, SerializationContext ctxt) throws Exception {
            if (isUnloaded(get(bean))) return;
            super.serializeAsProperty(bean, gen, ctxt);
        }
    }

    private static final class HibernateProxySerializer extends ValueSerializer<HibernateProxy> {
        @Override
        public void serialize(HibernateProxy value, JsonGenerator gen, SerializationContext ctxt) {
            if (isUnloaded(value)) {
                gen.writeNull();
                return;
            }
            ctxt.writeValue(gen, Hibernate.unproxy(value));
        }
    }

    @SuppressWarnings("rawtypes")
    private static final class PersistentCollectionSerializer extends ValueSerializer<PersistentCollection> {
        @Override
        public void serialize(PersistentCollection value, JsonGenerator gen, SerializationContext ctxt) {
            if (isUnloaded(value)) {
                gen.writeNull();
                return;
            }
            if (value instanceof Map<?, ?> map) {
                ctxt.writeValue(gen, new LinkedHashMap<>(map));
            } else if (value instanceof Collection<?> collection) {
                ctxt.writeValue(gen, new ArrayList<>(collection));
            } else {
                gen.writeNull();
            }
        }
    }
}

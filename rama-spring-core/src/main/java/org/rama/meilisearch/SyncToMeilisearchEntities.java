package org.rama.meilisearch;

import org.rama.annotation.SyncToMeilisearch;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every {@code @SyncToMeilisearch} entity found on the classpath, keyed by simple class name.
 *
 * <p>Discovery is a classpath scan (see {@link MeilisearchIndexInitializer}, which used to do
 * this scan itself) across the application's {@code basePackages} plus the starter's own
 * {@code org.rama} -- the starter's own {@code @SyncToMeilisearch} entities (e.g.
 * {@code org.rama.entity.master.MasterItem}) live outside any application's base package.
 *
 * <p>Keying by simple name (rather than fully-qualified name) is deliberate: it is what lets a
 * short, admin-typeable identifier (e.g. in {@code MeilisearchIndexSettingOverrideService})
 * resolve back to a {@code Class}. {@link MeilisearchService#resolveIndexName(Class)} already
 * defaults an index's name to this same simple name lowercased, so two entities sharing a simple
 * name would already collide there too -- this class fails fast at startup instead of leaving
 * that collision to surface later as a confusing Meilisearch indexing bug.
 */
public class SyncToMeilisearchEntities {

    private static final String STARTER_BASE_PACKAGE = "org.rama";

    private final Map<String, Class<?>> byName;

    public SyncToMeilisearchEntities(List<String> basePackages) {
        this(scan(basePackages));
    }

    /** Package-private: lets tests exercise the grouping/collision logic directly, without a real classpath scan. */
    SyncToMeilisearchEntities(Collection<Class<?>> entityClasses) {
        Map<String, Class<?>> found = new LinkedHashMap<>();
        for (Class<?> entityClass : entityClasses) {
            Class<?> existing = found.putIfAbsent(entityClass.getSimpleName(), entityClass);
            if (existing != null && existing != entityClass) {
                throw new IllegalStateException("Two @SyncToMeilisearch entities share the simple name '"
                        + entityClass.getSimpleName() + "': " + existing.getName() + " and " + entityClass.getName()
                        + " -- Meilisearch's own default index name is this same simple name lowercased, so this "
                        + "collision would already be ambiguous for indexing. Rename one of the entities.");
            }
        }
        this.byName = Map.copyOf(found);
    }

    private static Collection<Class<?>> scan(List<String> basePackages) {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(SyncToMeilisearch.class));

        // An application may legitimately BE org.rama (or nest under it), so de-duplicate the
        // packages -- an overlapping base package would otherwise scan the same class twice.
        Set<String> packagesToScan = new LinkedHashSet<>(basePackages);
        packagesToScan.add(STARTER_BASE_PACKAGE);

        Map<String, Class<?>> foundByClassName = new LinkedHashMap<>();
        for (String basePackage : packagesToScan) {
            for (BeanDefinition beanDefinition : scanner.findCandidateComponents(basePackage + ".entity")) {
                String className = beanDefinition.getBeanClassName();
                if (className == null || foundByClassName.containsKey(className)) {
                    continue;
                }
                try {
                    foundByClassName.put(className, Class.forName(className));
                } catch (ClassNotFoundException ex) {
                    // Unreachable in practice -- the scanner only returns classes it already loaded
                    // bytecode for -- but Class.forName's checked exception still has to go somewhere.
                }
            }
        }
        return foundByClassName.values();
    }

    public Set<String> names() {
        return byName.keySet();
    }

    public Collection<Class<?>> classes() {
        return byName.values();
    }

    /** @throws IllegalArgumentException if no {@code @SyncToMeilisearch} entity has this simple name */
    public Class<?> resolve(String simpleName) {
        Class<?> entityClass = byName.get(simpleName);
        if (entityClass == null) {
            throw new IllegalArgumentException("Unknown @SyncToMeilisearch entity '" + simpleName
                    + "' -- known: " + String.join(", ", names()));
        }
        return entityClass;
    }
}

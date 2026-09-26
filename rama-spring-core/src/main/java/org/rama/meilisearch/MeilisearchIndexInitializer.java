package org.rama.meilisearch;

import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Index;
import jakarta.annotation.PostConstruct;
import org.rama.annotation.SyncToMeilisearch;
import org.rama.meilisearch.service.MeilisearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class MeilisearchIndexInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(MeilisearchIndexInitializer.class);

    /**
     * The starter's own root package.
     *
     * <p>{@code basePackages} is supplied from {@code AutoConfigurationPackages}, i.e. the
     * <em>application's</em> package. The starter's own {@code @SyncToMeilisearch} entities
     * (currently {@code org.rama.entity.master.MasterItem}) therefore fell outside the scan
     * and their index settings were never applied.
     *
     * <p>The failure was silent and deferred: Meilisearch auto-creates an index with empty
     * settings on the first document write, so syncing worked and only <em>filtered</em>
     * queries blew up at runtime with "Attribute `groupKey` is not filterable. This index
     * does not have configured filterable attributes." — breaking every master-data typeahead.
     *
     * <p>Scanning this package alongside the application's keeps starter-owned entities
     * initialized no matter which application hosts them.
     */
    private static final String STARTER_BASE_PACKAGE = "org.rama";

    private final Client meilisearchClient;
    private final MeilisearchService meilisearchService;
    private final List<String> basePackages;
    private final List<MeilisearchIndexSettingsResolver> settingsResolvers;
    private final EnsuredMeilisearchIndexes ensuredIndexes;

    public MeilisearchIndexInitializer(Client meilisearchClient, MeilisearchService meilisearchService, List<String> basePackages,
                                        List<MeilisearchIndexSettingsResolver> settingsResolvers, EnsuredMeilisearchIndexes ensuredIndexes) {
        this.meilisearchClient = meilisearchClient;
        this.meilisearchService = meilisearchService;
        this.basePackages = basePackages;
        this.settingsResolvers = settingsResolvers;
        this.ensuredIndexes = ensuredIndexes;
    }

    @PostConstruct
    public void initializeIndexes() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(SyncToMeilisearch.class));

        // An application may legitimately BE org.rama (or nest under it), so de-duplicate the
        // packages, and de-duplicate the classes too — overlapping base packages would
        // otherwise initialize the same index twice.
        Set<String> packagesToScan = new LinkedHashSet<>(basePackages);
        packagesToScan.add(STARTER_BASE_PACKAGE);

        Set<String> initializedClassNames = new HashSet<>();
        for (String basePackage : packagesToScan) {
            for (BeanDefinition beanDefinition : scanner.findCandidateComponents(basePackage + ".entity")) {
                String className = beanDefinition.getBeanClassName();
                if (className == null || !initializedClassNames.add(className)) {
                    continue;
                }
                Class<?> clazz;
                try {
                    clazz = Class.forName(className);
                } catch (ClassNotFoundException ex) {
                    continue;
                }
                initializeIndex(clazz);
            }
        }
    }

    private void initializeIndex(Class<?> clazz) {
        SyncToMeilisearch annotation = clazz.getAnnotation(SyncToMeilisearch.class);
        if (!annotation.indexNameField().isEmpty()) {
            initializeSplitIndexes(clazz, annotation);
            return;
        }
        try {
            String indexName = meilisearchService.resolveIndexName(clazz);
            String primaryKey = meilisearchService.resolvePrimaryKey(clazz);
            Index index = MeilisearchIndexes.getOrCreate(meilisearchClient, indexName, primaryKey);
            MeilisearchIndexSettingsApplier.apply(index, MeilisearchIndexSettings.fromAnnotation(annotation));
        } catch (Exception ex) {
            LOGGER.error("Failed to sync index for class '{}': {}", clazz.getSimpleName(), ex.getMessage(), ex);
        }
    }

    /**
     * Eagerly creates/configures every split index a {@link MeilisearchIndexSettingsResolver}
     * bean names for {@code clazz} -- the only split indexes knowable without a database query
     * (see {@link SyncToMeilisearch#indexNameField()}). This is what lets changing a resolver's
     * settings and restarting the app take effect immediately: any OTHER split-field value this
     * entity happens to take on (no resolver registered for it) still gets created/configured
     * lazily instead, the first time {@code MeilisearchService.sync()} sees it.
     */
    private void initializeSplitIndexes(Class<?> clazz, SyncToMeilisearch annotation) {
        MeilisearchIndexSettings base = MeilisearchIndexSettings.fromAnnotation(annotation);
        for (MeilisearchIndexSettingsResolver resolver : settingsResolvers) {
            if (resolver.entityClass() != clazz) {
                continue;
            }
            String indexName = meilisearchService.resolveIndexName(clazz, resolver.splitFieldValue());
            boolean appliedByThisResolver = ensuredIndexes.ensureInitialized(indexName, () -> {
                try {
                    String primaryKey = meilisearchService.resolvePrimaryKey(clazz);
                    Index index = MeilisearchIndexes.getOrCreate(meilisearchClient, indexName, primaryKey);
                    MeilisearchIndexSettingsApplier.apply(index, resolver.settings().layeredOver(base));
                } catch (Exception ex) {
                    LOGGER.error("Failed to sync split index '{}' for class '{}': {}", indexName, clazz.getSimpleName(), ex.getMessage(), ex);
                }
            });
            if (!appliedByThisResolver) {
                LOGGER.warn("Two MeilisearchIndexSettingsResolver beans both claim {} + \"{}\" -- "
                                + "only the first one registered was applied to index '{}'",
                        clazz.getSimpleName(), resolver.splitFieldValue(), indexName);
            }
        }
    }
}

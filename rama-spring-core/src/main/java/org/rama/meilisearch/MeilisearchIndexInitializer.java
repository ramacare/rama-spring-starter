package org.rama.meilisearch;

import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.exceptions.MeilisearchException;
import jakarta.annotation.PostConstruct;
import org.rama.annotation.SyncToMeilisearch;
import org.rama.meilisearch.service.MeilisearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.util.Arrays;
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

    public MeilisearchIndexInitializer(Client meilisearchClient, MeilisearchService meilisearchService, List<String> basePackages) {
        this.meilisearchClient = meilisearchClient;
        this.meilisearchService = meilisearchService;
        this.basePackages = basePackages;
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
        try {
            String indexName = meilisearchService.resolveIndexName(clazz);
            String primaryKey = meilisearchService.resolvePrimaryKey(clazz);

            Index index;
            try {
                index = meilisearchClient.getIndex(indexName);
                if (index.getPrimaryKey() == null || !index.getPrimaryKey().equals(primaryKey)) {
                    meilisearchClient.updateIndex(indexName, primaryKey);
                }
            } catch (MeilisearchException ex) {
                meilisearchClient.createIndex(indexName, primaryKey);
                index = meilisearchClient.index(indexName);
            }

            String[] definedSearchable = annotation.searchableAttributes();
            if (definedSearchable.length > 0) {
                String[] currentSearchable = index.getSearchableAttributesSettings();
                if (!Arrays.equals(currentSearchable, definedSearchable)) {
                    index.updateSearchableAttributesSettings(definedSearchable);
                }
            }

            String[] definedFilterable = annotation.filterableAttributes();
            if (definedFilterable.length > 0) {
                String[] currentFilterable = index.getFilterableAttributesSettings();
                if (!Arrays.equals(currentFilterable, definedFilterable)) {
                    index.updateFilterableAttributesSettings(definedFilterable);
                }
            }
        } catch (Exception ex) {
            LOGGER.error("Failed to sync index for class '{}': {}", clazz.getSimpleName(), ex.getMessage(), ex);
        }
    }
}

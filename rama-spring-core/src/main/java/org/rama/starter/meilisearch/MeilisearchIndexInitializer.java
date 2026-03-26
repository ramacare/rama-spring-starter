package org.rama.starter.meilisearch;

import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.exceptions.MeilisearchException;
import jakarta.annotation.PostConstruct;
import org.rama.starter.annotation.SyncToMeilisearch;
import org.rama.starter.meilisearch.service.MeilisearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.util.Arrays;
import java.util.List;

public class MeilisearchIndexInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(MeilisearchIndexInitializer.class);

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

        for (String basePackage : basePackages) {
            for (BeanDefinition beanDefinition : scanner.findCandidateComponents(basePackage + ".entity")) {
                Class<?> clazz;
                try {
                    clazz = Class.forName(beanDefinition.getBeanClassName());
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

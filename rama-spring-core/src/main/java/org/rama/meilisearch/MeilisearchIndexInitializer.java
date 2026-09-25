package org.rama.meilisearch;

import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.exceptions.MeilisearchException;
import com.meilisearch.sdk.model.TypoTolerance;
import jakarta.annotation.PostConstruct;
import org.rama.annotation.SyncToMeilisearch;
import org.rama.meilisearch.service.MeilisearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

            String[] definedSortable = annotation.sortableAttributes();
            if (definedSortable.length > 0) {
                String[] currentSortable = index.getSortableAttributesSettings();
                if (!Arrays.equals(currentSortable, definedSortable)) {
                    index.updateSortableAttributesSettings(definedSortable);
                }
            }

            String[] definedRankingRules = annotation.rankingRules();
            if (definedRankingRules.length > 0) {
                String[] currentRankingRules = index.getRankingRulesSettings();
                if (!Arrays.equals(currentRankingRules, definedRankingRules)) {
                    index.updateRankingRulesSettings(definedRankingRules);
                }
            }

            // No idempotency check here (unlike the array-valued settings above): TypoTolerance
            // has no equals() override, and hand-comparing every field against
            // index.getTypoToleranceSettings() isn't worth it for a call that only ever runs
            // once at startup.
            if (!annotation.typoToleranceEnabled()
                    || annotation.typoToleranceMinWordSizeOneTypo() >= 0
                    || annotation.typoToleranceMinWordSizeTwoTypos() >= 0) {
                TypoTolerance typoTolerance = new TypoTolerance();
                typoTolerance.setEnabled(annotation.typoToleranceEnabled());
                HashMap<String, Integer> minWordSizeForTypos = new HashMap<>();
                if (annotation.typoToleranceMinWordSizeOneTypo() >= 0) {
                    minWordSizeForTypos.put("oneTypo", annotation.typoToleranceMinWordSizeOneTypo());
                }
                if (annotation.typoToleranceMinWordSizeTwoTypos() >= 0) {
                    minWordSizeForTypos.put("twoTypos", annotation.typoToleranceMinWordSizeTwoTypos());
                }
                if (!minWordSizeForTypos.isEmpty()) {
                    typoTolerance.setMinWordSizeForTypos(minWordSizeForTypos);
                }
                index.updateTypoToleranceSettings(typoTolerance);
            }

            SyncToMeilisearch.Synonym[] definedSynonyms = annotation.synonyms();
            if (definedSynonyms.length > 0) {
                Map<String, String[]> synonyms = new LinkedHashMap<>();
                for (SyncToMeilisearch.Synonym synonym : definedSynonyms) {
                    synonyms.put(synonym.word(), synonym.mappings());
                }
                index.updateSynonymsSettings(synonyms);
            }
        } catch (Exception ex) {
            LOGGER.error("Failed to sync index for class '{}': {}", clazz.getSimpleName(), ex.getMessage(), ex);
        }
    }
}

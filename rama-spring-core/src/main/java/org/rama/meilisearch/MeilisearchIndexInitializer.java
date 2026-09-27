package org.rama.meilisearch;

import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Index;
import jakarta.annotation.PostConstruct;
import org.rama.annotation.SyncToMeilisearch;
import org.rama.meilisearch.service.MeilisearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class MeilisearchIndexInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(MeilisearchIndexInitializer.class);

    private final Client meilisearchClient;
    private final MeilisearchService meilisearchService;
    private final SyncToMeilisearchEntities syncToMeilisearchEntities;
    private final List<MeilisearchIndexSettingsResolver> settingsResolvers;
    private final EnsuredMeilisearchIndexes ensuredIndexes;

    public MeilisearchIndexInitializer(Client meilisearchClient, MeilisearchService meilisearchService, SyncToMeilisearchEntities syncToMeilisearchEntities,
                                        List<MeilisearchIndexSettingsResolver> settingsResolvers, EnsuredMeilisearchIndexes ensuredIndexes) {
        this.meilisearchClient = meilisearchClient;
        this.meilisearchService = meilisearchService;
        this.syncToMeilisearchEntities = syncToMeilisearchEntities;
        this.settingsResolvers = settingsResolvers;
        this.ensuredIndexes = ensuredIndexes;
    }

    @PostConstruct
    public void initializeIndexes() {
        for (Class<?> clazz : syncToMeilisearchEntities.classes()) {
            initializeIndex(clazz);
        }
    }

    private void initializeIndex(Class<?> clazz) {
        SyncToMeilisearch annotation = clazz.getAnnotation(SyncToMeilisearch.class);
        if (!annotation.indexNameField().isEmpty()) {
            initializeSplitIndexes(clazz);
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
    private void initializeSplitIndexes(Class<?> clazz) {
        for (MeilisearchIndexSettingsResolver resolver : settingsResolvers) {
            if (resolver.entityClass() != clazz) {
                continue;
            }
            String indexName = meilisearchService.resolveIndexName(clazz, resolver.splitFieldValue());
            boolean appliedByThisResolver = ensuredIndexes.ensureInitialized(indexName, () -> {
                try {
                    String primaryKey = meilisearchService.resolvePrimaryKey(clazz);
                    Index index = MeilisearchIndexes.getOrCreate(meilisearchClient, indexName, primaryKey);
                    MeilisearchIndexSettings settings = meilisearchService.resolveSplitIndexSettings(clazz, resolver.splitFieldValue());
                    MeilisearchIndexSettingsApplier.apply(index, settings);
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

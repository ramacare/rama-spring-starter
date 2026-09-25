package org.rama.meilisearch;

/**
 * Supplies per-split-index Meilisearch settings for an entity using
 * {@link org.rama.annotation.SyncToMeilisearch#indexNameField()}. Register one as a Spring bean
 * per entity that needs genuinely different settings across its split indexes; an entity with no
 * matching resolver bean (or no resolver bean at all) falls back to its own
 * {@code @SyncToMeilisearch} settings for every split index, same as an unsplit entity.
 *
 * <p>Consulted lazily, the first time an instance with a not-yet-seen split-field value is
 * synced -- never at startup, since there is no way to enumerate every value in advance without a
 * database query.
 */
public interface MeilisearchIndexSettingsResolver {

    /** Whether this resolver supplies settings for {@code entityClass}. Only the first matching
     * resolver (in Spring bean registration order) is used for a given entity. */
    boolean supports(Class<?> entityClass);

    /**
     * Settings for the split index identified by {@code splitFieldValue} -- the raw (unsanitized)
     * value of the entity's {@code @SyncToMeilisearch(indexNameField = ...)} field, e.g.
     * {@code "$SNOMEDCT"} for a {@code MasterItem} split on {@code groupKey}.
     *
     * <p>A non-{@code null} result is layered on top of {@code entityClass}'s own
     * {@code @SyncToMeilisearch} settings (see {@link MeilisearchIndexSettings#layeredOver}), not
     * a full replacement -- override only what's different for {@code splitFieldValue} (e.g. just
     * {@code synonyms}); every other setting the annotation declares still applies to this index
     * without needing to repeat it here.
     *
     * @return the settings to layer over {@code entityClass}'s own {@code @SyncToMeilisearch}
     * settings, or {@code null} to use those unmodified for this particular value
     */
    MeilisearchIndexSettings resolve(Class<?> entityClass, String splitFieldValue);
}

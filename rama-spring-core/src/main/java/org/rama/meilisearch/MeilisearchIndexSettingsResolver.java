package org.rama.meilisearch;

/**
 * One split index's Meilisearch settings, for an entity using
 * {@link org.rama.annotation.SyncToMeilisearch#indexNameField()}. Register one Spring bean per
 * split index that needs settings different from the entity's own {@code @SyncToMeilisearch} --
 * e.g. one bean for {@code MasterItem} + {@code "$SNOMEDCT"}, a separate bean for
 * {@code MasterItem} + {@code "$ICD10"}. A split-field value with no matching bean uses the
 * entity's own {@code @SyncToMeilisearch} settings unmodified, same as an unsplit entity.
 *
 * <p>Because a bean declares its own {@link #entityClass()} and {@link #splitFieldValue()}
 * statically (no entity instance needed to find out), every registered resolver is known at
 * startup -- unlike split-field <em>values</em> in general, which can't be enumerated without a
 * database query. This lets {@code MeilisearchIndexInitializer} eagerly create/configure every
 * split index a resolver names, the same as it always has for unsplit entities, instead of
 * waiting for the next sync of that value: restarting the app after changing a resolver's
 * {@link #settings()} re-applies it immediately, with no dependency on new data arriving.
 *
 * <p>{@link #settings()} is layered on top of the entity's own {@code @SyncToMeilisearch}
 * settings (see {@link MeilisearchIndexSettings#layeredOver}), not a full replacement -- override
 * only what's different for this one index (e.g. just {@code synonyms}); every other setting the
 * annotation declares still applies without needing to repeat it here.
 */
public interface MeilisearchIndexSettingsResolver {

    /** The entity type this resolver's settings apply to. */
    Class<?> entityClass();

    /** The one {@code @SyncToMeilisearch(indexNameField = ...)} value this resolver's settings
     * apply to, e.g. {@code "$SNOMEDCT"} for a {@code MasterItem} split on {@code groupKey}. */
    String splitFieldValue();

    /** The settings for this one index. */
    MeilisearchIndexSettings settings();
}

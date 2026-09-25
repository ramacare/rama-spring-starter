package org.rama.annotation;

import org.rama.meilisearch.mapper.DefaultMeilisearchMapper;
import org.rama.meilisearch.mapper.IMeilisearchMapper;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SyncToMeilisearch {
    String indexName() default "";
    String primaryKey() default "id";
    String[] searchableAttributes() default {};
    String[] filterableAttributes() default {};
    Class<? extends IMeilisearchMapper> mapperClass() default DefaultMeilisearchMapper.class;

    /** Empty (default) leaves the index's current sortable attributes untouched. */
    String[] sortableAttributes() default {};

    /** Empty (default) leaves the index's current ranking rules untouched -- Meilisearch's own
     * default order applies. A custom order can inject a sortable attribute as a tiebreaker at a
     * specific priority, e.g. {"words", "typo", "proximity", "attribute", "sort",
     * "exactness", "myAttribute:asc"}. */
    String[] rankingRules() default {};

    /** {@code false} explicitly disables Meilisearch's typo tolerance for this index. */
    boolean typoToleranceEnabled() default true;

    /** {@code -1} (default) leaves Meilisearch's own threshold (4) untouched. */
    int typoToleranceMinWordSizeOneTypo() default -1;

    /** {@code -1} (default) leaves Meilisearch's own threshold (9) untouched. */
    int typoToleranceMinWordSizeTwoTypos() default -1;

    /** Empty (default) leaves the index's current synonyms untouched. */
    Synonym[] synonyms() default {};

    @interface Synonym {
        /** Searching {@code word} also matches documents containing any of {@link #mappings()} --
         * one-way, not the reverse. A genuinely mutual pair needs a second {@code @Synonym} entry
         * with {@code word} and {@code mappings} swapped. */
        String word();
        String[] mappings();
    }

    /**
     * Empty (default): one Meilisearch index for every instance of this entity, exactly as today.
     * Non-empty: the named field's value (read via reflection off each entity instance, sanitized
     * the same way primary keys are) is appended to the index name, so instances with different
     * field values land in different indexes -- e.g. {@code indexNameField = "groupKey"} on
     * {@code MasterItem} would split {@code $ICD10}, {@code $SNOMEDCT}, etc. into their own
     * indexes instead of sharing one.
     *
     * <p>This exists because every other setting on this annotation is a Meilisearch index-level
     * setting -- Meilisearch has no per-document-filtered variant of any of them, so instances
     * that genuinely need different tuning must live in different indexes. A split index's
     * settings come from whichever registered
     * {@link org.rama.meilisearch.MeilisearchIndexSettingsResolver} bean supports this entity
     * class, keyed by the field value; with no matching resolver (or no resolver bean at all),
     * every split index falls back to this annotation's own settings, same as an unsplit entity.
     *
     * <p>Split indexes are NOT created at startup (there is no way to enumerate every field value
     * in advance without a database query) -- each is created and configured lazily, the first
     * time an instance with a not-yet-seen value is synced.
     */
    String indexNameField() default "";
}

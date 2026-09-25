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
}

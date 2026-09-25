package org.rama.meilisearch;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;

import static java.util.Collections.emptyMap;

/**
 * A plain, code-buildable equivalent of {@link org.rama.annotation.SyncToMeilisearch}'s settings
 * attributes -- {@code null}/empty means "leave this setting untouched", the same convention the
 * annotation uses, so applying one built here behaves identically to applying the annotation
 * directly (see {@link MeilisearchIndexInitializer}, which both share).
 *
 * <p>Exists for {@link MeilisearchIndexSettingsResolver}: an annotation's compile-time constants
 * can't express "different settings per split index" (see
 * {@link org.rama.annotation.SyncToMeilisearch#indexNameField()}) without deeply nested,
 * duplicated annotation types for every setting -- a plain object supports the ordinary Java code
 * a resolver needs (loops, conditionals, externalized config).
 */
@Getter
@Builder
public class MeilisearchIndexSettings {
    @Builder.Default
    private String[] searchableAttributes = new String[0];
    @Builder.Default
    private String[] filterableAttributes = new String[0];
    @Builder.Default
    private String[] sortableAttributes = new String[0];
    @Builder.Default
    private String[] rankingRules = new String[0];
    /** {@code null} leaves Meilisearch's typo tolerance enablement untouched. */
    private Boolean typoToleranceEnabled;
    /** {@code null} leaves Meilisearch's own threshold (4) untouched. */
    private Integer typoToleranceMinWordSizeOneTypo;
    /** {@code null} leaves Meilisearch's own threshold (9) untouched. */
    private Integer typoToleranceMinWordSizeTwoTypos;
    @Builder.Default
    private Map<String, String[]> synonyms = emptyMap();

    /** The settings a plain (unsplit, or split-with-no-matching-resolver) entity already gets
     * today, straight off its {@code @SyncToMeilisearch}. */
    public static MeilisearchIndexSettings fromAnnotation(org.rama.annotation.SyncToMeilisearch annotation) {
        MeilisearchIndexSettingsBuilder builder = MeilisearchIndexSettings.builder()
                .searchableAttributes(annotation.searchableAttributes())
                .filterableAttributes(annotation.filterableAttributes())
                .sortableAttributes(annotation.sortableAttributes())
                .rankingRules(annotation.rankingRules());
        if (!annotation.typoToleranceEnabled()
                || annotation.typoToleranceMinWordSizeOneTypo() >= 0
                || annotation.typoToleranceMinWordSizeTwoTypos() >= 0) {
            builder.typoToleranceEnabled(annotation.typoToleranceEnabled());
            if (annotation.typoToleranceMinWordSizeOneTypo() >= 0) {
                builder.typoToleranceMinWordSizeOneTypo(annotation.typoToleranceMinWordSizeOneTypo());
            }
            if (annotation.typoToleranceMinWordSizeTwoTypos() >= 0) {
                builder.typoToleranceMinWordSizeTwoTypos(annotation.typoToleranceMinWordSizeTwoTypos());
            }
        }
        if (annotation.synonyms().length > 0) {
            Map<String, String[]> synonyms = new java.util.LinkedHashMap<>();
            for (org.rama.annotation.SyncToMeilisearch.Synonym synonym : annotation.synonyms()) {
                synonyms.put(synonym.word(), synonym.mappings());
            }
            builder.synonyms(synonyms);
        }
        return builder.build();
    }
}

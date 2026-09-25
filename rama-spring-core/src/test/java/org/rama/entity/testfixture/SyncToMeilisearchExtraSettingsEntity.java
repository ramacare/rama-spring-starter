package org.rama.entity.testfixture;

import org.rama.annotation.SyncToMeilisearch;

/**
 * Deliberately leaves searchableAttributes/filterableAttributes at their empty defaults so this
 * fixture never calls updateSearchableAttributesSettings/updateFilterableAttributesSettings --
 * MeilisearchIndexInitializerTest's existing MasterItem-based assertions on those two settings
 * count invocations against a single shared mock Index across every scanned entity, and would
 * break if this fixture triggered them too.
 */
@SyncToMeilisearch(
        sortableAttributes = {"termLength"},
        rankingRules = {"words", "typo", "proximity", "attribute", "sort", "exactness", "termLength:asc"},
        // Deliberately different from MasterItem's real thresholds (4/8) so a test isolating this
        // fixture's specific call among several updateTypoToleranceSettings invocations on the
        // shared mock Index can't accidentally match MasterItem's instead.
        typoToleranceMinWordSizeOneTypo = 3,
        typoToleranceMinWordSizeTwoTypos = 6,
        synonyms = {
                @SyncToMeilisearch.Synonym(word = "fixtureWord", mappings = {"fixtureSynonym"}),
        }
)
public class SyncToMeilisearchExtraSettingsEntity {
    private String id;
}

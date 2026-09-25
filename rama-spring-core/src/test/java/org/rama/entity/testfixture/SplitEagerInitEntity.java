package org.rama.entity.testfixture;

import org.rama.annotation.SyncToMeilisearch;

/** Split entity used to test MeilisearchIndexInitializer's eager, resolver-driven split-index
 * initialization (never used with sync() -- MeilisearchServiceSplitIndexTest's
 * SplitByCategoryEntity covers the lazy sync() path instead, so the two don't interfere). */
@SyncToMeilisearch(indexName = "spliteagerinitentity", indexNameField = "region", filterableAttributes = {"region"})
public class SplitEagerInitEntity {
    private String id;
    private String region;
}

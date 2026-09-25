package org.rama.entity.testfixture;

import org.rama.annotation.SyncToMeilisearch;

/** A minimal entity split on {@code category} for MeilisearchService's split-index sync tests.
 * Declares filterableAttributes so a resolver test can confirm this survives even when a matching
 * resolver overrides a different, unrelated setting for a given split value -- see
 * MeilisearchIndexSettings#layeredOver. */
@SyncToMeilisearch(indexName = "splittestentity", indexNameField = "category", filterableAttributes = {"category"})
public class SplitByCategoryEntity {
    private String id;
    private String category;
    private String name;

    public SplitByCategoryEntity() {
    }

    public SplitByCategoryEntity(String id, String category, String name) {
        this.id = id;
        this.category = category;
        this.name = name;
    }

    public String getId() {
        return id;
    }

    public String getCategory() {
        return category;
    }

    public String getName() {
        return name;
    }
}

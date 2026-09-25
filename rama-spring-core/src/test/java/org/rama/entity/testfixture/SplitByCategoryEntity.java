package org.rama.entity.testfixture;

import org.rama.annotation.SyncToMeilisearch;

/** A minimal entity split on {@code category} for MeilisearchService's split-index sync tests. */
@SyncToMeilisearch(indexName = "splittestentity", indexNameField = "category")
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

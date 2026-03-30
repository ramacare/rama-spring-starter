package org.rama.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageableMeta {
    private Integer page;
    private Integer perPage;
    private List<SortCriteria> sortBy;
    private Integer totalPages;
    private Long totalItems;

    public static PageableMeta of(Integer page, Integer perPage, List<SortCriteria> sortBy, Integer totalPages, Long totalItems) {
        return new PageableMeta(page, perPage, sortBy, totalPages, totalItems);
    }
}

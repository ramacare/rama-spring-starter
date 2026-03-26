package org.rama.starter.entity;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.List;

public class PageableInput {
    private int page = 0;
    private int perPage = 20;
    private List<SortCriteria> sortBy = new ArrayList<>();

    public PageRequest toPageRequest() {
        if (sortBy == null || sortBy.isEmpty()) {
            return PageRequest.of(page, perPage);
        }

        List<Sort.Order> orders = sortBy.stream()
                .map(sort -> new Sort.Order(Sort.Direction.fromString(sort.order()), sort.key()))
                .toList();

        return PageRequest.of(page, perPage, Sort.by(orders));
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getPerPage() {
        return perPage;
    }

    public void setPerPage(int perPage) {
        this.perPage = perPage;
    }

    public List<SortCriteria> getSortBy() {
        return sortBy;
    }

    public void setSortBy(List<SortCriteria> sortBy) {
        this.sortBy = sortBy;
    }
}

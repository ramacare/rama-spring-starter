package org.rama.starter.entity;

import java.util.List;

public record PageableMeta(int page, int perPage, List<SortCriteria> sortBy, int totalPages, long totalItems) {
}

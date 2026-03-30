package org.rama.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageableInput {
    private static final Logger LOGGER = LoggerFactory.getLogger(PageableInput.class);

    @Builder.Default
    private Integer page = 1;
    private Integer perPage;
    private List<SortCriteria> sortBy;

    public static PageableInput of(Integer page, Integer perPage, List<SortCriteria> sortBy) {
        return PageableInput.builder()
                .page(page == null ? 1 : page)
                .perPage(perPage)
                .sortBy(sortBy)
                .build();
    }

    public PageRequest toPageRequest() {
        PageRequest pr = (this.perPage > 0) ? PageRequest.of(this.page - 1, this.perPage) : PageRequest.of(0, Integer.MAX_VALUE);
        Sort sort = Sort.unsorted();

        if (this.sortBy != null) {
            for (SortCriteria sortCriteria : this.sortBy) {
                Sort.Direction sortDirection = Sort.Direction.ASC;
                try {
                    sortDirection = Sort.Direction.fromString(sortCriteria.getOrder().toUpperCase());
                } catch (Exception e) {
                    LOGGER.error("Error casting sort direction from String : {}", sortCriteria.getOrder());
                    LOGGER.error(e.getMessage());
                }
                sort = sort.and(Sort.by(sortDirection, sortCriteria.getKey()));
            }
        }
        pr = pr.withSort(sort);

        return pr;
    }
}

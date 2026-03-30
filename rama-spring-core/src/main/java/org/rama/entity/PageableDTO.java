package org.rama.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.stream.Collectors;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageableDTO<T> {
    private PageableMeta meta;
    private List<T> data;

    public static <T> PageableDTO<T> of(PageableMeta meta, List<T> data) {
        return new PageableDTO<>(meta, data);
    }

    public static <T> PageableDTO<T> of(Page<T> page) {
        Sort sort = page.getSort();

        List<SortCriteria> sortBy = sort.stream()
                .map(order -> SortCriteria.of(order.getProperty(), order.getDirection().name().toLowerCase()))
                .collect(Collectors.toList());

        int perPage = page.getSize();

        if (page.getNumber() == 0 && page.getSize() == Integer.MAX_VALUE) {
            perPage = -1;
        }

        return PageableDTO.of(PageableMeta.of(page.getNumber() + 1, perPage, sortBy, page.getTotalPages(), page.getTotalElements()), page.getContent());
    }
}

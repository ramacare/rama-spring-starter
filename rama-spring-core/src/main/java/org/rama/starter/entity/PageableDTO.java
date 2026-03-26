package org.rama.starter.entity;

import org.springframework.data.domain.Page;

import java.util.List;

public record PageableDTO<T>(PageableMeta meta, List<T> items) {
    public static <T> PageableDTO<T> of(Page<T> page) {
        return new PageableDTO<>(
                new PageableMeta(page.getNumber(), page.getSize(), List.of(), page.getTotalPages(), page.getTotalElements()),
                page.getContent()
        );
    }
}

package org.rama.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SortCriteria {
    private String key;
    @Builder.Default
    private String order = "asc";

    public static SortCriteria of(String key, String order) {
        return SortCriteria.builder().key(key).order(order == null ? "asc" : order).build();
    }
}

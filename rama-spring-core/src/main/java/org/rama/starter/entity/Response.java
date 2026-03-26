package org.rama.starter.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@Data
@Builder
@AllArgsConstructor(staticName = "of")
@RequiredArgsConstructor(staticName = "of")
public class Response {
    @NonNull
    private Boolean success;
    private String message;
    private Object data;
}

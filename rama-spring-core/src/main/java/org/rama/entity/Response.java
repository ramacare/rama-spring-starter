package org.rama.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Response {
    private Boolean success;
    private String message;
    private Object data;

    public static Response of(Boolean success) {
        return Response.builder().success(success).build();
    }

    public static Response of(Boolean success, String message, Object data) {
        return Response.builder().success(success).message(message).data(data).build();
    }
}

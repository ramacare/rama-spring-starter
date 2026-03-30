package org.rama.entity;

import jakarta.persistence.Embeddable;
import lombok.Data;

@Data
@Embeddable
public class UserstampField {
    private String createdBy;
    private String updatedBy;
}

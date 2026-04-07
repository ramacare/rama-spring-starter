package org.rama.event;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class EntityUpdated implements IEntityEvent<Object> {
    private Object entity;
}

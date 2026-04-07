package org.rama.event;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class EntityCreated implements IEntityEvent<Object> {
    private Object entity;
}

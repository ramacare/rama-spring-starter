package org.rama.event;

import lombok.Getter;

@Getter
public class EntityEmptyEvent implements IEntityEvent<Object> {
    private Object entity;
}

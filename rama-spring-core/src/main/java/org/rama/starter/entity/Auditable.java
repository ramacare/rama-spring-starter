package org.rama.starter.entity;

public interface Auditable {
    UserstampField getUserstampField();
    TimestampField getTimestampField();
    void setUserstampField(UserstampField userstampField);
    void setTimestampField(TimestampField timestampField);
}

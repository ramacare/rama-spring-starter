package org.rama.starter.listener.global;

import org.hibernate.event.spi.PreInsertEvent;
import org.hibernate.event.spi.PreInsertEventListener;
import org.rama.starter.entity.Auditable;
import org.rama.starter.entity.TimestampField;
import org.rama.starter.entity.UserstampField;
import org.rama.starter.service.environment.EnvironmentService;
import org.rama.starter.util.HibernateUtil;

import java.time.OffsetDateTime;

public class GlobalAuditablePreInsertListener implements PreInsertEventListener {
    private final EnvironmentService environmentService;

    public GlobalAuditablePreInsertListener(EnvironmentService environmentService) {
        this.environmentService = environmentService;
    }

    @Override
    public boolean onPreInsert(PreInsertEvent event) {
        if (event.getEntity() instanceof Auditable auditable) {
            OffsetDateTime now = OffsetDateTime.now();
            TimestampField timestampField = auditable.getTimestampField();
            if (timestampField == null) {
                timestampField = new TimestampField();
                auditable.setTimestampField(timestampField);
            }
            timestampField.setCreatedAt(now);
            timestampField.setUpdatedAt(now);
            HibernateUtil.setState(event, "timestampField", timestampField);

            UserstampField userstampField = auditable.getUserstampField();
            if (userstampField == null) {
                userstampField = new UserstampField();
                auditable.setUserstampField(userstampField);
            }
            userstampField.setCreatedBy(environmentService.getCurrentUsername());
            HibernateUtil.setState(event, "userstampField", userstampField);
        }
        return false;
    }
}

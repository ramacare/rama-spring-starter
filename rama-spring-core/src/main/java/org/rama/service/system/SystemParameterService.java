package org.rama.service.system;

import org.rama.entity.system.SystemParameter;
import org.rama.repository.system.SystemParameterRepository;
import org.springframework.transaction.annotation.Transactional;

public class SystemParameterService {
    private final SystemParameterRepository systemParameterRepository;

    public SystemParameterService(SystemParameterRepository systemParameterRepository) {
        this.systemParameterRepository = systemParameterRepository;
    }

    public String getParameter(String parameterKey) {
        SystemParameter parameter = systemParameterRepository.findSystemParameterByParameterKey(parameterKey);
        return parameter == null ? null : parameter.getParameterValue();
    }

    @Transactional
    public void setParameter(String parameterKey, String value) {
        SystemParameter parameter = systemParameterRepository.findByParameterKeyForUpdate(parameterKey)
                .orElseGet(() -> {
                    SystemParameter created = new SystemParameter();
                    created.setParameterKey(parameterKey);
                    return created;
                });
        parameter.setParameterValue(value);
        systemParameterRepository.save(parameter);
    }
}

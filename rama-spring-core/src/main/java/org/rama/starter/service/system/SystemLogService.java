package org.rama.starter.service.system;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.rama.starter.entity.system.SystemLog;
import org.rama.starter.repository.system.SystemLogRepository;
import org.springframework.transaction.annotation.Transactional;

public class SystemLogService {
    private final SystemLogRepository systemLogRepository;
    private final ObjectMapper objectMapper;

    public SystemLogService(SystemLogRepository systemLogRepository, ObjectMapper objectMapper) {
        this.systemLogRepository = systemLogRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void log(SystemLog.LogLevel level, String key, String message, Object detail) {
        SystemLog log = new SystemLog();
        log.setLogLevel(level);
        log.setLogKey(key);
        log.setMessage(message);
        log.setDetail(objectMapper.convertValue(detail, new TypeReference<>() {
        }));
        systemLogRepository.save(log);
    }
}

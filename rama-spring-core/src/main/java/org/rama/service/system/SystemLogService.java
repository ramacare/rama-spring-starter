package org.rama.service.system;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import lombok.RequiredArgsConstructor;
import org.rama.entity.system.SystemLog;
import org.rama.repository.system.SystemLogRepository;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
public class SystemLogService {
    private final SystemLogRepository systemLogRepository;
    private final JsonMapper objectMapper;

    @Transactional
    public void log(SystemLog.LogLevel level, String key, String message, Object detail) {
        SystemLog log = new SystemLog();
        log.setLogLevel(level);
        log.setLogKey(key);
        log.setMessage(message);
        if (detail != null) {
            log.setDetail(objectMapper.convertValue(detail, new TypeReference<>() {}));
        }
        systemLogRepository.save(log);
    }

    @Transactional
    public void log(SystemLog.LogLevel level, String key, String message) {
        log(level, key, message, null);
    }

    @Transactional
    public void info(String key, String message, Object detail) {
        log(SystemLog.LogLevel.INFO, key, message, detail);
    }

    @Transactional
    public void info(String key, String message) {
        info(key, message, null);
    }

    @Transactional
    public void warn(String key, String message, Object detail) {
        log(SystemLog.LogLevel.WARN, key, message, detail);
    }

    @Transactional
    public void warn(String key, String message) {
        warn(key, message, null);
    }

    @Transactional
    public void error(String key, String message, Object detail) {
        log(SystemLog.LogLevel.ERROR, key, message, detail);
    }

    @Transactional
    public void error(String key, String message) {
        error(key, message, null);
    }

    @Transactional
    public void debug(String key, String message, Object detail) {
        log(SystemLog.LogLevel.DEBUG, key, message, detail);
    }

    @Transactional
    public void debug(String key, String message) {
        debug(key, message, null);
    }
}

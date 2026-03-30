package org.rama.meilisearch.service;

import com.meilisearch.sdk.model.Task;
import com.meilisearch.sdk.model.TaskInfo;
import org.rama.service.system.SystemLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

public class LoggingMeilisearchErrorHandler implements MeilisearchErrorHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingMeilisearchErrorHandler.class);

    private final ObjectProvider<SystemLogService> systemLogServiceProvider;

    public LoggingMeilisearchErrorHandler(ObjectProvider<SystemLogService> systemLogServiceProvider) {
        this.systemLogServiceProvider = systemLogServiceProvider;
    }

    @Override
    public void handleTaskFailure(TaskInfo taskInfo, Task task, Object entity) {
        String errorMessage = task.getError() != null ? task.getError().getMessage() : "Unknown error";
        String errorCode = task.getError() != null ? task.getError().getCode() : "N/A";
        String errorType = task.getError() != null ? task.getError().getType() : "N/A";

        String message = String.format(
                "Meilisearch task FAILED [taskId=%s, index=%s, type=%s, code=%s, message=%s]",
                taskInfo.getTaskUid(),
                task.getIndexUid(),
                errorType,
                errorCode,
                errorMessage
        );

        LOGGER.error(message);
        systemLogServiceProvider.ifAvailable(sls -> sls.error("meilisearch_task", message, entity));
    }
}

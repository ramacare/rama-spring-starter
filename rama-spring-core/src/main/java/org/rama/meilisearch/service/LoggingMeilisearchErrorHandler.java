package org.rama.meilisearch.service;

import com.meilisearch.sdk.model.Task;
import com.meilisearch.sdk.model.TaskInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoggingMeilisearchErrorHandler implements MeilisearchErrorHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingMeilisearchErrorHandler.class);

    @Override
    public void handleTaskFailure(TaskInfo taskInfo, Task task, Object entity) {
        String errorMessage = task.getError() != null ? task.getError().getMessage() : "Unknown error";
        String errorCode = task.getError() != null ? task.getError().getCode() : "N/A";
        String errorType = task.getError() != null ? task.getError().getType() : "N/A";
        LOGGER.error(
                "Meilisearch task FAILED [taskId={}, index={}, type={}, code={}, message={}, entityClass={}]",
                taskInfo.getTaskUid(),
                task.getIndexUid(),
                errorType,
                errorCode,
                errorMessage,
                entity == null ? null : entity.getClass().getName()
        );
    }
}

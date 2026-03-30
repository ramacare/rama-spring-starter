package org.rama.meilisearch.service;

import com.meilisearch.sdk.model.Task;
import com.meilisearch.sdk.model.TaskInfo;

public interface MeilisearchErrorHandler {
    void handleTaskFailure(TaskInfo taskInfo, Task task, Object entity);
}

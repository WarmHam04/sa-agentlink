package io.agentlink.application;

import io.agentlink.domain.TaskEvent;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface TaskEventBus {
    TaskEvent publish(TaskEvent event);
    SseEmitter subscribe(String contextId, long afterOffset);
}

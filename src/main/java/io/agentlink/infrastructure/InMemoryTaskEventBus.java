package io.agentlink.infrastructure;

import io.agentlink.application.TaskEventBus;
import io.agentlink.domain.TaskEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class InMemoryTaskEventBus implements TaskEventBus {
    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, CopyOnWriteArrayList<TaskEvent>> history = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final int historyLimit;

    public InMemoryTaskEventBus(@Value("${agentlink.events.history-limit:1000}") int historyLimit) {
        this.historyLimit = historyLimit;
    }

    @Override
    public TaskEvent publish(TaskEvent event) {
        TaskEvent sequenced = event.withOffset(sequence.incrementAndGet());
        CopyOnWriteArrayList<TaskEvent> events = history.computeIfAbsent(event.contextId(), ignored -> new CopyOnWriteArrayList<>());
        events.add(sequenced);
        while (events.size() > historyLimit) {
            events.remove(0);
        }
        subscribers.getOrDefault(event.contextId(), new CopyOnWriteArrayList<>())
                .removeIf(emitter -> !send(emitter, sequenced));
        return sequenced;
    }

    @Override
    public SseEmitter subscribe(String contextId, long afterOffset) {
        SseEmitter emitter = new SseEmitter(0L);
        CopyOnWriteArrayList<SseEmitter> contextSubscribers =
                subscribers.computeIfAbsent(contextId, ignored -> new CopyOnWriteArrayList<>());
        contextSubscribers.add(emitter);
        emitter.onCompletion(() -> contextSubscribers.remove(emitter));
        emitter.onTimeout(() -> contextSubscribers.remove(emitter));
        emitter.onError(ignored -> contextSubscribers.remove(emitter));

        List<TaskEvent> replay = history.getOrDefault(contextId, new CopyOnWriteArrayList<>()).stream()
                .filter(event -> event.offset() > afterOffset)
                .toList();
        replay.forEach(event -> send(emitter, event));
        return emitter;
    }

    private boolean send(SseEmitter emitter, TaskEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .id(Long.toString(event.offset()))
                    .name("task-status")
                    .data(event));
            return true;
        } catch (IOException | IllegalStateException failure) {
            emitter.completeWithError(failure);
            return false;
        }
    }
}

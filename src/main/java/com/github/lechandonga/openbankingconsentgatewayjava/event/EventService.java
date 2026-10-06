package com.github.lechandonga.openbankingconsentgatewayjava.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 事件入库/入队服务（内存实现）。
 *
 * 去重语义：以 eventId（调用方指定或由 type+subjectId 派生）为唯一键，
 * publishIfAbsent 保证同一事件不会因重试/并发被重复入队。
 */
@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final Map<String, Event> events = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final EventDispatcher dispatcher;

    public EventService(ObjectMapper objectMapper, EventDispatcher dispatcher) {
        this.objectMapper = objectMapper;
        this.dispatcher = dispatcher;
    }

    /**
     * 发布事件；同 eventId 已存在时直接复用，绝不重复入队。
     * eventId 缺省时由 type + subjectId 确定性派生。
     */
    public Event publishIfAbsent(String type, String subjectId, Object payload) {
        return publishIfAbsent(null, type, subjectId, payload);
    }

    public Event publishIfAbsent(String explicitEventId, String type, String subjectId, Object payload) {
        String eventId = explicitEventId != null ? explicitEventId
                : UUID.nameUUIDFromBytes((type + "|" + subjectId).getBytes()).toString();
        String json;
        try {
            json = objectMapper.writeValueAsString(Map.of(
                    "eventId", eventId, "type", type, "subjectId", subjectId, "payload", payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("事件序列化失败", e);
        }
        Event event = events.computeIfAbsent(eventId, id -> {
            Event created = new Event(id, type, subjectId, json, Instant.now());
            log.info("事件入队 eventId={} type={} subjectId={}", id, type, subjectId);
            return created;
        });
        if (event.getStatus() == EventStatus.QUEUED) {
            dispatcher.enqueue(event);
        }
        return event;
    }

    public Optional<Event> find(String eventId) {
        return Optional.ofNullable(events.get(eventId));
    }

    public Collection<Event> findByStatus(EventStatus status) {
        List<Event> list = events.values().stream().filter(e -> e.getStatus() == status).toList();
        return list;
    }

    public Collection<Event> all() {
        return List.copyOf(events.values());
    }

    /** 重新入队死信（排障后手工重试）。 */
    public void requeueDead(String eventId) {
        Event event = events.get(eventId);
        if (event == null) {
            throw new IllegalArgumentException("事件不存在: " + eventId);
        }
        if (event.getStatus() != EventStatus.DEAD) {
            throw new IllegalArgumentException("仅死信事件可重新入队，当前状态: " + event.getStatus());
        }
        event.setStatus(EventStatus.QUEUED);
        event.setAttempts(0);
        event.setLastError(null);
        dispatcher.enqueue(event);
        log.info("死信事件重新入队 eventId={}", eventId);
    }
}

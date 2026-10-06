package com.github.lechandonga.openbankingconsentgatewayjava.event;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 事件发布入口：相同 eventId 不会重复入队；未提供 eventId 时自动生成。
 */
@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventStore store;
    private final EventDispatcher dispatcher;

    public EventService(EventStore store, EventDispatcher dispatcher) {
        this.store = store;
        this.dispatcher = dispatcher;
    }

    public BusinessEvent publish(String eventId, String type, String customerId, Map<String, Object> payload) {
        String id = (eventId == null || eventId.isBlank()) ? "evt-" + UUID.randomUUID() : eventId;
        BusinessEvent event = new BusinessEvent(id, type, customerId, payload == null ? Map.of() : payload);
        EventStore.SaveResult result = store.saveIfAbsent(event);
        if (result.created()) {
            log.info("event {} enqueued (type={}, customer={})", id, type, customerId);
            dispatcher.dispatch(result.event());
        } else {
            log.info("event {} already enqueued, duplicate publish ignored", id);
        }
        return result.event();
    }

    public BusinessEvent get(String eventId) {
        return store.find(eventId).orElseThrow(() -> new IllegalArgumentException("event not found: " + eventId));
    }

    public List<BusinessEvent> deadLetters() {
        return store.findByStatus(EventStatus.DEAD);
    }
}

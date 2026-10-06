package com.github.lechandonga.openbankingconsentgatewayjava.event;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Repository;

@Repository
public class EventStore {

    private final ConcurrentHashMap<String, BusinessEvent> events = new ConcurrentHashMap<>();

    /**
     * 幂等入库：相同 eventId 只入队一次。
     * @return 事件，以及是否为本次新建
     */
    public synchronized SaveResult saveIfAbsent(BusinessEvent event) {
        BusinessEvent previous = events.putIfAbsent(event.getEventId(), event);
        return previous == null ? new SaveResult(event, true) : new SaveResult(previous, false);
    }

    public Optional<BusinessEvent> find(String eventId) {
        return Optional.ofNullable(events.get(eventId));
    }

    public List<BusinessEvent> findByStatus(EventStatus status) {
        return events.values().stream().filter(e -> e.getStatus() == status).toList();
    }

    public List<BusinessEvent> all() {
        return List.copyOf(events.values());
    }

    public record SaveResult(BusinessEvent event, boolean created) {
    }
}

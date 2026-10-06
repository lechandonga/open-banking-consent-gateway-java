package com.github.lechandonga.openbankingconsentgatewayjava.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.github.lechandonga.openbankingconsentgatewayjava.common.ApiException;
import com.github.lechandonga.openbankingconsentgatewayjava.event.BusinessEvent;
import com.github.lechandonga.openbankingconsentgatewayjava.event.EventService;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> publish(@RequestBody PublishEventRequest request) {
        if (request.type() == null || request.type().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "event type is required");
        }
        BusinessEvent event = eventService.publish(request.eventId(), request.type(),
                request.customerId(), request.payload());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(toView(event));
    }

    @GetMapping("/{eventId}")
    public Map<String, Object> get(@PathVariable String eventId) {
        try {
            return toView(eventService.get(eventId));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND", e.getMessage());
        }
    }

    @GetMapping("/dead-letters")
    public List<Map<String, Object>> deadLetters() {
        return eventService.deadLetters().stream().map(EventController::toView).toList();
    }

    static Map<String, Object> toView(BusinessEvent event) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("eventId", event.getEventId());
        view.put("type", event.getType());
        view.put("customerId", event.getCustomerId());
        view.put("status", event.getStatus());
        view.put("attempts", event.getAttempts());
        view.put("createdAt", event.getCreatedAt());
        view.put("lastAttemptAt", event.getLastAttemptAt());
        view.put("nextAttemptAt", event.getNextAttemptAt());
        view.put("deliveredAt", event.getDeliveredAt());
        view.put("lastError", event.getLastError());
        return view;
    }

    public record PublishEventRequest(String eventId, String type, String customerId,
                                      Map<String, Object> payload) {
    }
}

package com.github.lechandonga.openbankingconsentgatewayjava.event;

import com.github.lechandonga.openbankingconsentgatewayjava.event.dto.EventView;
import com.github.lechandonga.openbankingconsentgatewayjava.event.dto.PublishEventRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public EventView publish(@RequestBody PublishEventRequest request) {
        if (request.getType() == null || request.getSubjectId() == null) {
            throw new IllegalArgumentException("type 与 subjectId 不能为空");
        }
        Event event = eventService.publishIfAbsent(
                request.getEventId(), request.getType(), request.getSubjectId(),
                request.getPayload() == null ? Map.of() : request.getPayload());
        return new EventView(event);
    }

    @GetMapping("/{eventId}")
    public EventView get(@PathVariable String eventId) {
        return eventService.find(eventId).map(EventView::new)
                .orElseThrow(() -> new IllegalArgumentException("事件不存在: " + eventId));
    }

    @GetMapping
    public List<EventView> list(@RequestParam(value = "status", required = false) String status) {
        if (status == null) {
            return eventService.all().stream().map(EventView::new).toList();
        }
        EventStatus s = EventStatus.valueOf(status.toUpperCase());
        return eventService.findByStatus(s).stream().map(EventView::new).toList();
    }

    @PostMapping("/{eventId}/requeue")
    public EventView requeue(@PathVariable String eventId) {
        eventService.requeueDead(eventId);
        return eventService.find(eventId).map(EventView::new).orElseThrow();
    }
}

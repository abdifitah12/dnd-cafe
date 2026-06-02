package DND.demo.service;

import DND.demo.entity.Event;
import DND.demo.repository.EventRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class EventService {

    private final EventRepository repository;

    public EventService(EventRepository repository) {
        this.repository = repository;
    }

    public List<Event> getAllEvents() {
        return repository.findAll();
    }

    public Event getEventById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Event not found with id: " + id));
    }

    public Event createEvent(Event event) {
        return repository.save(event);
    }

    public Event updateEvent(Long id, Event updated) {

        Event event = getEventById(id);

        event.setTitle(updated.getTitle());
        event.setDescription(updated.getDescription());
        event.setLocation(updated.getLocation());
        event.setDate(updated.getDate());

        event.setStartTime(updated.getStartTime());
        event.setEndTime(updated.getEndTime());

        event.setImageUrl(updated.getImageUrl());
        event.setMediaUrl(updated.getMediaUrl());
        event.setMediaType(updated.getMediaType());

        return repository.save(event);
    }
    public void deleteEvent(Long id) {
        if (!repository.existsById(id)) {
            throw new RuntimeException("Event not found with id: " + id);
        }
        repository.deleteById(id);
    }
}
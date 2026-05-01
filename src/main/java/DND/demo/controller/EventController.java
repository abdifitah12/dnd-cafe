package DND.demo.controller;

import DND.demo.entity.Event;
import DND.demo.service.EventService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/events")
@CrossOrigin(origins = "http://localhost:5173")
public class EventController {

    private final EventService service;

    public EventController(EventService service) {
        this.service = service;
    }

    @GetMapping
    public List<Event> getAll() {
        return service.getAllEvents();
    }

    @GetMapping("/{id}")
    public Event getById(@PathVariable Long id) {
        return service.getEventById(id);
    }

    // ✅ Create event WITH image upload
    @PostMapping(consumes = "multipart/form-data")
    public Event createWithImage(
            @RequestParam String title,
            @RequestParam String description,
            @RequestParam String location,
            @RequestParam String date,
            @RequestParam String time,
            @RequestParam(required = false) MultipartFile image
    ) throws IOException {

        Event event = new Event();
        event.setTitle(title);
        event.setDescription(description);
        event.setLocation(location);
        event.setDate(LocalDate.parse(date));
        event.setTime(LocalTime.parse(time));

        if (image != null && !image.isEmpty()) {
            String fileName = UUID.randomUUID() + "-" + image.getOriginalFilename();

            Path uploadPath = Paths.get("uploads/events");

            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            Path filePath = uploadPath.resolve(fileName);
            Files.copy(image.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

            event.setImageUrl("/events/images/" + fileName);
        }

        return service.createEvent(event);
    }

    @PutMapping("/{id}")
    public Event update(@PathVariable Long id, @RequestBody Event event) {
        return service.updateEvent(id, event);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.deleteEvent(id);
    }
}
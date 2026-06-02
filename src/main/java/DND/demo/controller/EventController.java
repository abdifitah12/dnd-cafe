package DND.demo.controller;

import DND.demo.entity.Event;
import DND.demo.service.CloudinaryService;
import DND.demo.service.EventService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@RestController
@RequestMapping("/api/events")
@CrossOrigin(
        origins = {
                "http://localhost:5173",
                "https://dndcafe.to",
                "https://www.dndcafe.to"
        },
        allowedHeaders = "*",
        methods = {
                RequestMethod.GET,
                RequestMethod.POST,
                RequestMethod.PUT,
                RequestMethod.DELETE,
                RequestMethod.OPTIONS
        }
)
public class EventController {

    private final EventService service;
    private final CloudinaryService cloudinaryService;

    public EventController(EventService service, CloudinaryService cloudinaryService) {
        this.service = service;
        this.cloudinaryService = cloudinaryService;
    }

    @GetMapping
    public List<Event> getAll() {
        return service.getAllEvents();
    }

    @GetMapping("/{id}")
    public Event getById(@PathVariable Long id) {
        return service.getEventById(id);
    }

    @PostMapping(consumes = "multipart/form-data")
    public Event createWithMedia(
            @RequestParam String title,
            @RequestParam String description,
            @RequestParam String location,
            @RequestParam String date,
            @RequestParam String startTime,
            @RequestParam String endTime,
            @RequestParam(required = false) MultipartFile image
    ) throws IOException {

        Event event = new Event();

        event.setTitle(title);
        event.setDescription(description);
        event.setLocation(location);
        event.setDate(LocalDate.parse(date));

        event.setStartTime(LocalTime.parse(startTime));
        event.setEndTime(LocalTime.parse(endTime));

        if (image != null && !image.isEmpty()) {

            String mediaUrl = cloudinaryService.uploadFile(image);

            event.setImageUrl(mediaUrl);

            if (image.getContentType() != null &&
                    image.getContentType().startsWith("video")) {
                event.setMediaType("video");
            } else {
                event.setMediaType("image");
            }
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
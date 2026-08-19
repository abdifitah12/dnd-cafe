package DND.demo.controller;

import DND.demo.entity.EventHistory;
import DND.demo.repository.EventHistoryRepository;
import DND.demo.service.CloudinaryService;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/event-history")
@CrossOrigin(
        origins = {
                "http://localhost:5173",
                "https://dndcafe.to",
                "https://www.dndcafe.to"
        }
)
public class EventHistoryController {

    private final EventHistoryRepository repository;
    private final CloudinaryService cloudinaryService;

    public EventHistoryController(
            EventHistoryRepository repository,
            CloudinaryService cloudinaryService
    ) {
        this.repository = repository;
        this.cloudinaryService = cloudinaryService;
    }

    @GetMapping
    public List<EventHistory> getAll() {
        return repository.findAllNewestFirst();
    }

    @PostMapping(consumes = "multipart/form-data")
    public EventHistory uploadHistory(
            @RequestParam String title,
            @RequestParam(required = false) String description,
            @RequestParam MultipartFile file
    ) throws IOException {

        String mediaUrl = cloudinaryService.uploadFile(file);

        EventHistory history = new EventHistory();
        history.setTitle(title);
        history.setDescription(description);
        history.setMediaUrl(mediaUrl);

        if (file.getContentType() != null && file.getContentType().startsWith("video")) {
            history.setMediaType("video");
        } else {
            history.setMediaType("image");
        }

        return repository.save(history);
    }

    @PostMapping("/youtube")
    public EventHistory saveYoutubeVideo(
            @RequestParam String title,
            @RequestParam String description,
            @RequestParam String mediaUrl
    ) {

        EventHistory history = new EventHistory();

        history.setTitle(title);
        history.setDescription(description);
        history.setMediaUrl(mediaUrl);
        history.setMediaType("youtube");

        return repository.save(history);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        repository.deleteById(id);
    }
}
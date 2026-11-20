package DND.demo.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CrossOrigin;

@RestController
@CrossOrigin(origins = {"http://localhost:5173", "https://dndcafe.to", "https://www.dndcafe.to",
        "https://d-d-frontend-sigma.vercel.app"})
public class LiveRoomController {
    private final LiveRoomWebSocketHandler room;

    public LiveRoomController(LiveRoomWebSocketHandler room) {
        this.room = room;
    }

    @PostMapping("/api/live/watch")
    public ResponseEntity<LiveRoomWebSocketHandler.WatchSession> watch() {
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(room.watch());
        } catch (IllegalStateException failure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, failure.getMessage());
        }
    }

    @GetMapping("/api/live/participants")
    public ResponseEntity<LiveRoomWebSocketHandler.RoomStatus> participants() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(room.roomStatus());
    }
}

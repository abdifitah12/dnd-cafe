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
    private final LiveHostTickets hostTickets;

    public LiveRoomController(LiveRoomWebSocketHandler room, LiveHostTickets hostTickets) {
        this.room = room;
        this.hostTickets = hostTickets;
    }

    @PostMapping("/api/live/host-ticket")
    public ResponseEntity<java.util.Map<String, String>> hostTicket(org.springframework.security.core.Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getAuthorities().stream().noneMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin sign-in is required to start a live room.");
        }
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(java.util.Map.of("ticket", hostTickets.issue()));
        } catch (IllegalStateException failure) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, failure.getMessage());
        }
    }

    @PostMapping("/api/live/admin/monitor-ticket")
    public ResponseEntity<java.util.Map<String, String>> monitorTicket(org.springframework.security.core.Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin sign-in required.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(java.util.Map.of("ticket", hostTickets.issueMonitor()));
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

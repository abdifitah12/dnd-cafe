package DND.demo.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestController
@RequestMapping("/api/live")
@CrossOrigin(origins = {"http://localhost:5173", "https://dndcafe.to", "https://www.dndcafe.to", "https://d-d-frontend-sigma.vercel.app"})
public class LiveBroadcastController {
    private final LiveBroadcastRequests requests;
    private final LiveRoomWebSocketHandler room;
    public LiveBroadcastController(LiveBroadcastRequests requests, LiveRoomWebSocketHandler room) { this.requests = requests; this.room = room; }
    public record NewRequest(String name, String topic) {}
    public record Decision(boolean approve) {}
    public record Control(String action, String target, String hostPeerId) {}
    private void admin(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin sign-in required.");
        }
    }
    private <T> ResponseEntity<T> fresh(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
    @PostMapping("/requests")
    public ResponseEntity<LiveBroadcastRequests.CreatedRequest> submit(@RequestBody NewRequest body) { return fresh(requests.submit(body.name(), body.topic())); }
    @GetMapping("/requests/{id}")
    public ResponseEntity<LiveBroadcastRequests.RequestView> status(@PathVariable String id, @RequestHeader("X-Live-Request-Token") String token) { return fresh(requests.status(id, token)); }
    @PostMapping("/requests/{id}/cancel")
    public ResponseEntity<LiveBroadcastRequests.RequestView> cancel(@PathVariable String id, @RequestHeader("X-Live-Request-Token") String token) { return fresh(requests.cancel(id, token)); }
    @PostMapping("/requests/{id}/start")
    public ResponseEntity<Map<String, String>> start(@PathVariable String id, @RequestHeader("X-Live-Request-Token") String token) {
        if (room.roomStatus().hostPeerId() != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "A live room is already running. Wait for it to end.");
        return fresh(Map.of("ticket", requests.redeem(id, token)));
    }
    @GetMapping("/admin/requests")
    public ResponseEntity<?> pending(Authentication authentication) { admin(authentication); return fresh(Map.of("requests", requests.list(), "room", room.roomStatus())); }
    @PostMapping("/admin/requests/{id}/decision")
    public ResponseEntity<?> decide(@PathVariable String id, @RequestBody Decision decision, Authentication authentication) {
        admin(authentication);
        if (decision.approve() && room.roomStatus().hostPeerId() != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Stop the active live room before approving another broadcast.");
        return fresh(requests.decide(id, decision.approve()));
    }
    @PostMapping("/admin/control")
    public ResponseEntity<?> control(@RequestBody Control control, Authentication authentication) {
        admin(authentication);
        room.adminControl(control.action(), control.target(), control.hostPeerId());
        return fresh(Map.of("ok", true));
    }
}

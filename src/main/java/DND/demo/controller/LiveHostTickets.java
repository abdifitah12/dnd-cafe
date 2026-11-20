package DND.demo.controller;

import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Single-use tickets avoid placing a reusable admin JWT in a WebSocket URL. */
@Service
public class LiveHostTickets {
    private final Clock clock;
    private final Map<String, Long> tickets = new HashMap<>();

    public LiveHostTickets() { this(Clock.systemUTC()); }
    LiveHostTickets(Clock clock) { this.clock = clock; }

    public synchronized String issue() {
        tickets.values().removeIf(expires -> expires <= clock.millis());
        if (tickets.size() >= 512) throw new IllegalStateException("Too many pending starts. Please wait and try again.");
        String ticket = UUID.randomUUID().toString();
        tickets.put(ticket, clock.millis() + 30_000);
        return ticket;
    }

    public synchronized boolean consume(String ticket) {
        if (ticket == null) return false;
        Long expires = tickets.remove(ticket);
        return expires != null && expires > clock.millis();
    }
}

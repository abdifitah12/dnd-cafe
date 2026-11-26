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
    private record Ticket(long expiresAt, String purpose) {}
    private final Map<String, Ticket> tickets = new HashMap<>();

    public LiveHostTickets() { this(Clock.systemUTC()); }
    LiveHostTickets(Clock clock) { this.clock = clock; }

    public synchronized String issue() { return issueFor("host"); }
    public synchronized String issueMonitor() { return issueFor("monitor"); }
    private String issueFor(String purpose) {
        tickets.values().removeIf(ticket -> ticket.expiresAt() <= clock.millis());
        if (tickets.size() >= 512) throw new IllegalStateException("Too many pending starts. Please wait and try again.");
        String ticket = UUID.randomUUID().toString();
        tickets.put(ticket, new Ticket(clock.millis() + 30_000, purpose));
        return ticket;
    }

    public synchronized boolean consume(String ticket) { return consumeFor(ticket, "host"); }
    public synchronized boolean consumeMonitor(String ticket) { return consumeFor(ticket, "monitor"); }
    private boolean consumeFor(String token, String purpose) {
        if (token == null) return false;
        Ticket ticket = tickets.get(token);
        if (ticket == null || !ticket.purpose().equals(purpose)) return false;
        tickets.remove(token);
        return ticket.expiresAt() > clock.millis();
    }
}

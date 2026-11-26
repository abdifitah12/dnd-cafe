package DND.demo.controller;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Comments belong to one live session, with bounded history and sender throttling. */
public class LiveCommentLog {
    public record Entry(String id, String clientId, String authorId, String name, String role, String text, String createdAt) {}
    private final Clock clock;
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private final Map<String, Long> lastSent = new HashMap<>();
    public LiveCommentLog() { this(Clock.systemUTC()); }
    LiveCommentLog(Clock clock) { this.clock = clock; }

    public synchronized Entry append(String authorId, String name, String role, String text, String clientId) {
        text = text == null ? "" : text.strip();
        if (text.isBlank() || text.length() > 500) throw new IllegalArgumentException("Write a comment of 1 to 500 characters.");
        if (clientId == null || clientId.length() > 80) throw new IllegalArgumentException("Invalid comment. Please try again.");
        Long previous = lastSent.get(authorId);
        if (previous != null && clock.millis() - previous < 2000) throw new IllegalArgumentException("Please wait two seconds before sending another comment.");
        lastSent.put(authorId, clock.millis());
        var entry = new Entry(UUID.randomUUID().toString(), clientId, authorId, name, role, text, clock.instant().toString());
        entries.addLast(entry);
        while (entries.size() > 100) entries.removeFirst();
        return entry;
    }
    public synchronized List<Entry> history() { return List.copyOf(entries); }
    public synchronized void forget(String authorId) { lastSent.remove(authorId); }
    public synchronized void clear() { entries.clear(); lastSent.clear(); }
}

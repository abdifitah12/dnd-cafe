package DND.demo.controller;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LiveCommentLogTest {
    private final AtomicLong now = new AtomicLong(10_000);
    private LiveCommentLog log() {
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenAnswer(call -> now.get());
        when(clock.instant()).thenAnswer(call -> Instant.ofEpochMilli(now.get()));
        return new LiveCommentLog(clock);
    }
    @Test
    void storesTrimmedPlainTextAndRejectsBlankOrOversizedComments() {
        var log = log();
        assertThrows(IllegalArgumentException.class, () -> log.append("id", "Viewer", "viewer", "  ", "c"));
        assertThrows(IllegalArgumentException.class, () -> log.append("id", "Viewer", "viewer", "x".repeat(501), "c"));
        var comment = log.append("id", "Viewer", "viewer", " Hello <b>world</b> ", "c");
        assertEquals("Hello <b>world</b>", comment.text());
        assertEquals("viewer", comment.role());
        assertEquals(1, log.history().size());
    }
    @Test
    void throttlesEachSenderWithoutBlockingOtherViewers() {
        var log = log(); log.append("one", "One", "viewer", "Hello", "1");
        assertThrows(IllegalArgumentException.class, () -> log.append("one", "One", "viewer", "Again", "2"));
        assertDoesNotThrow(() -> log.append("two", "Two", "viewer", "Hello", "3"));
        now.addAndGet(2000);
        assertDoesNotThrow(() -> log.append("one", "One", "viewer", "Later", "4"));
    }
    @Test
    void historyIsBoundedAndClearedBetweenLiveRooms() {
        var log = log();
        for (int i = 0; i < 105; i++) { log.append("id", "Viewer", "viewer", "Message " + i, "id" + i); now.addAndGet(2000); }
        assertEquals(100, log.history().size());
        assertEquals("Message 5", log.history().getFirst().text());
        log.clear(); assertTrue(log.history().isEmpty());
        assertDoesNotThrow(() -> log.append("id", "Viewer", "viewer", "New room", "new"));
    }
    @Test
    void leavingReleasesTheRateLimitWithoutErasingConversation() {
        var log = log(); log.append("id", "Viewer", "viewer", "Hello", "1");
        log.forget("id");
        assertEquals(1, log.history().size());
        assertDoesNotThrow(() -> log.append("id", "Viewer", "viewer", "Back", "2"));
    }
}

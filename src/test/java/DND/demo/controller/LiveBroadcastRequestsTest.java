package DND.demo.controller;

import DND.demo.entity.LiveBroadcastRequest;
import DND.demo.repository.LiveBroadcastRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LiveBroadcastRequestsTest {
    private final Map<String, LiveBroadcastRequest> rows = new HashMap<>();
    private final LiveBroadcastRequestRepository repository = mock(LiveBroadcastRequestRepository.class);
    private final LiveHostTickets tickets = new LiveHostTickets();
    private final LiveBroadcastRequests service = new LiveBroadcastRequests(repository, tickets);
    @BeforeEach
    void setup() {
        when(repository.save(any())).thenAnswer(call -> { LiveBroadcastRequest row = call.getArgument(0); rows.put(row.getId(), row); return row; });
        when(repository.findWithLockById(anyString())).thenAnswer(call -> Optional.ofNullable(rows.get(call.getArgument(0))));
        when(repository.countByStatusAndExpiresAtAfter(anyString(), any())).thenAnswer(call -> rows.values().stream()
                .filter(row -> row.getStatus().equals(call.getArgument(0)) && row.getExpiresAt().isAfter(call.getArgument(1))).count());
        when(repository.findByStatusInAndExpiresAtAfterOrderByCreatedAtAsc(anyList(), any())).thenAnswer(call -> {
            List<String> statuses = call.getArgument(0); Instant now = call.getArgument(1);
            return rows.values().stream().filter(row -> statuses.contains(row.getStatus()) && row.getExpiresAt().isAfter(now)).toList();
        });
    }
    @Test
    void requestIsPrivateAndStoredTokenIsHashed() {
        var created = service.submit("Visitor", "Coffee");
        assertEquals("PENDING", service.status(created.request().id(), created.token()).status());
        assertNotEquals(created.token(), rows.get(created.request().id()).getTokenHash());
        assertThrows(ResponseStatusException.class, () -> service.status(created.request().id(), "someone-else"));
        assertEquals(1, service.list().size());
    }
    @Test
    void approvalIsRequiredAndCanOnlyStartOnce() {
        var created = service.submit("Visitor", "Topic");
        assertThrows(ResponseStatusException.class, () -> service.redeem(created.request().id(), created.token()));
        service.decide(created.request().id(), true);
        String ticket = service.redeem(created.request().id(), created.token());
        assertTrue(tickets.consume(ticket)); assertFalse(tickets.consume(ticket));
        assertThrows(ResponseStatusException.class, () -> service.redeem(created.request().id(), created.token()));
        assertTrue(service.list().isEmpty());
    }
    @Test
    void declinedRevokedAndCancelledRequestsCannotStart() {
        var declined = service.submit("Declined", "");
        service.decide(declined.request().id(), false);
        assertThrows(ResponseStatusException.class, () -> service.redeem(declined.request().id(), declined.token()));
        var revoked = service.submit("Revoked", ""); service.decide(revoked.request().id(), true); service.decide(revoked.request().id(), false);
        assertThrows(ResponseStatusException.class, () -> service.redeem(revoked.request().id(), revoked.token()));
        var cancelled = service.submit("Cancelled", ""); service.cancel(cancelled.request().id(), cancelled.token());
        assertThrows(ResponseStatusException.class, () -> service.redeem(cancelled.request().id(), cancelled.token()));
        assertTrue(service.list().isEmpty());
    }
    @Test
    void onlyOneVisitorCanHaveAnActiveApproval() {
        var first = service.submit("First", ""); var second = service.submit("Second", "");
        service.decide(first.request().id(), true);
        assertThrows(ResponseStatusException.class, () -> service.decide(second.request().id(), true));
        service.decide(first.request().id(), false);
        assertEquals("APPROVED", service.decide(second.request().id(), true).status());
    }
    @Test
    void expiredApprovalsCannotStartAndInvalidNamesAreRejected() {
        assertThrows(ResponseStatusException.class, () -> service.submit(" ", ""));
        var created = service.submit("Visitor", ""); service.decide(created.request().id(), true);
        rows.get(created.request().id()).setExpiresAt(Instant.now().minusSeconds(1));
        assertEquals("EXPIRED", service.status(created.request().id(), created.token()).status());
        assertThrows(ResponseStatusException.class, () -> service.redeem(created.request().id(), created.token()));
    }
    @Test
    void adminRoutesRejectCustomersAndStaleRoomControls() {
        var room = mock(LiveRoomWebSocketHandler.class);
        when(room.roomStatus()).thenReturn(new LiveRoomWebSocketHandler.RoomStatus(10, List.of(), null, false));
        var controller = new LiveBroadcastController(service, room);
        var customer = new UsernamePasswordAuthenticationToken("Customer", null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        assertThrows(ResponseStatusException.class, () -> controller.pending(null));
        assertThrows(ResponseStatusException.class, () -> controller.pending(customer));
        assertThrows(ResponseStatusException.class, () -> controller.control(new LiveBroadcastController.Control("end-live", null, "host"), customer));
        verify(room, never()).adminControl(any(), any(), any());
    }
}

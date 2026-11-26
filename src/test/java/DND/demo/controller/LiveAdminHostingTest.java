package DND.demo.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LiveAdminHostingTest {
    private final LiveHostTickets tickets = new LiveHostTickets();
    private final LiveRoomWebSocketHandler room = new LiveRoomWebSocketHandler(
            JsonMapper.builder().build(), mock(LiveMediaService.class), tickets);

    private WebSocketSession connect(String mode, String ticket) throws Exception {
        var session = mock(WebSocketSession.class);
        when(session.getUri()).thenReturn(URI.create("http://localhost/ws/live?peerId=" + UUID.randomUUID()
                + "&name=User&mode=" + mode + (ticket == null ? "" : "&hostTicket=" + ticket)));
        when(session.getAttributes()).thenReturn(new HashMap<>());
        when(session.isOpen()).thenReturn(true);
        room.afterConnectionEstablished(session);
        return session;
    }

    @Test
    void publicVisitorsCannotStartTheRoomOrBecomeHostByRequesting() throws Exception {
        var anonymousStart = connect("start", null);
        var forgedTicket = connect("start", "not-an-admin-ticket");
        var request = connect("request", null);
        var missingMode = connect("", null);
        assertTrue(room.roomStatus().participants().isEmpty());
        for (var denied : List.of(anonymousStart, forgedTicket, request, missingMode)) {
            verify(denied).close(CloseStatus.NORMAL);
        }
    }

    @Test
    void adminTicketStartsOnceAndCannotReplaceAnExistingHost() throws Exception {
        String firstTicket = tickets.issue();
        var host = connect("start", firstTicket);
        assertEquals(1, room.roomStatus().participants().size());
        var second = connect("start", tickets.issue());
        verify(second).close(CloseStatus.NORMAL);
        assertEquals(host.getAttributes().get("peerId"), room.roomStatus().hostPeerId());
        room.afterConnectionClosed(host, CloseStatus.NORMAL);
        var replay = connect("start", firstTicket);
        verify(replay).close(CloseStatus.NORMAL);
        assertTrue(room.roomStatus().participants().isEmpty());
    }

    @Test
    void ticketsExpireAndCannotBeReused() {
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenReturn(1000L);
        var timed = new LiveHostTickets(clock);
        String singleUse = timed.issue();
        assertTrue(timed.consume(singleUse));
        assertFalse(timed.consume(singleUse));
        String expired = timed.issue();
        when(clock.millis()).thenReturn(31_000L);
        assertFalse(timed.consume(expired));
        assertFalse(timed.consume(null));
    }

    @Test
    void monitorTicketsCannotStartBroadcastsAndRequireAdminRole() {
        String monitor = tickets.issueMonitor();
        assertFalse(tickets.consume(monitor));
        assertTrue(tickets.consumeMonitor(monitor));
        assertFalse(tickets.consumeMonitor(monitor));
        String host = tickets.issue();
        assertFalse(tickets.consumeMonitor(host));
        assertTrue(tickets.consume(host));
        var controller = new LiveRoomController(room, tickets);
        var customer = new UsernamePasswordAuthenticationToken("Customer", null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        assertThrows(ResponseStatusException.class, () -> controller.monitorTicket(customer));
        assertThrows(ResponseStatusException.class, () -> controller.monitorTicket(null));
    }

    @Test
    void onlyAuthenticatedAdminRolesCanIssueHostTickets() {
        var controller = new LiveRoomController(room, tickets);
        assertThrows(ResponseStatusException.class, () -> controller.hostTicket(null));
        var customer = new UsernamePasswordAuthenticationToken("Customer", null,
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        assertThrows(ResponseStatusException.class, () -> controller.hostTicket(customer));
        var admin = new UsernamePasswordAuthenticationToken("Admin", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        var response = controller.hostTicket(admin);
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertTrue(tickets.consume(response.getBody().get("ticket")));
    }
}

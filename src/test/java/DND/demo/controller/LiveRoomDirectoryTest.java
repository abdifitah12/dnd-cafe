package DND.demo.controller;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.util.HashMap;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LiveRoomDirectoryTest {
    private final LiveMediaService media = mock(LiveMediaService.class);
    private final LiveHostTickets hostTickets = new LiveHostTickets();
    private final LiveRoomWebSocketHandler room = new LiveRoomWebSocketHandler(JsonMapper.builder().build(), media, hostTickets);

    private WebSocketSession connect(String name, String mode) throws Exception {
        var session = mock(WebSocketSession.class);
        when(session.getUri()).thenReturn(URI.create("http://localhost/ws/live?peerId=" + UUID.randomUUID()
                + "&name=" + name + "&mode=" + mode + (mode.equals("start") ? "&hostTicket=" + hostTickets.issue() : "")));
        when(session.getAttributes()).thenReturn(new HashMap<>());
        when(session.isOpen()).thenReturn(true);
        room.afterConnectionEstablished(session);
        return session;
    }

    private String id(WebSocketSession session) {
        return (String) session.getAttributes().get("peerId");
    }

    private void decide(WebSocketSession sender, WebSocketSession guest, String decision) throws Exception {
        room.handleTextMessage(sender, new TextMessage("{\"type\":\"" + decision
                + "\",\"target\":\"" + id(guest) + "\"}"));
    }

    @Test
    void browsingDoesNotJoinTheRoomAndDisablesCaching() {
        var response = new LiveRoomController(room, hostTickets).participants();
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals(10, response.getBody().limit());
        assertTrue(response.getBody().participants().isEmpty());
        assertNull(response.getBody().hostPeerId());
    }

    @Test
    void waitingGuestsAreNotListedOrConnectedUntilTheHostApproves() throws Exception {
        var host = connect("Zara", "start");
        var guest = connect("Abdi", "request");
        assertEquals(id(host), room.roomStatus().hostPeerId());
        assertEquals(1, room.roomStatus().participants().size());
        verify(host).sendMessage(argThat(message -> ((TextMessage) message).getPayload().contains("join-request")));
        verify(guest, never()).sendMessage(argThat(message -> ((TextMessage) message).getPayload().contains("joined")));
        decide(host, guest, "approve");
        assertEquals(java.util.List.of("Abdi", "Zara"), room.roomStatus().participants().stream()
                .map(LiveRoomWebSocketHandler.LiveParticipant::name).toList());
        verify(guest).sendMessage(argThat(message -> ((TextMessage) message).getPayload().contains("\"type\":\"joined\"")));
        room.afterConnectionClosed(guest, CloseStatus.NORMAL);
        assertEquals(1, room.roomStatus().participants().size());
    }

    @Test
    void pendingGuestsCannotRelaySignalsOrApproveThemselves() throws Exception {
        var host = connect("Host", "start");
        var guest = connect("Guest", "request");
        clearInvocations(host);
        decide(guest, guest, "approve");
        room.handleTextMessage(guest, new TextMessage("{\"type\":\"offer\",\"target\":\""
                + id(host) + "\",\"payload\":{\"sdp\":\"test\"}}"));
        verify(host, never()).sendMessage(any());
        assertEquals(1, room.roomStatus().participants().size());
    }

    @Test
    void approvedGuestsCannotAcceptOtherGuests() throws Exception {
        var host = connect("Host", "start");
        var first = connect("First", "request");
        decide(host, first, "approve");
        var second = connect("Second", "request");
        decide(first, second, "approve");
        assertEquals(2, room.roomStatus().participants().size());
    }

    @Test
    void declinedAndCancelledRequestsCannotBeApprovedLater() throws Exception {
        var host = connect("Host", "start");
        var declined = connect("Declined", "request");
        decide(host, declined, "decline");
        verify(declined).close(CloseStatus.NORMAL);
        decide(host, declined, "approve");
        var cancelled = connect("Cancelled", "request");
        room.afterConnectionClosed(cancelled, CloseStatus.NORMAL);
        decide(host, cancelled, "approve");
        assertEquals(1, room.roomStatus().participants().size());
        verify(host, atLeastOnce()).sendMessage(argThat(message -> ((TextMessage) message).getPayload().contains("request-resolved")));
    }

    @Test
    void endingTheHostSessionClosesApprovedAndWaitingGuests() throws Exception {
        var host = connect("Host", "start");
        var approved = connect("Approved", "request");
        decide(host, approved, "approve");
        var pending = connect("Waiting", "request");
        room.afterConnectionClosed(host, CloseStatus.NORMAL);
        assertTrue(room.roomStatus().participants().isEmpty());
        assertNull(room.roomStatus().hostPeerId());
        for (var guest : java.util.List.of(approved, pending)) {
            verify(guest).sendMessage(argThat(message -> ((TextMessage) message).getPayload().contains("room-ended")));
            verify(guest).close(CloseStatus.NORMAL);
        }
        var lateGuest = connect("Late", "request");
        verify(lateGuest).close(CloseStatus.NORMAL);
        assertTrue(room.roomStatus().participants().isEmpty());
    }

    @Test
    void hostCanRecoverPendingRequestsAndGuestsCannotReadThem() throws Exception {
        var host = connect("Host", "start");
        var guest = connect("PendingName", "request");
        clearInvocations(host, guest);
        room.handleTextMessage(host, new TextMessage("{\"type\":\"sync-requests\"}"));
        verify(host).sendMessage(argThat(message -> {
            String payload = ((TextMessage) message).getPayload();
            return payload.contains("request-list") && payload.contains("PendingName");
        }));
        room.handleTextMessage(guest, new TextMessage("{\"type\":\"sync-requests\"}"));
        verify(guest, never()).sendMessage(any());
        decide(host, guest, "decline");
        clearInvocations(host);
        room.handleTextMessage(host, new TextMessage("{\"type\":\"sync-requests\"}"));
        verify(host).sendMessage(argThat(message -> ((TextMessage) message).getPayload().contains("\"requests\":[]")));
    }

    @Test
    void hostCanRestrictGuestMediaAndReleaseEachRestrictionIndependently() throws Exception {
        var host = connect("Host", "start");
        var guest = connect("Guest", "request");
        decide(host, guest, "approve");
        decide(host, guest, "mute-guest");
        decide(host, guest, "camera-off");
        var state = room.roomStatus().participants().stream().filter(person -> person.peerId().equals(id(guest))).findFirst().orElseThrow();
        assertTrue(state.audioBlocked());
        assertTrue(state.cameraBlocked());
        verify(guest, atLeastOnce()).sendMessage(argThat(message -> ((TextMessage) message).getPayload().contains("guest-controls")));
        decide(host, guest, "allow-mic");
        state = room.roomStatus().participants().stream().filter(person -> person.peerId().equals(id(guest))).findFirst().orElseThrow();
        assertFalse(state.audioBlocked());
        assertTrue(state.cameraBlocked());
        decide(host, guest, "allow-camera");
        state = room.roomStatus().participants().stream().filter(person -> person.peerId().equals(id(guest))).findFirst().orElseThrow();
        assertFalse(state.audioBlocked());
        assertFalse(state.cameraBlocked());
    }

    @Test
    void guestCannotModerateOthersOrEndLive() throws Exception {
        var host = connect("Host", "start");
        var guest = connect("Guest", "request");
        decide(host, guest, "approve");
        clearInvocations(host, guest);
        for (String action : java.util.List.of("mute-guest", "camera-off", "remove-guest", "end-live")) {
            decide(guest, host, action);
        }
        decide(guest, guest, "allow-mic");
        assertEquals(2, room.roomStatus().participants().size());
        assertTrue(room.roomStatus().participants().stream().noneMatch(person -> person.audioBlocked() || person.cameraBlocked()));
        verify(host, never()).close(any(CloseStatus.class));
        verify(host, never()).sendMessage(any());
    }

    @Test
    void removedGuestsAreDisconnectedAndCannotSendSignals() throws Exception {
        var host = connect("Host", "start");
        var guest = connect("Guest", "request");
        decide(host, guest, "approve");
        decide(host, guest, "remove-guest");
        assertEquals(1, room.roomStatus().participants().size());
        verify(guest).close(CloseStatus.NORMAL);
        verify(guest).sendMessage(argThat(message -> ((TextMessage) message).getPayload().contains("\"type\":\"removed\"")));
        clearInvocations(host);
        room.handleTextMessage(guest, new TextMessage("{\"type\":\"offer\",\"target\":\"" + id(host) + "\",\"payload\":{}}"));
        verify(host, never()).sendMessage(any());
    }

    @Test
    void newlyApprovedGuestsReceiveExistingMediaRestrictions() throws Exception {
        var host = connect("Host", "start");
        var first = connect("First", "request");
        decide(host, first, "approve");
        decide(host, first, "mute-guest");
        decide(host, first, "camera-off");
        var second = connect("Second", "request");
        decide(host, second, "approve");
        verify(second).sendMessage(argThat(message -> {
            String payload = ((TextMessage) message).getPayload();
            return payload.contains("\"type\":\"joined\"") && payload.contains("\"audioBlocked\":true")
                    && payload.contains("\"cameraBlocked\":true");
        }));
    }

    @Test
    void hostCanExplicitlyEndLiveForEveryone() throws Exception {
        var host = connect("Host", "start");
        var guest = connect("Guest", "request");
        decide(host, guest, "approve");
        room.handleTextMessage(host, new TextMessage("{\"type\":\"end-live\"}"));
        assertTrue(room.roomStatus().participants().isEmpty());
        assertNull(room.roomStatus().hostPeerId());
        verify(host).close(CloseStatus.NORMAL);
        verify(guest).close(CloseStatus.NORMAL);
    }

    @Test
    void viewersDoNotConsumeTheTenSpeakerPlacesOrGetPublishingGrants() throws Exception {
        when(media.configured()).thenReturn(true);
        when(media.session(anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(new LiveMediaService.MediaSession("wss://example.invalid", "test-token"));
        var host = connect("Host", "start");
        for (int count = 0; count < 9; count++) {
            var guest = connect("Guest" + count, "request");
            decide(host, guest, "approve");
        }
        assertEquals(10, room.roomStatus().participants().size());
        assertTrue(room.roomStatus().watchingEnabled());
        for (int count = 0; count < 30; count++) {
            assertEquals(id(host), room.watch().hostPeerId());
        }
        assertEquals(10, room.roomStatus().participants().size());
        verify(media, times(30)).session(eq("dnd-" + id(host)), startsWith("viewer-"), eq("Viewer"), eq(false));
    }

    @Test
    void watchTokensCannotBeIssuedAfterTheHostLeaves() throws Exception {
        var host = connect("Host", "start");
        room.afterConnectionClosed(host, CloseStatus.NORMAL);
        assertThrows(IllegalStateException.class, room::watch);
        verify(media, never()).session(anyString(), startsWith("viewer-"), anyString(), eq(false));
    }

    @Test
    void adminCanModerateAndStopAVisitorHostedRoom() throws Exception {
        var host = connect("VisitorHost", "start");
        var guest = connect("Guest", "request"); decide(host, guest, "approve");
        String hostId = id(host);
        room.adminControl("mute-guest", hostId, hostId);
        assertTrue(room.roomStatus().participants().stream().filter(person -> person.peerId().equals(hostId)).findFirst().orElseThrow().audioBlocked());
        room.adminControl("camera-off", hostId, hostId);
        assertTrue(room.roomStatus().participants().stream().filter(person -> person.peerId().equals(hostId)).findFirst().orElseThrow().cameraBlocked());
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> room.adminControl("end-live", null, "previous-host"));
        assertEquals(2, room.roomStatus().participants().size());
        room.adminControl("end-live", null, hostId);
        assertTrue(room.roomStatus().participants().isEmpty());
        verify(host).close(CloseStatus.NORMAL); verify(guest).close(CloseStatus.NORMAL);
    }

    private WebSocketSession monitor(String ticket) throws Exception {
        var session = mock(WebSocketSession.class);
        when(session.getUri()).thenReturn(URI.create("http://localhost/ws/live?peerId=" + UUID.randomUUID() + "&name=Admin&mode=monitor&hostTicket=" + ticket));
        when(session.getAttributes()).thenReturn(new HashMap<>());
        when(session.isOpen()).thenReturn(true);
        room.afterConnectionEstablished(session);
        return session;
    }

    @Test
    void adminMonitoringDoesNotUseCameraSpacesAndCannotSendHostCommands() throws Exception {
        var host = connect("Host", "start");
        var monitor = monitor(hostTickets.issueMonitor());
        assertEquals(1, room.roomStatus().participants().size());
        verify(host).sendMessage(argThat(message -> ((TextMessage) message).getPayload().contains("monitor-joined")));
        clearInvocations(host);
        room.handleTextMessage(monitor, new TextMessage("{\"type\":\"end-live\"}"));
        room.handleTextMessage(monitor, new TextMessage("{\"type\":\"offer\",\"target\":\"" + id(host) + "\",\"payload\":{}}"));
        verify(host, never()).sendMessage(any());
        assertEquals(1, room.roomStatus().participants().size());
        room.afterConnectionClosed(monitor, CloseStatus.NORMAL);
        assertEquals(1, room.roomStatus().participants().size());
    }

    @Test
    void monitoringRequiresTheCorrectTicketAndEndsWithTheRoom() throws Exception {
        var host = connect("Host", "start");
        var denied = monitor(hostTickets.issue());
        verify(denied).close(CloseStatus.NORMAL);
        var monitor = monitor(hostTickets.issueMonitor());
        var guest = connect("Guest", "request"); decide(host, guest, "approve");
        verify(monitor).sendMessage(argThat(message -> ((TextMessage) message).getPayload().contains("peer-joined")));
        verify(guest).sendMessage(argThat(message -> ((TextMessage) message).getPayload().contains("monitor-joined")));
        room.adminControl("end-live", null, id(host));
        verify(monitor).close(CloseStatus.NORMAL);
        assertTrue(room.roomStatus().participants().isEmpty());
    }

    @Test
    void guestsJoinImmediatelyWithoutWaitingForHostApproval() throws Exception {
        var host = connect("Host", "start");
        clearInvocations(host);
        var guest = connect("Guest+Name", "join");
        assertEquals(2, room.roomStatus().participants().size());
        assertEquals("Guest Name", room.roomStatus().participants().stream().filter(person -> person.peerId().equals(id(guest))).findFirst().orElseThrow().name());
        verify(guest).sendMessage(argThat(message -> ((TextMessage) message).getPayload().contains("\"type\":\"joined\"")));
        verify(guest, never()).sendMessage(argThat(message -> ((TextMessage) message).getPayload().contains("waiting")));
        verify(host, never()).sendMessage(argThat(message -> ((TextMessage) message).getPayload().contains("join-request")));
    }

    @Test
    void directJoinsStillRequireAnActiveHostAndRespectTenCameraLimit() throws Exception {
        var noHost = connect("NoHost", "join");
        verify(noHost).close(CloseStatus.NORMAL);
        assertTrue(room.roomStatus().participants().isEmpty());
        connect("Host", "start");
        for (int index = 0; index < 9; index++) connect("Guest" + index, "join");
        assertEquals(10, room.roomStatus().participants().size());
        var full = connect("TooMany", "join");
        verify(full).close(CloseStatus.NORMAL);
        assertEquals(10, room.roomStatus().participants().size());
    }

    @Test
    void publicViewersWatchWithoutApprovalOrUsingCameraPlaces() throws Exception {
        var host = connect("Host", "start");
        clearInvocations(host);
        var viewer = connect("Viewer", "watch");
        assertEquals(1, room.roomStatus().participants().size());
        assertEquals(1, room.roomStatus().viewerCount());
        assertEquals("peer", room.roomStatus().watchTransport());
        verify(viewer).sendMessage(argThat(message -> ((TextMessage) message).getPayload().contains("\"type\":\"watching\"")));
        verify(host, never()).sendMessage(argThat(message -> ((TextMessage) message).getPayload().contains("join-request")));
        room.afterConnectionClosed(viewer, CloseStatus.NORMAL);
        assertEquals(0, room.roomStatus().viewerCount());
        assertEquals(1, room.roomStatus().participants().size());
    }

    @Test
    void publicViewingIsReceiveOnlyAndCannotStartOrStopBroadcasts() throws Exception {
        var missingHost = connect("NoHost", "watch");
        verify(missingHost).close(CloseStatus.NORMAL);
        assertTrue(room.roomStatus().participants().isEmpty());
        var host = connect("Host", "start");
        var viewer = connect("Viewer", "watch");
        clearInvocations(host);
        room.handleTextMessage(viewer, new TextMessage("{\"type\":\"end-live\"}"));
        room.handleTextMessage(viewer, new TextMessage("{\"type\":\"offer\",\"target\":\"" + id(host) + "\",\"payload\":{}}"));
        verify(host, never()).sendMessage(any());
        assertEquals(1, room.roomStatus().participants().size());
    }

    @Test
    void viewerLimitIsEnforcedSeparatelyAndKeepsAdminMonitoringAvailable() throws Exception {
        connect("Host", "start");
        for (int count = 0; count < 9; count++) connect("Guest" + count, "join");
        for (int count = 0; count < 3; count++) connect("Viewer" + count, "watch");
        assertEquals(10, room.roomStatus().participants().size());
        assertEquals(3, room.roomStatus().viewerCount());
        assertFalse(room.roomStatus().watchingEnabled());
        var full = connect("Full", "watch"); verify(full).close(CloseStatus.NORMAL);
        var admin = monitor(hostTickets.issueMonitor());
        verify(admin, never()).close(any(CloseStatus.class));
        assertEquals(3, room.roomStatus().viewerCount());
    }

    @Test
    void endingTheRoomClosesPublicViewersAndClearsViewingSpaces() throws Exception {
        var host = connect("Host", "start"); var viewer = connect("Viewer", "watch");
        room.adminControl("end-live", null, id(host));
        verify(viewer).sendMessage(argThat(message -> ((TextMessage) message).getPayload().contains("room-ended")));
        verify(viewer).close(CloseStatus.NORMAL);
        assertEquals(0, room.roomStatus().viewerCount());
    }

    @Test
    void sfuHostingAdvertisesScalableTransportInsteadOfPeerViewerLimit() throws Exception {
        when(media.configured()).thenReturn(true);
        when(media.session(anyString(), anyString(), anyString(), anyBoolean())).thenReturn(new LiveMediaService.MediaSession("wss://example.invalid", "token"));
        connect("Host", "start");
        assertEquals("sfu", room.roomStatus().watchTransport());
        assertEquals(0, room.roomStatus().viewerLimit());
        assertTrue(room.roomStatus().watchingEnabled());
        var fallback = connect("Viewer", "watch"); verify(fallback).close(CloseStatus.NORMAL);
    }

    private void comment(WebSocketSession sender, String text) throws Exception {
        room.handleTextMessage(sender, new TextMessage("{\"type\":\"comment\",\"text\":\"" + text + "\",\"clientId\":\"client-test\",\"name\":\"Forged host\",\"role\":\"host\"}"));
    }

    @Test
    void viewersCanCommentToHostsAndGuestsWithoutCameraPermissions() throws Exception {
        var host = connect("Host", "start"); var guest = connect("Guest", "join"); var viewer = connect("Viewer", "watch");
        clearInvocations(host, guest, viewer);
        comment(viewer, "Hello everyone");
        for (var recipient : java.util.List.of(host, guest, viewer)) {
            verify(recipient).sendMessage(argThat(message -> {
                String payload = ((TextMessage) message).getPayload();
                return payload.contains("\"type\":\"comment\"") && payload.contains("Hello everyone")
                        && payload.contains("\"role\":\"viewer\"") && payload.contains("\"name\":\"Viewer\"") && !payload.contains("Forged host");
            }));
        }
        assertEquals(2, room.roomStatus().participants().size());
    }

    @Test
    void invalidAndPendingCommentsAreNotBroadcast() throws Exception {
        var host = connect("Host", "start"); var pending = connect("Pending", "request"); var viewer = connect("Viewer", "watch");
        clearInvocations(host);
        comment(pending, "Not admitted"); comment(viewer, "x".repeat(501));
        verify(host, never()).sendMessage(any());
        verify(viewer).sendMessage(argThat(message -> ((TextMessage) message).getPayload().contains("comment-error")));
    }

    @Test
    void lateViewersReceiveHistoryButNewRoomsDoNotReceiveOldComments() throws Exception {
        var host = connect("Host", "start"); comment(host, "Old room message");
        var viewer = connect("Viewer", "watch");
        verify(viewer).sendMessage(argThat(message -> ((TextMessage) message).getPayload().contains("comments-history") && ((TextMessage) message).getPayload().contains("Old room message")));
        room.afterConnectionClosed(host, CloseStatus.NORMAL);
        var next = connect("NextHost", "start");
        verify(next).sendMessage(argThat(message -> ((TextMessage) message).getPayload().contains("\"comments\":[]")));
    }

    @Test
    void scalableViewersGetACommentsOnlyConnectionWithoutMediaPrivileges() throws Exception {
        when(media.configured()).thenReturn(true);
        when(media.session(anyString(), anyString(), anyString(), anyBoolean())).thenReturn(new LiveMediaService.MediaSession("wss://example.invalid", "token"));
        var host = connect("Host", "start");
        var watch = room.watch();
        var viewer = mock(WebSocketSession.class);
        var expectedHost = id(host);
        when(viewer.getUri()).thenReturn(URI.create("http://localhost/ws/live?peerId=" + UUID.randomUUID() + "&name=Viewer&mode=comments&hostTicket=" + watch.commentsTicket() + "&expectedHost=" + expectedHost));
        when(viewer.getAttributes()).thenReturn(new HashMap<>()); when(viewer.isOpen()).thenReturn(true);
        room.afterConnectionEstablished(viewer);
        verify(viewer).sendMessage(argThat(message -> ((TextMessage) message).getPayload().contains("comments-ready")));
        clearInvocations(host);
        comment(viewer, "Watching with LiveKit");
        verify(host).sendMessage(argThat(message -> ((TextMessage) message).getPayload().contains("Watching with LiveKit")));
        clearInvocations(host);
        room.handleTextMessage(viewer, new TextMessage("{\"type\":\"offer\",\"target\":\"" + id(host) + "\",\"payload\":{}}"));
        verify(host, never()).sendMessage(any());
        assertEquals(1, room.roomStatus().participants().size());
        assertEquals(0, room.roomStatus().viewerCount());
        room.adminControl("end-live", null, id(host)); verify(viewer).close(CloseStatus.NORMAL);
    }

    @Test
    void roomLimitIsRecheckedAtApproval() throws Exception {
        var host = connect("Host", "start");
        var waiting = connect("Waiting", "request");
        for (int count = 0; count < 9; count++) {
            var guest = connect("Guest" + count, "request");
            decide(host, guest, "approve");
        }
        decide(host, waiting, "approve");
        assertEquals(10, room.roomStatus().participants().size());
        verify(host).sendMessage(argThat(message -> ((TextMessage) message).getPayload().contains("request-error")));
    }
}

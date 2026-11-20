package DND.demo.controller;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class LiveRoomWebSocketHandler extends TextWebSocketHandler {
    private static final int MAX_PARTICIPANTS = 10;
    private static final int MAX_PENDING = 20;
    private final JsonMapper objectMapper;
    private final LiveMediaService media;
    private final Map<String, WebSocketSession> participants = new LinkedHashMap<>();
    private final Map<String, WebSocketSession> pending = new LinkedHashMap<>();
    private final Object roomLock = new Object();
    private String hostPeerId;
    private record MediaLimits(boolean audioBlocked, boolean cameraBlocked) {}
    private final Map<String, MediaLimits> mediaLimits = new LinkedHashMap<>();

    public LiveRoomWebSocketHandler(JsonMapper objectMapper, LiveMediaService media) {
        this.objectMapper = objectMapper;
        this.media = media;
    }

    public record LiveParticipant(String peerId, String name, boolean audioBlocked, boolean cameraBlocked) {}
    public record RoomStatus(int limit, List<LiveParticipant> participants, String hostPeerId, boolean watchingEnabled) {}
    public record WatchSession(LiveMediaService.MediaSession media, String hostPeerId) {}

    public WatchSession watch() {
        synchronized (roomLock) {
            if (hostPeerId == null || participants.isEmpty()) throw new IllegalStateException("No live room is active.");
            return new WatchSession(media.session(mediaRoom(), "viewer-" + UUID.randomUUID(), "Viewer", false), hostPeerId);
        }
    }

    private String mediaRoom() { return "dnd-" + hostPeerId; }

    public RoomStatus roomStatus() {
        synchronized (roomLock) {
            var live = participants.entrySet().stream().filter(entry -> entry.getValue().isOpen())
                    .map(entry -> new LiveParticipant(entry.getKey(), displayName(entry.getValue()),
                            limits(entry.getKey()).audioBlocked(), limits(entry.getKey()).cameraBlocked()))
                    .sorted(java.util.Comparator.comparing(LiveParticipant::name)
                            .thenComparing(LiveParticipant::peerId)).toList();
            return new RoomStatus(MAX_PARTICIPANTS, live, hostPeerId, media.configured());
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        var query = UriComponentsBuilder.fromUri(session.getUri()).build().getQueryParams();
        String peerId;
        try {
            peerId = UUID.fromString(query.getFirst("peerId")).toString();
        } catch (IllegalArgumentException | NullPointerException exception) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }
        String name = query.getFirst("name");
        name = name == null ? "" : name.strip().replaceAll("\\p{Cntrl}", "");
        if (name.isBlank() || name.length() > 60) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }
        synchronized (roomLock) {
            if (participants.containsKey(peerId) || pending.containsKey(peerId)) {
                reject(session, "Could not join. Please try again.");
                return;
            }
            session.getAttributes().put("peerId", peerId);
            session.getAttributes().put("displayName", name);
            if (participants.isEmpty()) {
                if ("request".equals(query.getFirst("mode"))) {
                    reject(session, "The host has ended the room. You can start a new live room.");
                    return;
                }
                hostPeerId = peerId;
                admit(peerId, session);
                return;
            }
            if (participants.size() >= MAX_PARTICIPANTS || pending.size() >= MAX_PENDING) {
                reject(session, "The live room is full or has too many requests. Please try later.");
                return;
            }
            pending.put(peerId, session);
            send(session, objectMapper.createObjectNode().put("type", "waiting")
                    .put("message", "Your request was sent. Waiting for the host to accept."));
            send(participants.get(hostPeerId), objectMapper.createObjectNode().put("type", "join-request")
                    .put("peerId", peerId).put("name", name));
        }
    }

    private void admit(String peerId, WebSocketSession session) {
        var existing = new ArrayList<>(participants.entrySet());
        participants.put(peerId, session);
        mediaLimits.put(peerId, new MediaLimits(false, false));
        var joined = objectMapper.createObjectNode().put("type", "joined")
                .put("peerId", peerId).put("hostPeerId", hostPeerId);
        if (media.configured()) {
            var credentials = media.session(mediaRoom(), peerId, displayName(session), true);
            joined.putObject("media").put("url", credentials.url()).put("token", credentials.token());
        }
        var peers = joined.putArray("participants");
        for (var entry : existing) {
            peers.addObject().put("peerId", entry.getKey()).put("name", displayName(entry.getValue()))
                    .put("audioBlocked", limits(entry.getKey()).audioBlocked())
                    .put("cameraBlocked", limits(entry.getKey()).cameraBlocked());
        }
        send(session, joined);
        var notification = objectMapper.createObjectNode().put("type", "peer-joined")
                .put("peerId", peerId).put("name", displayName(session));
        existing.forEach(entry -> send(entry.getValue(), notification));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        if (message.getPayloadLength() > 64_000) {
            session.close(CloseStatus.TOO_BIG_TO_PROCESS);
            return;
        }
        JsonNode request;
        try {
            request = objectMapper.readTree(message.getPayload());
        } catch (JacksonException exception) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }
        if (request == null || !request.isObject()) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }
        synchronized (roomLock) {
            String peerId = (String) session.getAttributes().get("peerId");
            // A waiting guest cannot send WebRTC signals or make host decisions.
            if (peerId == null || participants.get(peerId) != session) return;
            String type = request.path("type").asText();
            if (type.equals("end-live")) {
                if (!peerId.equals(hostPeerId)) return;
                afterConnectionClosed(session, CloseStatus.NORMAL);
                send(session, objectMapper.createObjectNode().put("type", "room-ended").put("message", "You ended the live room."));
                close(session);
                return;
            }
            if (type.equals("sync-requests")) {
                if (!peerId.equals(hostPeerId)) return;
                var snapshot = objectMapper.createObjectNode().put("type", "request-list");
                var requests = snapshot.putArray("requests");
                pending.forEach((id, guest) -> {
                    if (guest.isOpen()) requests.addObject().put("peerId", id).put("name", displayName(guest));
                });
                send(session, snapshot);
                return;
            }
            String target = request.path("target").asText();
            if (type.equals("mute-guest") || type.equals("allow-mic") || type.equals("camera-off")
                    || type.equals("allow-camera") || type.equals("remove-guest")) {
                if (!peerId.equals(hostPeerId) || target.equals(hostPeerId)) return;
                WebSocketSession guest = participants.get(target);
                if (guest == null) return;
                if (type.equals("remove-guest")) {
                    try { media.remove(mediaRoom(), target); }
                    catch (IllegalStateException failure) {
                        send(session, objectMapper.createObjectNode().put("type", "request-error").put("message", failure.getMessage()));
                        return;
                    }
                    afterConnectionClosed(guest, CloseStatus.NORMAL);
                    send(guest, objectMapper.createObjectNode().put("type", "removed")
                            .put("message", "The host removed you from the live room."));
                    close(guest);
                    return;
                }
                var current = limits(target);
                var next = new MediaLimits(type.equals("mute-guest") || (!type.equals("allow-mic") && current.audioBlocked()),
                        type.equals("camera-off") || (!type.equals("allow-camera") && current.cameraBlocked()));
                try { media.restrict(mediaRoom(), target, next.audioBlocked(), next.cameraBlocked()); }
                catch (IllegalStateException failure) {
                    send(session, objectMapper.createObjectNode().put("type", "request-error").put("message", failure.getMessage()));
                    return;
                }
                mediaLimits.put(target, next);
                var controls = objectMapper.createObjectNode().put("type", "guest-controls").put("peerId", target)
                        .put("audioBlocked", next.audioBlocked()).put("cameraBlocked", next.cameraBlocked());
                participants.values().forEach(member -> send(member, controls));
                return;
            }
            if (type.equals("approve") || type.equals("decline")) {
                if (!peerId.equals(hostPeerId)) return;
                WebSocketSession guest = pending.get(target);
                if (guest == null) return;
                if (type.equals("approve") && participants.size() >= MAX_PARTICIPANTS) {
                    send(session, objectMapper.createObjectNode().put("type", "request-error")
                            .put("message", "The room is full. Decline this request or wait for a space."));
                    return;
                }
                pending.remove(target);
                if (type.equals("approve") && guest.isOpen()) admit(target, guest);
                else reject(guest, "The host declined your request to join.");
                send(session, objectMapper.createObjectNode().put("type", "request-resolved").put("peerId", target));
                return;
            }
            JsonNode payload = request.get("payload");
            if ((!type.equals("offer") && !type.equals("answer") && !type.equals("ice"))
                    || payload == null || (!payload.isObject() && !(type.equals("ice") && payload.isNull()))) {
                session.close(CloseStatus.BAD_DATA);
                return;
            }
            WebSocketSession recipient = participants.get(target);
            if (recipient == null || target.equals(peerId)) return;
            var relay = objectMapper.createObjectNode().put("type", type).put("fromPeer", peerId)
                    .put("fromName", displayName(session));
            relay.set("payload", payload);
            send(recipient, relay);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String peerId = (String) session.getAttributes().get("peerId");
        if (peerId == null) return;
        synchronized (roomLock) {
            if (pending.remove(peerId, session)) {
                send(participants.get(hostPeerId), objectMapper.createObjectNode()
                        .put("type", "request-resolved").put("peerId", peerId));
                return;
            }
            if (!participants.remove(peerId, session)) return;
            mediaLimits.remove(peerId);
            if (peerId.equals(hostPeerId)) {
                try { media.end(mediaRoom()); } catch (IllegalStateException failure) {
                    org.slf4j.LoggerFactory.getLogger(getClass()).warn("Unable to close the media room: {}", failure.getMessage());
                }
                var remaining = new ArrayList<>(participants.values());
                remaining.addAll(pending.values());
                participants.clear();
                mediaLimits.clear();
                pending.clear();
                hostPeerId = null;
                var ended = objectMapper.createObjectNode().put("type", "room-ended")
                        .put("message", "The host ended the live room.");
                remaining.forEach(guest -> { send(guest, ended); close(guest); });
            } else {
                var left = objectMapper.createObjectNode().put("type", "peer-left").put("peerId", peerId);
                participants.values().forEach(guest -> send(guest, left));
            }
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        afterConnectionClosed(session, CloseStatus.SERVER_ERROR);
        close(session);
    }

    private MediaLimits limits(String peerId) {
        return mediaLimits.getOrDefault(peerId, new MediaLimits(false, false));
    }

    private String displayName(WebSocketSession session) {
        return (String) session.getAttributes().get("displayName");
    }

    private void reject(WebSocketSession session, String reason) {
        send(session, objectMapper.createObjectNode().put("type", "error").put("message", reason));
        close(session);
    }

    private void close(WebSocketSession session) {
        try { session.close(CloseStatus.NORMAL); } catch (IOException ignored) { }
    }

    private void send(WebSocketSession session, JsonNode message) {
        if (session == null || !session.isOpen()) return;
        synchronized (session) {
            try {
                if (session.isOpen()) session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
            } catch (IOException exception) {
                close(session);
            }
        }
    }
}

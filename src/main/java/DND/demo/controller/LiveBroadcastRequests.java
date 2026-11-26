package DND.demo.controller;

import DND.demo.entity.LiveBroadcastRequest;
import DND.demo.repository.LiveBroadcastRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class LiveBroadcastRequests {
    private final LiveBroadcastRequestRepository repository;
    private final LiveHostTickets tickets;
    public LiveBroadcastRequests(LiveBroadcastRequestRepository repository, LiveHostTickets tickets) {
        this.repository = repository; this.tickets = tickets;
    }
    public record RequestView(String id, String name, String topic, String status, Instant createdAt, Instant expiresAt) {}
    public record CreatedRequest(RequestView request, String token) {}
    private RequestView view(LiveBroadcastRequest request) {
        return new RequestView(request.getId(), request.getName(), request.getTopic(), request.getStatus(), request.getCreatedAt(), request.getExpiresAt());
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private ResponseStatusException fail(HttpStatus status, String message) { return new ResponseStatusException(status, message); }

    @Transactional
    public synchronized CreatedRequest submit(String name, String topic) {
        name = name == null ? "" : name.strip(); topic = topic == null ? "" : topic.strip();
        if (name.isBlank() || name.length() > 60 || topic.length() > 160) throw fail(HttpStatus.BAD_REQUEST, "Enter your name and a topic of at most 160 characters.");
        if (repository.countByStatusAndExpiresAtAfter("PENDING", Instant.now()) >= 100) throw fail(HttpStatus.TOO_MANY_REQUESTS, "The request list is busy. Please try later.");
        var request = new LiveBroadcastRequest();
        String token = UUID.randomUUID().toString() + UUID.randomUUID();
        request.setId(UUID.randomUUID().toString()); request.setName(name); request.setTopic(topic);
        request.setTokenHash(hash(token)); request.setStatus("PENDING"); request.setCreatedAt(Instant.now());
        request.setExpiresAt(Instant.now().plusSeconds(86_400));
        repository.save(request);
        return new CreatedRequest(view(request), token);
    }

    private LiveBroadcastRequest owned(String id, String token) {
        var request = repository.findWithLockById(id).orElseThrow(() -> fail(HttpStatus.NOT_FOUND, "Request not found."));
        if (token == null || !MessageDigest.isEqual(hash(token).getBytes(StandardCharsets.UTF_8), request.getTokenHash().getBytes(StandardCharsets.UTF_8))) {
            throw fail(HttpStatus.FORBIDDEN, "This request belongs to another visitor.");
        }
        if (!request.getExpiresAt().isAfter(Instant.now()) && List.of("PENDING", "APPROVED").contains(request.getStatus())) {
            request.setStatus("EXPIRED"); repository.save(request);
        }
        return request;
    }

    @Transactional
    public RequestView status(String id, String token) { return view(owned(id, token)); }

    @Transactional(readOnly = true)
    public List<RequestView> list() {
        return repository.findByStatusInAndExpiresAtAfterOrderByCreatedAtAsc(List.of("PENDING", "APPROVED"), Instant.now()).stream().map(this::view).toList();
    }

    @Transactional
    public synchronized RequestView decide(String id, boolean approve) {
        var request = repository.findWithLockById(id).orElseThrow(() -> fail(HttpStatus.NOT_FOUND, "Request not found."));
        if ((!request.getStatus().equals("PENDING") && !(request.getStatus().equals("APPROVED") && !approve)) || !request.getExpiresAt().isAfter(Instant.now())) throw fail(HttpStatus.CONFLICT, "This request is no longer pending.");
        if (approve && repository.countByStatusAndExpiresAtAfter("APPROVED", Instant.now()) > 0) throw fail(HttpStatus.CONFLICT, "Another visitor is approved. Wait for them to start or decline that approval first.");
        request.setStatus(approve ? "APPROVED" : "DECLINED");
        if (approve) request.setExpiresAt(Instant.now().plusSeconds(600));
        repository.save(request); return view(request);
    }

    @Transactional
    public RequestView cancel(String id, String token) {
        var request = owned(id, token);
        if (List.of("PENDING", "APPROVED").contains(request.getStatus())) {
            request.setStatus("CANCELLED"); repository.save(request);
        }
        return view(request);
    }

    @Transactional
    public String redeem(String id, String token) {
        var request = owned(id, token);
        if (!request.getStatus().equals("APPROVED")) throw fail(HttpStatus.FORBIDDEN, "An admin must approve your request before you can go live.");
        String ticket = tickets.issue();
        request.setStatus("STARTED"); repository.save(request);
        return ticket;
    }
}

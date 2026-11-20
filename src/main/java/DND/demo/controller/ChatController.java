package DND.demo.controller;

import DND.demo.entity.ChatConversation;
import DND.demo.entity.ChatMessage;
import DND.demo.repository.ChatConversationRepository;
import DND.demo.repository.ChatMessageRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = {"http://localhost:5173", "https://dndcafe.to", "https://www.dndcafe.to",
        "https://d-d-frontend-sigma.vercel.app"})
public class ChatController {
    private final ChatConversationRepository conversations;
    private final ChatMessageRepository messages;

    public ChatController(ChatConversationRepository conversations, ChatMessageRepository messages) {
        this.conversations = conversations;
        this.messages = messages;
    }

    public record StartRequest(@NotBlank @Size(max = 80) String name) {}
    public record MessageRequest(@NotBlank @Size(max = 2000) String content) {}
    public record Session(UUID id, String token) {}
    public record MessageView(Long id, String sender, String content, Instant createdAt) {}

    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    public Session start(@Valid @RequestBody StartRequest request) {
        ChatConversation conversation = new ChatConversation();
        conversation.setId(UUID.randomUUID());
        conversation.setAccessToken(UUID.randomUUID());
        conversation.setVisitorName(request.name().strip());
        conversation.setCreatedAt(Instant.now());
        conversations.save(conversation);
        return new Session(conversation.getId(), conversation.getAccessToken().toString());
    }

    @GetMapping("/conversations/{id}/messages")
    @Transactional(readOnly = true)
    public List<MessageView> history(@PathVariable UUID id, @RequestHeader("X-Chat-Token") String token,
                                     @RequestParam(defaultValue = "0") Long after) {
        authorize(id, token);
        return read(id, after);
    }

    @PostMapping("/conversations/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageView send(@PathVariable UUID id, @RequestHeader("X-Chat-Token") String token,
                            @Valid @RequestBody MessageRequest request) {
        authorize(id, token);
        return save(id, "visitor", request.content());
    }

    @GetMapping("/admin/conversations")
    public List<ChatConversation> inbox() {
        return conversations.findTop100ByOrderByCreatedAtDesc();
    }

    @GetMapping("/admin/conversations/{id}/messages")
    @Transactional(readOnly = true)
    public List<MessageView> adminHistory(@PathVariable UUID id, @RequestParam(defaultValue = "0") Long after) {
        return read(id, after);
    }

    @PostMapping("/admin/conversations/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageView reply(@PathVariable UUID id, @Valid @RequestBody MessageRequest request) {
        return save(id, "staff", request.content());
    }

    private List<MessageView> read(UUID id, Long after) {
        return messages.findTop100ByConversationIdAndIdGreaterThanOrderByIdAsc(id, Math.max(0, after))
                .stream().map(this::view).toList();
    }

    private MessageView save(UUID id, String sender, String content) {
        ChatMessage message = new ChatMessage();
        message.setConversation(conversations.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        message.setSender(sender);
        message.setContent(content.strip());
        message.setCreatedAt(Instant.now());
        return view(messages.save(message));
    }

    private MessageView view(ChatMessage message) {
        return new MessageView(message.getId(), message.getSender(), message.getContent(), message.getCreatedAt());
    }

    private void authorize(UUID id, String suppliedToken) {
        ChatConversation conversation = conversations.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        if (!MessageDigest.isEqual(conversation.getAccessToken().toString().getBytes(StandardCharsets.UTF_8),
                suppliedToken.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

}
